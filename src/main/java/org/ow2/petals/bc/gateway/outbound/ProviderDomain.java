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
import org.ow2.easywsdl.wsdl.api.WSDLException;
import org.ow2.petals.bc.gateway.AbstractDomain;
import org.ow2.petals.bc.gateway.JBISender;
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
import org.ow2.petals.component.framework.util.WSDLUtilImpl;
import org.w3c.dom.Document;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
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
 * {@link #connect()} and {@link #disconnect()} corresponds to components start and stop. {@link #connect()} should
 * trigger {@link #updatePropagatedServices(TransportedPropagatedConsumesList)} by the {@link Channel} normally.
 * 
 * {@link #init()} and {@link #shutdown()} corresponds to SU init and shutdown.
 * 
 */
public class ProviderDomain extends AbstractDomain {

    private final JbiProviderDomain jpd;

    private final ProviderMatcher matcher;

    private final Bootstrap bootstrap;

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

        public ServiceData(final @Nullable Document description) {
            this.description = description;
        }
    }

    public ProviderDomain(final ProviderMatcher matcher, final JbiProviderDomain jpd,
            final Collection<Pair<Provides, JbiProvidesConfig>> provides, final JBISender sender,
            final Bootstrap partialBootstrap, final Logger logger) throws PEtALSCDKException {
        super(sender, logger);
        this.matcher = matcher;
        this.jpd = jpd;

        this.provides = new HashMap<>();
        for (final Pair<Provides, JbiProvidesConfig> pair : provides) {
            this.provides.put(new ServiceKey(pair.getB()), pair.getA());
        }

        final LoggingHandler debugs = new LoggingHandler(logger.getName() + ".client", LogLevel.TRACE);
        final LoggingHandler errors = new LoggingHandler(logger.getName() + ".errors", LogLevel.ERROR);
        final ObjectEncoder objectEncoder = new ObjectEncoder();

        final Bootstrap _bootstrap = partialBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // This mirror the protocol used in TransporterListener
                final ChannelPipeline p = ch.pipeline();
                p.addFirst("log-debug", debugs);
                p.addLast(objectEncoder);
                p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                p.addLast("init", new TransportInitClient(logger, ProviderDomain.this));
                p.addLast("log-errors", errors);
            }
        }).remoteAddress(jpd.getIp(), jpd.getPort());
        assert _bootstrap != null;
        bootstrap = _bootstrap;
    }

    /**
     * Register propagated consumes for the JBI listener, can be called after or before the component has started (i.e.,
     * {@link #connect()} has been called).
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
                assert data != null;
                deregisterOrStoreOrLog(data, null);
            }

            throw e;
        } finally {
            initLock.writeLock().unlock();
        }
    }

    /**
     * Deregister the propagated consumes for the JBI Listener
     */
    public void shutdown() throws PEtALSCDKException {

        final List<Exception> exceptions = new ArrayList<>();

        initLock.writeLock().lock();
        try {
            init = false;

            for (final ServiceData data : services.values()) {
                assert data != null;
                deregisterOrStoreOrLog(data, exceptions);
            }
        } finally {
            initLock.writeLock().unlock();
        }

        if (!exceptions.isEmpty()) {
            final PEtALSCDKException ex = new PEtALSCDKException("Errors during ProviderDomain shutdown");
            for (final Exception e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    /**
     * 
     * This registers and initialises the consumes being declared in the provider domain that we mirror on this side.
     * 
     * We receive this notification once we are connected to the other side, i.e., just after component start (and of
     * course after SU deploy)
     * 
     * It can be executed after or before {@link #init()} has been called.
     * 
     * In case of reconnection, it can be called again.
     * 
     * TODO we should be able to disable the activation of consumes (i.e., only use the provides then!)
     * 
     * TODO should we register endpoint for unexisting service on the other side????
     * 
     * 
     */
    public void updatePropagatedServices(final TransportedPropagatedConsumesList propagatedServices) {
        initLock.readLock().lock();
        try {

            final Set<ServiceKey> oldKeys = new HashSet<>(services.keySet());

            for (final TransportedPropagatedConsumes service : propagatedServices.consumes) {
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
                    final ServiceData data = new ServiceData(service.description);

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
                deregisterOrStoreOrLog(data, null);
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
            if (data.description == null) {
                // let's generate a minimal one for now
                try {
                    data.description = WSDLUtilImpl
                            .convertDescriptionToDocument(WSDLUtilImpl.createLightWSDL20Description(sk.interfaceName,
                                    key.getServiceName(), key.getEndpointName()));
                } catch (final WSDLException e) {
                    throw new PEtALSCDKException(e);
                }
            }
            assert data.description != null;
            matcher.register(key, ps, data.description);
        }
    }

    private void deregisterOrStoreOrLog(final ServiceData data, final @Nullable Collection<Exception> exceptions) {
        final ServiceEndpointKey key = data.key;
        if (key != null) {
            try {
                if (!deregisterProviderService(data)) {
                    logger.warning("Expected to deregister '" + key + "' but it wasn't registered...");
                }
            } catch (final PEtALSCDKException e) {
                if (exceptions != null) {
                    exceptions.add(e);
                } else {
                    logger.log(Level.WARNING, "Couldn't deregister propagated service '" + key + "'", e);
                }
            }
        } else {
            assert !init;
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
     * 3rd is taken care of by {@link AbstractDomain}.
     */
    public void send(final ServiceKey service, final Exchange exchange) {
        final MessageExchange mex = exchange.getMessageExchange();
        assert mex != null;
        final TransportedNewMessage m = new TransportedNewMessage(service, mex);
        final Channel channel = this.channel;
        // we can't be disconnected because it would mean that the component is stopped and in that case we don't
        // receive messages!
        assert channel != null;
        // let's use the context of the client so that the error logger does not log the message
        final ChannelHandlerContext ctx = channel.pipeline().context(TransportClient.class);
        assert ctx != null;
        sendToChannel(ctx, m, exchange);
    }

    /**
     * Connect to the provider partner
     * 
     * TODO how to ensure that connecting and authenticating worked or raised an exception if not?
     */
    public void connect() {
        final Channel _channel = bootstrap.connect().channel();
        assert _channel != null;
        channel = _channel;
    }

    /**
     * Disconnect from the provider partner
     */
    public void disconnect() {
        final Channel _channel = this.channel;
        if (_channel != null && _channel.isOpen()) {
            _channel.close();
            channel = null;
        }
    }

    public void exceptionReceived(final Exception msg) {
        logger.log(Level.WARNING,
                "Received an exeception from the other side, this is purely informative, we can't do anything about it",
                msg);
    }

    public String getName() {
        final String id = jpd.getId();
        assert id != null;
        return id;
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
