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
import org.ow2.petals.bc.gateway.inbound.JbiGatewayExternalListener;
import org.ow2.petals.bc.gateway.netty.TransportInitialiser;
import org.ow2.petals.bc.gateway.outbound.JbiGatewayJBIListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiTransportListener;
import org.ow2.petals.component.framework.bc.AbstractBindingComponent;
import org.ow2.petals.component.framework.bc.BindingComponentServiceUnitManager;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LoggingHandler;

/**
 * There is one instance for the whole component. The class is declared in the jbi.xml.
 * 
 * For external exchange handling, see {@link JbiGatewayExternalListener}.
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
    private EventLoopGroup bossGroup;

    @Nullable
    private EventLoopGroup workerGroup;

    /**
     * No need for synchronisation: this is initialised in {@link #doInit()} and that's all!
     */
    private final Map<String, TransportListener> listeners = new HashMap<>();

    @Override
    protected void doInit() throws JBIException {
        // TODO number of thread for the boss (acceptor)?
        bossGroup = new NioEventLoopGroup(1);
        // TODO should we set a specific number of thread? by default it is based on the number of processors...
        workerGroup = new NioEventLoopGroup();

        try {
            for (final JbiTransportListener jtl : JbiGatewayJBIHelper
                    .getListeners(getJbiComponentDescriptor().getComponent())) {
                listeners.put(jtl.id, new TransportListener(jtl, newBootstrap()));
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
        return new ServerBootstrap().group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler());
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
                getLogger().log(Level.WARNING, "Error while stopping listeners", e1);
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

    @Override
    protected BindingComponentServiceUnitManager createServiceUnitManager() {
        return new JbiGatewaySUManager(this);
    }

    public static class TransportListener {

        public final JbiTransportListener jtl;

        public final ServerBootstrap bootstrap;

        /**
         * This {@link Channel} can be used whenever we want to send things to the client! could make sense to send
         * updates or notifications or whatever...
         */
        @Nullable
        public Channel channel;

        /**
         * This must be accessed with synchonized: the access are not very frequent, so no need to introduce a specific
         * performance oriented locking
         * 
         * TODO The key is for now the auth-name declared in the jbi.xml, but later we need to introduce something
         * better to identify consumer and not simply a string Because this corresponds to a validity check of the
         * consumer. e.g., a public key fingerprint or something like that
         */
        public final Map<String, ConsumerDomainDispatcher> dispatchers = new HashMap<>();

        public TransportListener(final JbiTransportListener jtl, final ServerBootstrap partialBootstrap) {
            this.jtl = jtl;
            this.bootstrap = partialBootstrap.childHandler(new TransportInitialiser(this))
                    .localAddress(jtl.port);
        }

        public @Nullable ConsumerDomainDispatcher getConsumerDomainDispatcher(final String consumerAuthName) {
            synchronized (dispatchers) {
                return dispatchers.get(consumerAuthName);
            }
        }

        public void createConsumerDomainDispatcherIfNeeded(final String consumerAuthName) {
            synchronized (dispatchers) {
                if (!dispatchers.containsKey(consumerAuthName)) {
                    dispatchers.put(consumerAuthName, new ConsumerDomainDispatcher());
                }
            }
        }
    }
}
