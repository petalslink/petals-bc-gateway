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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jbi.JBIException;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.messaging.MessagingException;
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
import org.ow2.petals.binding.gateway.clientserver.api.AdminService;
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
import io.netty.handler.codec.serialization.ClassResolver;
import io.netty.handler.codec.serialization.ClassResolvers;

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
public class JbiGatewayComponent extends AbstractBindingComponent implements ProviderMatcher, AdminService {

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

    private final Map<String, TransportListener> listeners = new HashMap<>();
    
    private final ConcurrentMap<ServiceEndpointKey, ServiceData> services = new ConcurrentHashMap<>();

    private static class ServiceData {
        private @Nullable ServiceEndpoint endpoint;

        private final ProviderService service;

        private final @Nullable Document description;

        public ServiceData(final ProviderService service, final @Nullable Document description) {
            this.service = service;
            this.description = description;
        }
    }

    private boolean started = false;

    @Override
    protected void doInit() throws JBIException {
        sender = new JbiGatewayJBISender(this);

        // only one thread for accepting new connections is enough, we don't create connections often
        bossGroup = new NioEventLoopGroup(1);
        // TODO choose a specific number of threads, knowing that they are only for very small tasks
        // This represents the number of thread concurrently usable by all the incoming connections
        workerGroup = new NioEventLoopGroup();
        // TODO choose a specific number of threads, knowing that they are only for very small tasks
        // This represents the number of thread concurrently usable by all the outgoing connections
        clientsGroup = new NioEventLoopGroup();

        for (final JbiTransportListener jtl : JbiGatewayJBIHelper
                .getTransportListeners(getJbiComponentDescriptor().getComponent())) {
            assert jtl != null;
            addTransporterListener(jtl);
        }
    }

    /**
     * This will create, register and start if needed a provider domain
     */
    public ProviderDomain registerProviderDomain(final String ownerSU, final JbiProviderDomain jpd,
            final Collection<Pair<Provides, JbiProvidesConfig>> provides) throws PEtALSCDKException {
        // TODO should provider domain share their connections if they point to the same ip/port?
        final Logger logger;
        try {
            logger = getContext().getLogger("provider." + ownerSU + "." + jpd.getId(), null);
            assert logger != null;
        } catch (final MissingResourceException | JBIException e) {
            throw new PEtALSCDKException("Can't create logger", e);
        }
        final ProviderDomain pd = new ProviderDomain(this, jpd, provides, getSender(), newClientBootstrap(), logger,
                newClassResolver());
        if (started) {
            pd.connect();
        }
        return pd;
    }

    public void deregisterProviderDomain(final ProviderDomain domain) {
        domain.disconnect();
    }

    public ConsumerDomain createConsumerDomain(final String ownerSU, final JbiConsumerDomain jcd,
            final Collection<Consumes> consumes) throws PEtALSCDKException {
        // TODO support many transports for one consumer domain
        final Logger logger;
        try {
            logger = getContext().getLogger("consumer." + ownerSU + "." + jcd.getId(), null);
            assert logger != null;
        } catch (final MissingResourceException | JBIException e) {
            throw new PEtALSCDKException("Can't create logger", e);
        }
        final TransportListener tl = getTransportListener(jcd.getTransport());
        return new ConsumerDomain(tl, getContext(), jcd, consumes, getSender(), logger);
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
                .channel(NioServerSocketChannel.class);
        assert bootstrap != null;
        return bootstrap;
    }

    /**
     * This constructs a {@link ClassResolver} from the container {@link ClassLoader}.
     * 
     * This is needed because when we receive a {@link MessageExchange} from the other side, its class was coming from
     * the container on the other side. If we didn't use the container {@link ClassLoader}, then we couldn't unserialize
     * the {@link MessageExchange}.
     */
    private ClassResolver newClassResolver() throws PEtALSCDKException {
        final ClassLoader cl;
        try {
            cl = getChannel().createExchangeFactory().createInOnlyExchange().getClass().getClassLoader();
        } catch (final MessagingException e) {
            throw new PEtALSCDKException(e);
        }
        final ClassResolver cr = ClassResolvers.cacheDisabled(cl);
        final ClassResolver mine = ClassResolvers.cacheDisabled(null);
        return new ClassResolver() {
            @Override
            public @Nullable Class<?> resolve(final @Nullable String className) throws ClassNotFoundException {
                try {
                    return mine.resolve(className);
                } catch (final ClassNotFoundException e) {
                    return cr.resolve(className);
                }
            }
        };
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

        for (final TransportListener tl : listeners.values()) {
            // Bind and start to accept incoming connections.
            tl.bind();
        }

        for (final ProviderDomain pd : getServiceUnitManager().getProviderDomains()) {
            pd.connect();
        }

        this.started = true;
    }

    private TransportListener addTransporterListener(final JbiTransportListener jtl) throws PEtALSCDKException {
        if (listeners.containsKey(jtl.getId())) {
            throw new PEtALSCDKException(String.format("Duplicate transporter id '%s'", jtl.getId()));
        }
        final Logger logger;
        try {
            logger = getContext().getLogger(jtl.getId(), null);
            assert logger != null;
        } catch (final MissingResourceException | JBIException e) {
            throw new PEtALSCDKException("Can't create logger", e);
        }
        final TransportListener tl = new TransportListener(jtl, newServerBootstrap(), logger, newClassResolver());
        listeners.put(jtl.getId(), tl);
        if (getLogger().isLoggable(Level.CONFIG)) {
            getLogger().config(String.format("Transporter '%s' added", jtl));
        }
        return tl;
    }

