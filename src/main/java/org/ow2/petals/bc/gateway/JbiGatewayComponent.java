/**
 * Copyright (c) 2015 Linagora
 * 
 * This program/library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or (at your
 * option) any later version.
 * 
 * This program/library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program/library; If not, see <http://www.gnu.org/licenses/>
 * for the GNU Lesser General Public License version 2.1.
 */
package org.ow2.petals.bc.gateway;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import javax.jbi.JBIException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.inbound.TransportServer;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.outbound.TransportConnection;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiProviderDomain;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiTransportListener;
import org.ow2.petals.component.framework.bc.AbstractBindingComponent;
import org.ow2.petals.component.framework.su.AbstractServiceUnitManager;
import org.ow2.petals.component.framework.util.ServiceProviderEndpointKey;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LoggingHandler;

/**
 * There is one instance for the whole component. The class is declared in the jbi.xml.
 * 
 * For external exchange handling, see {@link JbiGatewayJBISender} and {@link TransportServer}.
 * 
 * For internal exchange handling, see {@link JbiGatewayJBIListener}.
 * 
 * For SU management, see {@link JbiGatewaySUManager}.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayComponent extends AbstractBindingComponent {

    /**
     * We need only one sender per component because it is stateless (for the functionalities we use)
     */
    @Nullable
    private JbiGatewayJBISender sender;

    @Nullable
    private EventLoopGroup bossGroup;

    @Nullable
    private EventLoopGroup workerGroup;

    @Nullable
    private EventLoopGroup clientsGroup;

    /**
     * No need for synchronisation: this is initialised in {@link #doInit()} and that's all!
     */
    private final Map<String, TransportListener> listeners = new HashMap<>();

    private final Map<String, TransportConnection> clients = new ConcurrentHashMap<>();

    @Override
    protected void doInit() throws JBIException {
        sender = new JbiGatewayJBISender(this);

        try {
            // TODO number of thread for the boss (acceptor)?
            bossGroup = new NioEventLoopGroup(1);
            // TODO should we set a specific number of thread? by default it is based on the number of processors...
            workerGroup = new NioEventLoopGroup();
            // TODO should we set a specific number of thread? by default it is based on the number of processors...
            // TODO could we share it with the workerGroup?! normally yes... but do we want?
            clientsGroup = new NioEventLoopGroup();

            for (final JbiTransportListener jtl : JbiGatewayJBIHelper
                    .getListeners(getJbiComponentDescriptor().getComponent())) {
                listeners.put(jtl.id, new TransportListener(this, jtl, newServerBootstrap()));
                if (getLogger().isLoggable(Level.CONFIG)) {
                    getLogger().config(String.format("Transporter added: %s", jtl));
                }
            }
        } catch (final Exception e) {
            getLogger().log(Level.SEVERE, "Error during component initialisation, undoing everything");
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
                bossGroup = null;
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
                workerGroup = null;
            }
            if (clientsGroup != null) {
                clientsGroup.shutdownGracefully();
                clientsGroup = null;
            }

            // we can simply clear, nothing was started
            listeners.clear();
            throw new JBIException("Error while initialising component", e);
        }
    }

    public ProviderDomain createConnection(final String name, final JbiProviderDomain jpd) {
        // TODO should provider domain share their connections if they point to the same ip/port?
        final ProviderDomain pd = new ProviderDomain(this, jpd);
        clients.put(name, new TransportConnection(this, pd, newClientBootstrap()));
        return pd;
    }

    public void deleteConnection(final String name) {
        final TransportConnection conn = clients.remove(name);
        if (conn != null) {
            conn.disconnect();
        }
    }

    private Bootstrap newClientBootstrap() {
        // TODO use epoll on linux?
        final Bootstrap bootstrap = new Bootstrap().group(clientsGroup).channel(NioSocketChannel.class);
        assert bootstrap != null;
        return bootstrap;
    }

    private ServerBootstrap newServerBootstrap() {
        // TODO use epoll on linux?
        final ServerBootstrap bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class).handler(new LoggingHandler());
        assert bootstrap != null;
        return bootstrap;
    }

    @Override
    protected void doShutdown() throws JBIException {
        assert bossGroup != null;
        // TODO is that ok?
        bossGroup.shutdownGracefully();
        bossGroup = null;

        assert workerGroup != null;
        // TODO is that ok?
        workerGroup.shutdownGracefully();
        workerGroup = null;

        assert clientsGroup != null;
        // TODO is that ok?
        clientsGroup.shutdownGracefully();
        clientsGroup = null;

        listeners.clear();
    }

    @Override
    protected void doStart() throws JBIException {
        try {
            for (final TransportListener tl : this.listeners.values()) {
                // Bind and start to accept incoming connections.
                tl.bind();
            }
        } catch (final Exception e) {
            // normally this shouldn't really happen, but well...
            getLogger().log(Level.SEVERE, "Error during component start, stopping listeners");
            for (final TransportListener tl : this.listeners.values()) {
                try {
                    tl.unbind();
                } catch (final Exception e1) {
                    // normally this shouldn't really happen, but well...
                    getLogger().log(Level.WARNING, "Error while stopping listeners", e1);
                }
            }
            throw new JBIException("Error while starting component", e);
        }

        // TODO resume/start connections to provider domains
    }

    @Override
    protected void doStop() throws JBIException {
        final List<Throwable> exceptions = new LinkedList<>();
        for (final TransportListener tl : this.listeners.values()) {
            try {
                tl.unbind();
            } catch (final Exception e1) {
                // normally this shouldn't really happen, but well...
                exceptions.add(e1);
            }
        }

        // TODO pause/stop connections to provider domains

        if (!exceptions.isEmpty()) {
            final JBIException ex = new JBIException("Errors while stopping listeners");
            for (final Throwable e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    public @Nullable TransportListener getTransportListener(final String transportId) {
        return listeners.get(transportId);
    }

    public @Nullable ProviderDomain getProviderDomain(final ServiceProviderEndpointKey key) {
        // TODO
        return null;
    }

    /**
     * Used by the {@link TransportServer} to send exchanges. But they come back through one of the
     * {@link JbiGatewayJBIListener}.
     */
    public JbiGatewayJBISender getSender() {
        assert sender != null;
        return sender;
    }

    @Override
    protected AbstractServiceUnitManager createServiceUnitManager() {
        return new JbiGatewaySUManager(this);
    }

    @Override
    public JbiGatewaySUManager getServiceUnitManager() {
        final AbstractServiceUnitManager suManager = super.getServiceUnitManager();
        assert suManager != null;
        return (JbiGatewaySUManager) suManager;
    }

}
