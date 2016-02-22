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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jbi.JBIException;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.inbound.TransportServer;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.outbound.ProviderMatcher;
import org.ow2.petals.bc.gateway.outbound.ProviderService;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.bc.AbstractBindingComponent;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.su.AbstractServiceUnitManager;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
import org.ow2.petals.component.framework.util.ServiceEndpointKey;
import org.w3c.dom.Document;

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
public class JbiGatewayComponent extends AbstractBindingComponent implements ProviderMatcher {

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
     * Only modification from SUs (see {@link #registerTransportListener(String, JbiTransportListener)}) are concurrent
     */
    private final ConcurrentMap<String, TransportListener> listeners = new ConcurrentHashMap<>();

    @SuppressWarnings("null")
    private final Set<ProviderDomain> providers = Collections
            .newSetFromMap(new ConcurrentHashMap<ProviderDomain, Boolean>());

    private final ConcurrentMap<ServiceEndpointKey, Pair<ServiceEndpoint, ProviderService>> services = new ConcurrentHashMap<>();

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

    public ProviderDomain registerProviderDomain(final String ownerSU, final JbiProviderDomain jpd,
            final Collection<Pair<Provides, JbiProvidesConfig>> provides) throws PEtALSCDKException {
        // TODO should provider domain share their connections if they point to the same ip/port?
        final Logger logger;
        try {
            logger = getContext().getLogger("provider." + ownerSU + "." + jpd.getId(), null);
            assert logger != null;
        } catch (final MissingResourceException | JBIException e) {
            throw new RuntimeException("Impossible case", e);
        }
        final ProviderDomain pd = new ProviderDomain(this, jpd, provides, getSender(), newClientBootstrap(), logger);
        providers.add(pd);
        return pd;
    }

    public void deregisterProviderDomain(final ProviderDomain domain) {
        if (providers.remove(domain)) {
            domain.disconnect();
        }
    }

    public void registerConsumerDomain(final String ownerSU, final JbiConsumerDomain jcd,
            final Collection<Consumes> consumes) throws PEtALSCDKException {
        // TODO support many transports?
        final Logger logger;
        try {
            logger = getContext().getLogger("consumer." + ownerSU + "." + jcd.getId(), null);
            assert logger != null;
        } catch (final MissingResourceException | JBIException e) {
            throw new RuntimeException("Impossible case", e);
        }
        getTransportListener(ownerSU, jcd.getTransport()).register(jcd,
                new ConsumerDomain(getContext(), consumes, logger));
    }

    public void deregisterConsumerDomain(String ownerSU, JbiConsumerDomain jcd) throws PEtALSCDKException {
        getTransportListener(ownerSU, jcd.getTransport()).deregistrer(jcd);
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
            for (final TransportListener tl : listeners.values()) {
                // Bind and start to accept incoming connections.
                tl.bind();
            }

            for (final ProviderDomain tc : providers) {
                tc.connect();
            }
        } catch (final Exception e) {
            // normally this shouldn't really happen, but well...
            getLogger().log(Level.SEVERE, "Error during component start, stopping listeners and clients");

            for (final ProviderDomain tc : providers) {
                try {
                    tc.disconnect();
                } catch (final Exception e1) {
                    // normally this shouldn't really happen, but well...
                    getLogger().log(Level.WARNING, "Error while stopping client", e1);
                }
            }

            for (final TransportListener tl : listeners.values()) {
                try {
                    tl.unbind();
                } catch (final Exception e1) {
                    // normally this shouldn't really happen, but well...
                    getLogger().log(Level.WARNING, "Error while stopping listener", e1);
                }
            }

            throw new JBIException("Error during component start", e);
        }

        this.started = true;
    }

    public void registerTransportListener(final String ownerSU, final JbiTransportListener jtl)
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
        final Logger logger;
        try {
            logger = getContext().getLogger("transport." + (ownerSU == null ? "" : ownerSU + ".") + jtl.getId(), null);
            assert logger != null;
        } catch (final MissingResourceException | JBIException e) {
            throw new RuntimeException("Impossible case", e);
        }
        final TransportListener tl = new TransportListener(getSender(), jtl, newServerBootstrap(), logger);
        if (listeners.putIfAbsent(getTransportListenerName(ownerSU, jtl.getId()), tl) != null) {
            throw new PEtALSCDKException(String.format("Duplicate transporter id '%s'", jtl.getId()));
        }
        if (getLogger().isLoggable(Level.CONFIG)) {
            getLogger().config(String.format(
                    "Transporter '%s' added " + (ownerSU != null ? "for SU '%s'" : "for component"), jtl, ownerSU));
        }
        return tl;
    }