    /**
     * TODO do we really want to disconnect everything?! more like simply stop processing new exchanges!
     */
    @Override
    protected void doStop() throws JBIException {

        this.started = false;

        final List<Throwable> exceptions = new LinkedList<>();

        for (final ProviderDomain tc : getServiceUnitManager().getProviderDomains()) {
            try {
                tc.disconnect();
            } catch (final Exception e1) {
                // normally this shouldn't really happen, but well...
                exceptions.add(e1);
            }
        }

        // TODO the consumerdomain are not disconnected!
        for (final TransportListener tl : listeners.values()) {
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

    private boolean removeTransportListener(final JbiTransportListener jtl) {
        final TransportListener tl = this.listeners.remove(jtl.getId());
        if (tl != null) {
            if (getLogger().isLoggable(Level.CONFIG)) {
                getLogger().config(String.format("Transporter '%s' removed", jtl));
            }
            tl.unbind();
            return true;
        } else {
            return false;
        }
    }

    private TransportListener getTransportListener(final String transportId) throws PEtALSCDKException {
        final TransportListener tl = listeners.get(transportId);
        if (tl == null) {
            throw new PEtALSCDKException(String.format("Missing transporter '%s'", transportId));
        } else {
            return tl;
        }
    }

    @Override
    public @Nullable ProviderService matches(final ServiceEndpointKey key) {
        return services.get(key).service;
    }

    @Override
    public void register(final ServiceEndpointKey key, final ProviderService ps, final Document description)
            throws PEtALSCDKException {
        this.register(key, ps, description, true);
    }

    @Override
    public void register(final ServiceEndpointKey key, final ProviderService ps) throws PEtALSCDKException {
        this.register(key, ps, null, false);
    }

    @Override
    public @Nullable Document getServiceDescription(final @Nullable ServiceEndpoint endpoint) {
        final Document desc = super.getServiceDescription(endpoint);
        if (desc == null) {
            final ServiceData data = services.get(new ServiceEndpointKey(endpoint));
            return data != null ? data.description : null;
        }
        return desc;
    }

    private void register(final ServiceEndpointKey key, final ProviderService ps, final @Nullable Document description,
            final boolean activate) throws PEtALSCDKException {

        final ServiceData data = new ServiceData(ps, description);
        if (services.putIfAbsent(key, data) != null) {
            throw new PEtALSCDKException("Duplicate service " + key);
        }

        final ServiceEndpoint endpoint;
        if (activate) {
            assert description != null;
            try {
                endpoint = getContext().activateEndpoint(key.getServiceName(), key.getEndpointName());
                getLogger().log(Level.INFO, "New Service Endpoint deployed: " + endpoint);
            } catch (final JBIException e) {
                services.remove(key);
                throw new PEtALSCDKException(e);
            }
        } else {
            assert description == null;
            final ServiceUnitDataHandler suDH = getServiceUnitManager().getSUDataHandler(key);
            endpoint = suDH.getEndpoint(key);
        }
        assert endpoint != null;

        data.endpoint = endpoint;
    }

    @Override
    public boolean deregister(final ServiceEndpointKey key) throws PEtALSCDKException {
        // TODO this is not correct (why?!!)
        final ServiceData removed = services.remove(key);

        if (removed != null) {
            try {
                getContext().deactivateEndpoint(removed.endpoint);
                getLogger().log(Level.INFO, "Service Endpoint undeployed: " + removed.endpoint);
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

    @Override
    public Collection<String> getMBeanOperationsNames() {
        final Collection<String> methods = super.getMBeanOperationsNames();

        methods.add(JbiGatewayBootstrap.METHOD_ADD_TRANSPORT);
        methods.add(JbiGatewayBootstrap.METHOD_REMOVE_TRANSPORT);
        methods.add("refreshPropagations");

        return methods;
    }
    
    @Override
    public void refreshPropagations() {
        // TODO synchronization?!
        for (final ConsumerDomain cd : getServiceUnitManager().getConsumerDomains()) {
            cd.refreshPropagations();
        }
    }

    @Override
    public void reloadPlaceHolders() {
        super.reloadPlaceHolders();


    }

    public void addTransportListener(final String id, final int port) throws PEtALSCDKException {
        // TODO synchronization?!
        if (listeners.containsKey(id)) {
            throw new PEtALSCDKException("A transport listener with id '" + id + "' already exists");
        }

        final JbiTransportListener jtl = JbiGatewayJBIHelper.addTransportListener(id, port,
                getJbiComponentDescriptor().getComponent());

        try {
            final TransportListener tl = addTransporterListener(jtl);
            if (started) {
                tl.bind();
            }
        } catch (final PEtALSCDKException e) {
            try {
                JbiGatewayJBIHelper.removeTransportListener(id, getJbiComponentDescriptor().getComponent());
            } catch (final PEtALSCDKException ex) {
                e.addSuppressed(ex);
            }
            throw e;
        }
    }

    public boolean removeTransportListener(final String id) throws PEtALSCDKException {
        // TODO synchronization?!
        final JbiTransportListener removed = JbiGatewayJBIHelper.removeTransportListener(id,
                getJbiComponentDescriptor().getComponent());

        if (removed != null) {
            // TODO should I remove it even if removed is null? In case of inconsistency, it would be safer...
            return removeTransportListener(removed);
        } else {
            return false;
        }
    }
}
