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
package org.ow2.petals.bc.gateway.outbound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jbi.messaging.MessageExchange;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.JbiGatewayJBISender;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.messages.TransportedPropagatedConsumes;
import org.ow2.petals.bc.gateway.messages.TransportedPropagatedConsumesList;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.util.EndpointUtil;
import org.ow2.petals.component.framework.util.ServiceEndpointKey;
import org.w3c.dom.Document;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * There is one instance of this class per opened connection to a provider partner.
 * 
 * It maintains the list of Provides we should create on our side (based on the Consumes propagated)
 *
 * {@link #connect()} and {@link #disconnect()} corresponds to components start and stop.
 * 
 * {@link #init()} and {@link #shutdown()} corresponds to SU init and shutdown
 *
 */
public class ProviderDomain {

    private final JbiProviderDomain jpd;

    private final ProviderMatcher matcher;

    private final Bootstrap bootstrap;

    private final Logger logger;

    /**
     * Updated by {@link #addedProviderService(ServiceKey, Document)} and {@link #removedProviderService(ServiceKey)}.
     * 
     * Contains the services announced by the provider partner as being propagated.
     * 
     * The content of {@link ServiceData} itself is updated by {@link #init()} and {@link #shutdown()} (to add the
     * {@link ServiceEndpointKey} that is activated as an endpoint)
     * 
     * No need for concurrent map because modifications are done by one instance of {@link TransportClient}, so no worry
     * for thread safety.
     */
    private final Map<ServiceKey, ServiceData> services = new HashMap<>();

    /**
     * writeLock for {@link #init()} and {@link #shutdown()}.
     * 
     * readlock for event from the {@link TransportClient}.
     */
    private final ReadWriteLock initLock = new ReentrantReadWriteLock();

    /**
     * immutable, all the provides for this domain.
     */
    private final Map<ServiceKey, Provides> provides;

    @Nullable
    private Channel channel;

    private boolean init = false;

    private static class ServiceData {

        private @Nullable Document description;

        private @Nullable ServiceEndpointKey key;

        public ServiceData(final @Nullable ServiceEndpointKey key, final @Nullable Document description) {
            this.description = description;
            this.key = key;
        }
    }

    public ProviderDomain(final ProviderMatcher matcher, final JbiProviderDomain jpd,
            final Collection<Pair<Provides, JbiProvidesConfig>> provides, final JBISender sender,
            final Bootstrap partialBootstrap, final Logger logger) throws PEtALSCDKException {
        this.matcher = matcher;
        this.jpd = jpd;
        this.logger = logger;

        this.provides = new HashMap<>();
        for (final Pair<Provides, JbiProvidesConfig> pair : provides) {
            this.provides.put(new ServiceKey(pair.getB()), pair.getA());
        }

        final LoggingHandler logging = new LoggingHandler(logger.getName() + ".channel", LogLevel.ERROR);
        final ObjectEncoder objectEncoder = new ObjectEncoder();

        final Bootstrap bootstrap = partialBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // This mirror the protocol used in TransporterListener
                final ChannelPipeline p = ch.pipeline();
                p.addLast(objectEncoder);
                p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                p.addLast(new TransportInitClient(sender, ProviderDomain.this));
                p.addLast(logging);
            }
        }).remoteAddress(jpd.getIp(), jpd.getPort());
        assert bootstrap != null;
        this.bootstrap = bootstrap;
    }

    /**
     * SU INIT
     */
    public void init() throws PEtALSCDKException {
        initLock.writeLock().lock();
        try {
            // TODO should we fail if a provide does not corresponds to a propagated Consumes?
            // Because it is actived...
            for (final Entry<ServiceKey, ServiceData> e : services.entrySet()) {
                final ServiceKey sk = e.getKey();
                final ServiceData data = e.getValue();
                assert sk != null;
                assert data != null;
                registerProviderService(sk, data);
            }

            init = true;

        } catch (final PEtALSCDKException e) {
            logger.severe("Error during ProviderDomain init, undoing everything");

            for (final ServiceData data : services.values()) {
                if (data.key != null) {
                    try {
                        if (!deregisterProviderService(data)) {
                            logger.warning("Expected to deregister '" + data.key + "' but it wasn't registered...");
                        }
                    } catch (final Exception e1) {
                        logger.log(Level.WARNING, "Error while deregistering propagated service", e1);
                    }
                }
            }

            throw e;
        } finally {
            initLock.writeLock().unlock();
        }
    }

    /**
     * SU SHUTDOWN
     */
    public void shutdown() throws PEtALSCDKException {

        final List<Throwable> exceptions = new ArrayList<>();

        initLock.writeLock().lock();
        try {
            init = false;

            for (final ServiceData data : services.values()) {
                assert data.key != null;
                try {
                    if (!deregisterProviderService(data)) {
                        logger.warning("Expected to deregister '" + data.key + "' but it wasn't registered...");
                    }
                } catch (final Exception e) {
                    exceptions.add(e);
                }
            }
        } finally {
            initLock.writeLock().unlock();
        }

        if (!exceptions.isEmpty()) {
            final PEtALSCDKException ex = new PEtALSCDKException("Errors during ProviderDomain shutdown");
            for (final Throwable e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    /**
     * 
     * This corresponds to consumes being declared in the provider domain that we mirror on this side
     * 
     * We receive these notification once we are connected to the other side, i.e., just after component start (and of
     * course after SU deploy)
     * 
     * TODO we should be able to disable the activation of consumes (i.e., only use the provides then!)
     * 
     * TODO should we register endpoint for unexisting service on the other side????
     * 
     */
    public void initProviderServices(final TransportedPropagatedConsumesList initServices) {

        initLock.readLock().lock();
        try {

            final Set<ServiceKey> oldKeys = new HashSet<>(services.keySet());

            for (final TransportedPropagatedConsumes service : initServices.consumes) {
                if (oldKeys.remove(service.service)) {
                    // we already knew this service from a previous connection
                    final ServiceData data = services.get(service.service);
                    assert data != null;
                    if (service.description != null) {
                        // Note: this is not always used because the description is only retrieved by the container
                        // on activation
                        data.description = service.description;
                    }
                } else {
                    // the service is new!
                    final ServiceData data = new ServiceData(null, service.description);

                    try {
                        if (init) {
                            registerProviderService(service.service, data);
                        }

                        // we add it after we are sure no error happened with the registration
                        services.put(service.service, data);
                    } catch (final PEtALSCDKException e) {
                        logger.log(Level.WARNING,
                                "Couldn't register propagated service '" + service.service + "' (" + data.key + ")", e);
                    }
                }
            }

            // these services from a previous connection do not exist anymore!
            for (final ServiceKey sk : oldKeys) {
                final ServiceData data = services.remove(sk);
                assert data != null;
                final ServiceEndpointKey key = data.key;
                if (key != null) {
                    try {
                        if (!deregisterProviderService(data)) {
                            logger.warning("Expected to deregister '" + key + "' but it wasn't registered...");
                        }
                    } catch (final PEtALSCDKException e) {
                        logger.log(Level.WARNING, "Couldn't deregister propagated service '" + sk + "' (" + key + ")",
                                e);
                    }
                } else {
                    assert !init;
                }
            }
        } finally {
            initLock.readLock().unlock();
        }
    }

    private void registerProviderService(final ServiceKey sk, final ServiceData data) throws PEtALSCDKException {
        final Provides p = provides.get(sk);

        final ProviderService ps = new ProviderService() {
            @Override
            public void send(final Exchange exchange) {
                ProviderDomain.this.send(sk, exchange);
            }
        };

        if (p != null) {
            // TODO what about WSDL rewriting for these??
            final ServiceEndpointKey key = new ServiceEndpointKey(p);
            data.key = key;
            matcher.register(key, ps);
        } else {
            final ServiceEndpointKey key = generateSEK(sk);
            data.key = key;
            matcher.register(key, ps, data.description);
        }
    }

    private boolean deregisterProviderService(final ServiceData data) throws PEtALSCDKException {
        final ServiceEndpointKey key = data.key;
        assert key != null;
        data.key = null;
        return matcher.deregister(key);
    }

    private static ServiceEndpointKey generateSEK(final ServiceKey sk) {
        // Note: we should not propagate endpoint name, it is local to each domain
        final String endpointName = EndpointUtil.generateEndpointName();
        final QName serviceName = sk.service == null
                ? new QName(sk.interfaceName.getNamespaceURI(), sk.interfaceName.getLocalPart() + "GeneratedService")
                : sk.service;
        final ServiceEndpointKey key = new ServiceEndpointKey(serviceName, endpointName);
        return key;
    }

    /**
     * This is used to send to the channel for (1st step) exchanges arriving on JBI
     * 
     * 3rd is taken care of by {@link JbiGatewayJBISender} direcly!
     */
    public void send(final ServiceKey service, final Exchange exchange) {
        final MessageExchange mex = exchange.getMessageExchange();
        assert mex != null;
        final TransportedNewMessage m = new TransportedNewMessage(service, mex);
        final Channel channel = this.channel;
        // we can't be disconnected in that case because the component is stopped and we don't process messages!
        assert channel != null;
        channel.writeAndFlush(m);
    }

    public void connect() throws InterruptedException {
        // TODO should I do that async?
        final Channel channel = bootstrap.connect().sync().channel();
        assert channel != null;
        this.channel = channel;
    }

    public void disconnect() {
        final Channel channel = this.channel;
        if (channel != null && channel.isOpen()) {
            // TODO should I do that sync?
            channel.close();
            this.channel = null;
        }
    }

    public void exceptionReceived(final Exception msg) {
        logger.log(Level.WARNING,
                "Received an exeception from the other side, this is purely informative, we can't do anything about it",
                msg);
    }

    public String getAuthName() {
        final String authName = jpd.getAuthName();
        assert authName != null;
        return authName;
    }

    public void close() {
        // this is like a disconnect... but emanating from the other side
        // TODO what should I do? the same as with shutdown()?
    }
}
