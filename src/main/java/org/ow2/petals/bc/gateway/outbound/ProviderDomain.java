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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.jbi.messaging.MessageExchange;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.JbiGatewayJBISender;
import org.ow2.petals.bc.gateway.inbound.ConsumerAuthenticator;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainPropagatedConsumes;
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

/**
 * There is one instance of this class per opened connection to a provider partner.
 * 
 * It maintains the list of Provides we should create on our side (based on the Consumes propagated)
 *
 * {@link #connect()} and {@link #disconnect()} corresponds to components start and stop.
 * 
 * {@link #init()} and {@link #shutdown()} corresponds to SU init and shutdown
 * 
 * TODO how do we know when the connection is closed by the other side? In that case we should maybe deregister
 * everything?
 *
 */
public class ProviderDomain {

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
     */
    private final ConcurrentMap<ServiceKey, ServiceData> services = new ConcurrentHashMap<>();

    /**
     * immutable, all the provides for this domain.
     */
    private final Map<ServiceKey, Provides> provides;

    @Nullable
    private Channel channel;

    private volatile boolean init = false;

    private static class ServiceData {

        private @Nullable Document description;

        private @Nullable ServiceEndpointKey key;

        public ServiceData(final @Nullable ServiceEndpointKey key, final @Nullable Document description) {
            this.description = description;
            this.key = key;
        }
    }

    // TODO add a logger
    public ProviderDomain(final ProviderMatcher matcher, final JbiProviderDomain jpd,
            final Collection<Pair<Provides, JbiProvidesConfig>> provides, final JBISender sender,
            final Bootstrap partialBootstrap) throws PEtALSCDKException {
        this.matcher = matcher;
        this.jpd = jpd;

        final Map<ServiceKey, Provides> pm = new HashMap<>();

        for (final Pair<Provides, JbiProvidesConfig> pair : provides) {
            pm.put(new ServiceKey(pair.getB()), pair.getA());
        }

        this.provides = Collections.unmodifiableMap(pm);

        final Bootstrap bootstrap = partialBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // This mirror the protocol used in TransporterListener
                final ChannelPipeline p = ch.pipeline();
                p.addLast(new ObjectEncoder());
                p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                p.addLast(new TransportInitClient(sender, ProviderDomain.this));
            }
        }).remoteAddress(jpd.getIp(), jpd.getPort());
        assert bootstrap != null;
        this.bootstrap = bootstrap;
    }

    /**
     * SU INIT
     */
    public void init() throws PEtALSCDKException {
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

            for (final ServiceData data : services.values()) {
                if (data.key != null) {
                    try {
                        if (!deregisterProviderService(data)) {
                            // TODO log strange situation
                        }
                    } catch (final Exception e1) {
                        // TODO log!
                    }
                }
            }

            throw e;
        }
    }

    /**
     * SU SHUTDOWN
     */
    public void shutdown() throws PEtALSCDKException {

        init = false;

        final List<Throwable> exceptions = new ArrayList<>();

        for (final ServiceData data : services.values()) {
            if (data.key != null) {
                try {
                    if (!deregisterProviderService(data)) {
                        // TODO log strange situation
                    }
                } catch (final Exception e) {
                    exceptions.add(e);
                }
            } else {
                // TODO this is not normal...
            }
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
     * TODO we should be able to disable the activation of consumes (i.e., only use the provides then!) TODO should we
     * register endpoint for unexisting service on the other side????
     */
    public void initProviderServices(final Collection<TransportedToConsumerDomainPropagatedConsumes> initServices)
            throws PEtALSCDKException {

        final Set<ServiceKey> oldKeys = new HashSet<>(services.keySet());

        for (final TransportedToConsumerDomainPropagatedConsumes service : initServices) {
            if (oldKeys.remove(service.service)) {
                // we already knew this service from a previous connection
                final ServiceData data = services.get(service.service);
                if (data != null) {
                    if (service.description != null) {
                        // TODO anyway, description is only get by container on activation... maybe change the way the
                        // container behave?
                        data.description = service.description;
                    }
                } else {
                    // TODO this can't happen normally... !
                }
            } else {
                // the service is new!
                final ServiceData data = new ServiceData(null, service.description);
                services.put(service.service, data);

                if (init) {
                    // TODO handle that exception differently??!!
                    registerProviderService(service.service, data);
                }
            }
        }

        // these services from a previous connection do not exist anymore!
        for (final ServiceKey sk : oldKeys) {
            final ServiceData data = services.remove(sk);
            if (data != null) {
                final ServiceEndpointKey key = data.key;
                if (key != null) {
                    // TODO handle that exception differently??!!
                    if (!deregisterProviderService(data)) {
                        // TODO log strange situation
                    }
                } else {
                    // it means it wasn't activated (i.e. SU is not init)
                    // TODO check !init?
                }
            } else {
                // TODO this can't happen normally... !
            }
        }
    }

    private void registerProviderService(final ServiceKey sk, final ServiceData data) throws PEtALSCDKException {
        final Provides p = provides.get(sk);
        if (p != null) {
            // TODO what about WSDL rewriting for these??
            final ServiceEndpointKey key = new ServiceEndpointKey(p);
            data.key = key;
            matcher.register(key, getProviderService(sk));
        } else {
            final ServiceEndpointKey key = generateSEK(sk);
            data.key = key;
            matcher.register(key, getProviderService(sk), data.description);
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

    private ProviderService getProviderService(final ServiceKey sk) {
        return new ProviderService() {
            @Override
            public void send(final Exchange exchange) {
                ProviderDomain.this.send(sk, exchange);
            }
        };
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

    /**
     * TODO on connect, we should be ready to receive new services via
     * {@link #addedProviderService(ServiceKey, Document)} and remove/add the previous ones!
     */
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
        // TODO just log it: receiving an exception here means that there is nothing to do, it is just
        // information for us.
    }

    /**
     * TODO replace that by something complexer, see {@link ConsumerAuthenticator}.
     */
    public String getAuthName() {
        final String authName = jpd.getAuthName();
        assert authName != null;
        return authName;
    }
}
