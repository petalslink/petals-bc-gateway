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
import java.util.logging.Level;

import javax.jbi.JBIException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomainDispatcher;
import org.ow2.petals.bc.gateway.inbound.JbiGatewaySender;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.outbound.JbiGatewayJBIListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiTransportListener;
import org.ow2.petals.component.framework.bc.AbstractBindingComponent;
import org.ow2.petals.component.framework.su.AbstractServiceUnitManager;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LoggingHandler;

/**
 * There is one instance for the whole component. The class is declared in the jbi.xml.
 * 
 * For external exchange handling, see {@link ConsumerDomainDispatcher}.
 * 
 * For internal exchange handling, see {@link JbiGatewayJBIListener}.
 * 
 * For SU management, see {@link JbiGatewaySUManager}.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayComponent extends AbstractBindingComponent {

    @Nullable
    private JbiGatewaySender sender;

    @Nullable
    private EventLoopGroup bossGroup;

    @Nullable
    private EventLoopGroup workerGroup;

    /**
     * No need for synchronisation: this is initialised in {@link #doInit()} and that's all!
     */
    private final Map<String, TransportListener> listeners = new HashMap<>();

    @Override
    protected void doInit() throws JBIException {
        this.sender = new JbiGatewaySender(this);

        // TODO number of thread for the boss (acceptor)?
        bossGroup = new NioEventLoopGroup(1);
        // TODO should we set a specific number of thread? by default it is based on the number of processors...
        workerGroup = new NioEventLoopGroup();

        try {
            for (final JbiTransportListener jtl : JbiGatewayJBIHelper
                    .getListeners(getJbiComponentDescriptor().getComponent())) {
                listeners.put(jtl.id, new TransportListener(this, jtl, newBootstrap()));
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
            // we can simply clear, nothing was started
            listeners.clear();
            throw new JBIException("Error while initialising component", e);
        }
    }

    private ServerBootstrap newBootstrap() {
        final ServerBootstrap bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class).handler(new LoggingHandler());
        assert bootstrap != null;
        return bootstrap;
    }

    @Override
    protected void doShutdown() throws JBIException {
        assert bossGroup != null;
        bossGroup.shutdownGracefully();

        assert workerGroup != null;
        workerGroup.shutdownGracefully();

        listeners.clear();
    }

    @Override
    protected void doStart() throws JBIException {
        try {
            for (final TransportListener tl : this.listeners.values()) {
                // Bind and start to accept incoming connections.
                assert tl.bootstrap != null;
                final Channel channel = tl.bootstrap.bind().sync().channel();
                assert channel != null;
                tl.channel = channel;
            }
        } catch (final Exception e) {
            // normally this shouldn't really happen, but well...
            getLogger().log(Level.SEVERE, "Error during component start, stopping listeners");
            for (final TransportListener tl : this.listeners.values()) {
                final Channel channel = tl.channel;
                if (channel != null) {
                    try {
                        stopListener(channel);
                    } catch (final Exception e1) {
                        // normally this shouldn't really happen, but well...
                        getLogger().log(Level.WARNING, "Error while stopping listeners", e1);
                    }
                }
            }
            throw new JBIException("Error while starting component", e);
        }

        // TODO connect to provider domains
    }

    @Override
    protected void doStop() throws JBIException {
        final List<Throwable> exceptions = new LinkedList<>();
        for (final TransportListener tl : this.listeners.values()) {
            final Channel channel = tl.channel;
            assert channel != null;
            try {
                stopListener(channel);
            } catch (final Exception e1) {
                // normally this shouldn't really happen, but well...
                exceptions.add(e1);
            }
        }

        // TODO disconnect from provider domains

        if (!exceptions.isEmpty()) {
            final JBIException ex = new JBIException("Errors while stopping listeners");
            for (final Throwable e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    private void stopListener(final Channel c) throws InterruptedException {
        c.close();
    }

    public @Nullable TransportListener getTransportListener(final String transportId) {
        return listeners.get(transportId);
    }

    /**
     * Used by the {@link ConsumerDomainDispatcher} to send exchanges. But they come back through one of the
     * {@link JbiGatewayJBIListener}.
     */
    public JbiGatewaySender getSender() {
        assert sender != null;
        return sender;
    }

    @Override
    protected AbstractServiceUnitManager createServiceUnitManager() {
        return new JbiGatewaySUManager(this);
    }

}
