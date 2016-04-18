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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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
import org.ow2.petals.bc.gateway.messages.TransportedDocument;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.messages.TransportedPropagatedConsumes;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.bc.gateway.utils.JbiGatewayProvideExtFlowStepBeginLogData;
import org.ow2.petals.bc.gateway.utils.LastLoggingHandler;
import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.logger.StepLogHelper;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
import org.ow2.petals.component.framework.util.EndpointUtil;
import org.ow2.petals.component.framework.util.ServiceEndpointKey;
import org.ow2.petals.component.framework.util.WSDLUtilImpl;
import org.w3c.dom.Document;

import com.ebmwebsourcing.easycommons.lang.StringHelper;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.serialization.ClassResolver;
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
 * trigger {@link #updatePropagatedServices(TransportedPropagatedConsumes)} by the {@link Channel} normally.
 * 
 * {@link #register()} and {@link #deregister()} corresponds to SU init and shutdown.
 * 
 */
public class ProviderDomain extends AbstractDomain {

    public static final String LOG_ERRORS_HANDLER = "log-errors";

    public static final String LOG_DEBUG_HANDLER = "log-debug";

    public static final String SSL_HANDLER = "ssl";

    private JbiProviderDomain jpd;

    private final ProviderMatcher matcher;

    private final Bootstrap bootstrap;

    /**
     * Updated by {@link #updatePropagatedServices(TransportedPropagatedConsumes)}.
     * 
     * Contains the services announced by the provider partner as being propagated.
     * 
     * The content of {@link ServiceData} itself is updated by {@link #register()} and {@link #deregister()} (to add the
     * {@link ServiceEndpointKey} that is activated as an endpoint)
     * 
     */
    private final Map<ServiceKey, ServiceData> services = new HashMap<>();

    /**
     * lock for manipulating the services map to avoid new services not taken into account during {@link #register()}
     * and {@link #deregister()}.
     */
    private final Lock servicesLock = new ReentrantLock();

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

    public ProviderDomain(final ProviderMatcher matcher, final ServiceUnitDataHandler handler,
            final JbiProviderDomain jpd, final Collection<Pair<Provides, JbiProvidesConfig>> provides,
            final JBISender sender, final Bootstrap partialBootstrap, final Logger logger, final ClassResolver cr)
            throws PEtALSCDKException {
        super(sender, logger);

        this.matcher = matcher;
        this.jpd = jpd;

        this.provides = new HashMap<>();
        for (final Pair<Provides, JbiProvidesConfig> pair : provides) {
            if (StringHelper.isNullOrEmpty(pair.getA().getWsdl())) {
                throw new PEtALSCDKException(
                        "The provides " + pair.getA().getServiceName() + " must have a WSDL defined");
            }
            this.provides.put(new ServiceKey(pair.getB()), pair.getA());
        }

        final LoggingHandler debugs = new LoggingHandler(logger.getName() + ".client", LogLevel.TRACE);
        final LastLoggingHandler errors = new LastLoggingHandler(logger.getName() + ".errors");
        final ObjectEncoder objectEncoder = new ObjectEncoder();

        final Bootstrap _bootstrap = partialBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // This mirror the protocol used in TransporterListener
                final ChannelPipeline p = ch.pipeline();
                p.addFirst(LOG_DEBUG_HANDLER, debugs);
                p.addLast(objectEncoder);
                p.addLast(new ObjectDecoder(cr));
                p.addLast("init", new TransportInitClient(handler, logger, ProviderDomain.this));
                p.addLast(LOG_ERRORS_HANDLER, errors);
            }
        });
        assert _bootstrap != null;
        bootstrap = _bootstrap;
    }

    public void reload(final JbiProviderDomain newJPD) {
        if (!jpd.getRemoteAuthName().equals(newJPD.getRemoteAuthName())
                || !jpd.getRemoteIp().equals(newJPD.getRemoteIp())
                || !jpd.getRemotePort().equals(newJPD.getRemotePort())
                || !jpd.getCertificate().equals(newJPD.getCertificate())
                || !jpd.getRemoteCertificate().equals(newJPD.getRemoteCertificate())
                || !jpd.getKey().equals(newJPD.getKey()) || !jpd.getPassphrase().equals(newJPD.getPassphrase())) {
            jpd = newJPD;
            disconnect();
            connect();
        }
    }

    /**
     * Register propagated consumes for the JBI listener, can be called after or before the component has started (i.e.,
     * {@link #connect()} has been called).
     */
    public void register() throws PEtALSCDKException {
        servicesLock.lock();
        try {
            // TODO log warning if there is a provides in the SU that is not propagated
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
            servicesLock.unlock();
        }
    }

    /**
     * Deregister the propagated consumes for the JBI Listener
     */
    public void deregister() throws PEtALSCDKException {

        final List<Exception> exceptions = new ArrayList<>();

        servicesLock.lock();
        try {
            init = false;

            for (final ServiceData data : services.values()) {
                assert data != null;
                deregisterOrStoreOrLog(data, exceptions);
            }
        } finally {
            servicesLock.unlock();
        }

        if (!exceptions.isEmpty()) {
            final PEtALSCDKException ex = new PEtALSCDKException("Errors during ProviderDomain shutdown");
            for (final Exception e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    public void updatePropagatedServices(final TransportedPropagatedConsumes propagatedServices) {
        updatePropagatedServices(propagatedServices.getConsumes());
    }

    /**
     * 
     * This registers and initialises the consumes being declared in the provider domain that we mirror on this side.
     * 
     * We receive this notification once we are connected to the other side, i.e., just after component start (and of
     * course after SU deploy)
     * 
     * It can be executed after or before {@link #register()} has been called.
     * 
     * In case of reconnection, it can be called again or if there is an update from the other side.
     */
    private void updatePropagatedServices(final Map<ServiceKey, TransportedDocument> propagated) {
        servicesLock.lock();
        try {

            final Set<ServiceKey> oldKeys = new HashSet<>(services.keySet());

            for (final Entry<ServiceKey, TransportedDocument> entry : propagated.entrySet()) {
                final ServiceKey service = entry.getKey();
                assert service != null;
                final Document document = entry.getValue() != null ? entry.getValue().getDocument() : null;

                // let's skip those we are not concerned with
                if (!jpd.isPropagateAll() && !provides.containsKey(service)) {
                    continue;
                }

                final boolean register;
                final ServiceData data;

                if (oldKeys.remove(service)) {
                    // we already knew this service from a previous event
                    data = services.get(service);
                    assert data != null;
                    if (document != null && data.description == null) {
                        data.description = document;
                        // let's re-register it then!
                        deregisterOrStoreOrLog(data, null);
                        register = true;
                    } else {
                        register = false;
                    }
                } else {
                    // the service is new!
                    data = new ServiceData(document);
                    register = true;
                }

                if (register) {
                    try {
                        if (init) {
                            registerProviderService(service, data);
                        }

                        // we add it after we are sure no error happened with the registration
                        services.put(service, data);
                    } catch (final PEtALSCDKException e) {
                        logger.log(Level.WARNING,
                                "Couldn't register propagated service '" + service + "' (" + data.key + ")",
                                e);
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
            servicesLock.unlock();
        }
    }

    private void registerProviderService(final ServiceKey sk, final ServiceData data) throws PEtALSCDKException {

        final ProviderService ps = new ProviderService() {
            @Override
            public void sendToChannel(final Exchange exchange) {
                ProviderDomain.this.sendToChannel(sk, exchange);
            }
        };

        // the service key was built from the provides config: it must match exactly for rewriting work
        final Provides p = provides.get(sk);
        if (p != null) {
            final ServiceEndpointKey key = new ServiceEndpointKey(p);
            data.key = key;
            // the description is managed by the ServiceUnitManager, the component will retrieve it
            // see also the constructor that verify that the description is present
            matcher.register(key, ps);
        } else {
            final Document description;
            final ServiceEndpointKey key = generateSEK(sk);
            data.key = key;
            if (data.description == null) {
                // let's generate a minimal one for now
                // but we won't store it, in case we get one from the other side later
                try {
                    description = WSDLUtilImpl.convertDescriptionToDocument(WSDLUtilImpl.createLightWSDL20Description(
                            sk.interfaceName, key.getServiceName(), key.getEndpointName()));
                } catch (final WSDLException e) {
                    throw new PEtALSCDKException(e);
                }
            } else {
                description = data.description;
            }
            assert description != null;
            matcher.register(key, ps, description);
        }
    }

    private void deregisterOrStoreOrLog(final ServiceData data, final @Nullable Collection<Exception> exceptions) {
        final ServiceEndpointKey key = data.key;
        if (key != null) {
            try {
                data.key = null;
                if (!matcher.deregister(key)) {
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
    private void sendToChannel(final ServiceKey service, final Exchange exchange) {
        final MessageExchange mex = exchange.getMessageExchange();
        assert mex != null;

        // current provide step
        final FlowAttributes provideStep = PetalsExecutionContext.getFlowAttributes();
        // step for the external call
        final FlowAttributes provideExtStep = PetalsExecutionContext.nextFlowStepId();

        // we cheat a bit and come back to the previous one for the following
        // and will switch to the ext one just before sending over the channel
        PetalsExecutionContext.putFlowAttributes(provideStep);

        assert provideExtStep != null;
        final TransportedMessage m = TransportedMessage.newMessage(service, provideExtStep, mex);

        final Channel _channel = channel;
        // channel can't be null because it would mean that the component is stopped and in that case we
        // wouldn't be receiving messages!
        assert _channel != null;
        // let's use the context of the client
        final ChannelHandlerContext ctx = _channel.pipeline().context(TransportClient.class);
        assert ctx != null;
        sendToChannel(ctx, m, exchange);
    }

    /**
     * Connect to the provider partner
     */
    public void connect() {
        // it should have been checked already by JbiGatewayJBIHelper
        final int port = Integer.parseInt(jpd.getRemotePort());

        bootstrap.remoteAddress(jpd.getRemoteIp(), port).connect().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                assert future != null;
                if (!future.isSuccess()) {
                    // TODO propose another way to reconnect?
                    logger.log(Level.SEVERE, "Can't connect to provider domain " + jpd.getId()
                            + ": fix the problem and, either stop/start the component, "
                            + "undeploy/deploy the SU or fix/reload the placeholders if it applies",
                            future.cause());
                } else {
                    channel = future.channel();
                }
            }
        });
    }

    /**
     * Disconnect from the provider partner
     */
    public void disconnect() {
        final Channel _channel = channel;
        channel = null;
        if (_channel != null && _channel.isOpen()) {
            // Note: this should trigger a call to close normally!
            _channel.close().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                    assert future != null;
                    if (!future.isSuccess()) {
                        logger.log(Level.WARNING,
                                "Error while disconnecting from provider domain " + jpd.getId() + ": nothing to do",
                                future.cause());
                    }
                }
            });
        }
    }

    public JbiProviderDomain getJPD() {
        return jpd;
    }

    public void close() {
        // this is like a disconnect... but emanating from the other side
        updatePropagatedServices(TransportedPropagatedConsumes.EMPTY);
    }

    @Override
    protected void logAfterReceivingFromChannel(final TransportedMessage m) {
        if (m.step == 2) {
            // the message contains the FA we created before sending it as a TransportedNewMessage in send

            // this is the end of provides ext that started in ProviderDomain.send
            StepLogHelper.addMonitExtEndOrFailureTrace(logger, m.exchange, m.provideExtStep, false);
        }

        // TODO for now, when the exchange is received from the channel, we set the flow attributes in the context to
        // the one of the provide and not the one of the provide ext, but the TRACE is done using the provide ext flow
        // attribute: this is ok because only the instance is really important when logging, but who knows if in the
        // future things won't be different?!

    }

    @Override
    protected void logBeforeSendingToChannel(final TransportedMessage m) {

        final FlowAttributes provideStep = PetalsExecutionContext.getFlowAttributes();

        // this is the step of the provide ext
        PetalsExecutionContext.putFlowAttributes(m.provideExtStep);

        if (m.step == 1) {
            logger.log(Level.MONIT, "",
                    new JbiGatewayProvideExtFlowStepBeginLogData(m.provideExtStep, provideStep, jpd.getId()));
        }
    }
}