    private String getConnectionName(final String ownerSU, final String providerDomainId) {
        return ownerSU + ":" + providerDomainId;
    }

    private String getTransportListenerName(final @Nullable String ownerSU, final String transportId) {
        return (ownerSU == null ? "c:" : (ownerSU + ":")) + transportId;
    }

    /**
     * TODO do we want to stop all connections? or should we simply pause the event loop?!?!
     */
    @Override
    protected void doStop() throws JBIException {

        this.started = false;

        final List<Throwable> exceptions = new LinkedList<>();

        for (final ProviderDomain tc : providers) {
            try {
                tc.disconnect();
            } catch (final Exception e1) {
                // normally this shouldn't really happen, but well...
                exceptions.add(e1);
            }
        }

        for (final TransportListener tl : this.listeners.values()) {
            try {
                tl.unbind();
            } catch (final Exception e1) {
                // normally this shouldn't really happen, but well...
                exceptions.add(e1);
            }
        }

        if (!exceptions.isEmpty()) {
            final JBIException ex = new JBIException("Errors while stopping listeners");
            for (final Throwable e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    public void deregisterTransportListener(final String ownerSU, final JbiTransportListener jtl) {
        final TransportListener tl = this.listeners.remove(getTransportListenerName(ownerSU, jtl.getId()));
        if (getLogger().isLoggable(Level.CONFIG)) {
            getLogger().config(String.format(
                    "Transporter '%s' removed " + (ownerSU != null ? "for SU '%s'" : "for component"), jtl, ownerSU));
        }
        tl.unbind();
    }

    public TransportListener getTransportListener(final String ownerSU, final String transportId)
            throws PEtALSCDKException {
        final TransportListener tl = listeners.get(getTransportListenerName(ownerSU, transportId));
        if (tl == null) {
            final TransportListener tl2 = listeners.get(getTransportListenerName(null, transportId));
            if (tl2 == null) {
                throw new PEtALSCDKException(
                        String.format("Missing transporter '%s' for SU '%s'", transportId, ownerSU));
            } else {
                return tl2;
            }
        } else {
            return tl;
        }
    }

    @Override
    public @Nullable ProviderService matches(final ServiceEndpointKey key) {
        return services.get(key).getB();
    }

    @Override
    public void register(final ServiceEndpointKey key, final ProviderService ps, final @Nullable Document description)
            throws PEtALSCDKException {
        this.register(key, ps, description, true);
    }

    @Override
    public void register(final ServiceEndpointKey key, final ProviderService ps)
            throws PEtALSCDKException {
        this.register(key, ps, null, false);
    }

    /**
     * TODO if description is null, we should reask for it later!
     * 
     * TODO make it to safely (i.e. detect errors vs valid) support re-registering (for when we reask for description
     * for example)
     */
    private void register(final ServiceEndpointKey key, final ProviderService ps, final @Nullable Document description,
            final boolean activate) throws PEtALSCDKException {

        final ServiceEndpoint endpoint;
        if (activate) {
            // TODO we need to store the Document somewhere so that we can override getServiceDescription!
            // -> store in services, then do the activation, then remove if problem or update endpoint if not
            try {
                // TODO we need to activate or get that only on SU INIT!
                endpoint = getContext().activateEndpoint(key.getServiceName(), key.getEndpointName());
            } catch (final JBIException e) {
                throw new PEtALSCDKException(e);
            }
        } else {
            final ServiceUnitDataHandler suDH = getServiceUnitManager().getSUDataHandler(key);
            // TODO we need to get that only on SU INIT!
            endpoint = suDH.getEndpoint(key);
        }
        assert endpoint != null;

        if (services.putIfAbsent(key, new Pair<>(endpoint, ps)) != null) {
            // shouldn't happen because activation wouldn't have worked then, but well...
            final PEtALSCDKException t = new PEtALSCDKException("Duplicate service " + key);
            if (activate) {
                try {
                    getContext().deactivateEndpoint(endpoint);
                } catch (final JBIException ex) {
                    t.addSuppressed(ex);
                }
            }
            throw t;
        }
    }

    @Override
    public boolean deregister(final ServiceEndpointKey key) throws PEtALSCDKException {
        // TODO this is not correct
        final Pair<ServiceEndpoint, ProviderService> removed = services.remove(key);

        if (removed != null) {
            try {
                getContext().deactivateEndpoint(removed.getA());
            } catch (final JBIException e) {
                throw new PEtALSCDKException(e);
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Used by the {@link TransportServer} to send exchanges. But they come back through one of the
     * {@link JbiGatewayJBIListener}.
     */
    private JbiGatewayJBISender getSender() {
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
