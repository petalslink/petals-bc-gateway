/**
 * Copyright (c) 2015-2016 Linagora
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

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import javax.jbi.JBIException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.inbound.TransportServer;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.outbound.TransportConnection;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
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
     * Only modification from SUs (see {@link #addSUTransporterListener(String, JbiTransportListener)}) are concurrent
     */
    private final ConcurrentMap<String, TransportListener> listeners = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, TransportConnection> clients = new ConcurrentHashMap<>();

    private boolean started = false;

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
                assert jtl != null;
                addTransporterListener(null, jtl);
            }
        } catch (final Exception e) {
            getLogger().log(Level.SEVERE, "Error during component init, undoing everything");
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
            throw new JBIException("Error during component init", e);
        }
    }

    public ProviderDomain createConnection(final String name, final JbiProviderDomain jpd) {
        // TODO should provider domain share their connections if they point to the same ip/port?
        final ProviderDomain pd = new ProviderDomain(this, jpd);
        clients.put(name, new TransportConnection(getSender(), pd, newClientBootstrap()));
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
            throw new JBIException("Error during component start", e);
        }

        // TODO resume/start connections to provider domains

        this.started = true;
    }

    public void addSUTransporterListener(final String ownerSU, final JbiTransportListener jtl)
            throws PEtALSCDKException {
        final TransportListener tl = addTransporterListener(ownerSU, jtl);
        if (started) {
            try {
                tl.bind();
            } catch (final Exception e) {
                // normally this shouldn't really happen, but well...
                throw new PEtALSCDKException(String.format("Error while starting transporter '%s'", jtl));
            }
        }
    }

    private TransportListener addTransporterListener(final @Nullable String ownerSU, final JbiTransportListener jtl)
            throws PEtALSCDKException {
        final TransportListener tl = new TransportListener(getServiceUnitManager(), jtl, newServerBootstrap());
        if (listeners.putIfAbsent(getTransportListenerName(ownerSU, jtl.getId()), tl) != null) {
            throw new PEtALSCDKException(String.format("Duplicate transporter id '%s'", jtl.getId()));
        }
        if (getLogger().isLoggable(Level.CONFIG)) {
            getLogger().config(String.format(
                    "Transporter '%s' added " + (ownerSU != null ? "for SU '%s'" : "for component"), jtl, ownerSU));
        }
        return tl;
    }

    private String getTransportListenerName(final @Nullable String ownerSU, final String transportId) {
        return (ownerSU == null ? "c-" : (ownerSU + "-")) + transportId;
    }

    @Override
    protected void doStop() throws JBIException {

        this.started = false;

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

    public void removeSUTransporterListener(final String ownerSU, final JbiTransportListener jtl) {
        final TransportListener tl = this.listeners.remove(getTransportListenerName(ownerSU, jtl.getId()));
        if (getLogger().isLoggable(Level.CONFIG)) {
            getLogger().config(String.format(
                    "Transporter '%s' removed " + (ownerSU != null ? "for SU '%s'" : "for component"), jtl, ownerSU));
        }
        tl.unbind();
    }

    public @Nullable TransportListener getTransportListener(final String ownerSU, final String transportId) {
        final TransportListener tl = listeners.get(getTransportListenerName(ownerSU, transportId));
        if (tl == null) {
            return listeners.get(getTransportListenerName(null, transportId));
        } else {
            return tl;
        }
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
