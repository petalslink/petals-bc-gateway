/**
 * Copyright (c) 2015-2026 Linagora
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
 * along with this program/library; If not, see http://www.gnu.org/licenses/
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

import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.BcGatewayComponent;
import org.ow2.petals.bc.gateway.BcGatewayJBISender;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.commons.AbstractDomain;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.bc.gateway.commons.messages.TransportedDocument;
import org.ow2.petals.bc.gateway.commons.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.commons.messages.TransportedPropagations;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.utils.BcGatewayJbiHelper.Pair;
import org.ow2.petals.bc.gateway.utils.BcGatewayProvideExtFlowStepBeginLogData;
import org.ow2.petals.bc.gateway.utils.BcGatewayServiceEndpointHelper;
import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.logger.AbstractFlowLogData;
import org.ow2.petals.component.framework.logger.StepLogHelper;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
import org.ow2.petals.component.framework.util.EndpointUtil;
import org.ow2.petals.component.framework.util.ServiceEndpointKey;
import org.w3c.dom.Document;

import com.ebmwebsourcing.easycommons.lang.StringHelper;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.serialization.ClassResolver;

/**
 * There is one instance of this class per opened connection to a provider partner.
 * 
 * It maintains the list of Provides we should create on our side (based on the Consumes propagated)
 *
 * {@link #connect(boolean)} and {@link #disconnect()} corresponds to components start and stop. {@link #connect(boolean)} should
 * trigger {@link #updatePropagatedServices(TransportedPropagations)} by the {@link Channel} normally.
 * 
 * {@link #register()} and {@link #deregister()} corresponds to SU init and shutdown.
 * 
 */
public class ProviderDomain extends AbstractDomain {

    /**
     * For now we only accept ONE provides per propagated service, but there is no reason to do so, we could have many
     * provides for a given matching service (as long as each provides matches only one service of course!).
     * 
     * TODO support multi provides per service (it's complicated because then we have to rething {@link #services} that
     * won't match the received services anymore)
     * 
     * TODO support multiple activation of a provides (by changing its endpoint name thus) if multiple
     * {@link ServiceKey} matches.
     */
    private final Service2ProvidesMatcher service2provides;

    private final ProviderMatcher matcher;

    private final TransportClient client;

    /**
     * lock for manipulating the {@link #services}, {@link #jpd} and {@link #init},
     */
    private final Lock mainLock = new ReentrantLock(true);

    /**
     * Updated by {@link #updatePropagatedServices(TransportedPropagations)}.
     * 
     * Contains the services announced by the provider partner as being propagated.
     * 
     * The content of {@link ServiceData} itself is updated by {@link #register()} and {@link #deregister()} (to add the
     * {@link ServiceEndpointKey} that is activated as an endpoint)
     * 
     */
    private final Map<ServiceKey, ServiceData> services = new HashMap<>();

    private final AbstractComponent component;

    /**
     * Not final because it can be updated by {@link #reload(JbiProviderDomain)}.
     */
    private JbiProviderDomain jpd;

    private boolean init = false;

    private static class ServiceData {

        private @Nullable Document description;

        private @Nullable ServiceEndpointKey key;

        public ServiceData(final @Nullable Document description) {
            this.description = description;
        }
    }

    public ProviderDomain(final BcGatewayComponent component, final ServiceUnitDataHandler handler,
            final JbiProviderDomain jpd, final Collection<Pair<Provides, JbiProvidesConfig>> provides,
            final JBISender sender, final Bootstrap partialBootstrap, final Logger logger, final ClassResolver cr)
            throws PEtALSCDKException {
        super(sender, handler, logger);

        this.matcher = component;
        this.component = component;
        this.jpd = jpd;
        this.service2provides = new Service2ProvidesMatcher(provides);
        this.client = new TransportClient(handler, partialBootstrap, logger, cr, this);
    }

    @Override
    public String getId() {
        return jpd.getId();
    }

    public void reload(final JbiProviderDomain newJPD) {
        mainLock.lock();
        try {
            if (!jpd.getRemoteAuthName().equals(newJPD.getRemoteAuthName())
                    || !jpd.getRemoteIp().equals(newJPD.getRemoteIp())
                    || !jpd.getRemotePort().equals(newJPD.getRemotePort())
                    || !StringHelper.equal(jpd.getCertificate(), newJPD.getCertificate())
                    || !StringHelper.equal(jpd.getRemoteCertificate(), newJPD.getRemoteCertificate())
                    || !StringHelper.equal(jpd.getKey(), newJPD.getKey())
                    || !jpd.getPassphrase().equals(newJPD.getPassphrase())) {
                jpd = newJPD;
                connect(true);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Register propagated consumes for the JBI listener, can be called after or before the component has started (i.e.,
     * {@link #connect(boolean)} has been called).
     */
    public void register() throws PEtALSCDKException {
        mainLock.lock();
        try {
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
            mainLock.unlock();
        }
    }

    /**
     * Deregister the propagated consumes for the JBI Listener
     */
    public void deregister() throws PEtALSCDKException {

        final List<Exception> exceptions = new ArrayList<>();

        mainLock.lock();
        try {
            init = false;

            for (final ServiceData data : services.values()) {
                assert data != null;
                deregisterOrStoreOrLog(data, exceptions);
            }
        } finally {
            mainLock.unlock();
        }

        if (!exceptions.isEmpty()) {
            final PEtALSCDKException ex = new PEtALSCDKException("Errors during ProviderDomain shutdown");
            for (final Exception e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    public void updatePropagatedServices(final TransportedPropagations propagatedServices) {
        updatePropagatedServices(propagatedServices.getPropagations());
    }

    /**
     * 
     * This registers and initializes the consumes being declared in the provider domain that we mirror on this side.
     * 
     * We receive this notification once we are connected to the other side, i.e., just after component start (and of
     * course after SU deploy)
     * 
     * It can be executed after or before {@link #register()} has been called.
     * 
     * In case of reconnection, it can be called again or if there is an update from the other side.
     */
    private void updatePropagatedServices(final Map<ServiceKey, TransportedDocument> propagated) {
        mainLock.lock();
        try {
            final Set<ServiceKey> oldKeys = new HashSet<>(services.keySet());

            // TODO handle exceptions and log them?
            for (final Entry<ServiceKey, TransportedDocument> entry : propagated.entrySet()) {
                final ServiceKey service = entry.getKey();
                assert service != null;
                final Document document = entry.getValue() != null ? entry.getValue().getDocument() : null;

                // let's skip those we are not concerned with
                if (!jpd.isPropagateAll() && service2provides.getProvides(service) == null) {
                    continue;
                }

                final boolean register;
                final ServiceData data;

                if (oldKeys.remove(service)) {
                    // we already knew this service from a previous event
                    data = services.get(service);
                    assert data != null;
                    if (document != null && data.description == null) {
                        final Provides p = service2provides.getProvides(service);
                        if (p != null && p.getWsdl() != null) {
                            // in this case we deregister and re-register it with the right document
                            data.description = document;
                            deregisterOrStoreOrLog(data, null);
                            register = true;
                        } else {
                            // in this case, we anyway use the provides description
                            register = false;
                        }
                    } else {
                        // in this case we don't touch it
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
            mainLock.unlock();
        }
    }

    private void registerProviderService(final ServiceKey sk, final ServiceData data) throws PEtALSCDKException {

        final ProviderService provider = new ProviderService() {
            @Override
            public void sendToChannel(final Exchange exchange) {
                ProviderDomain.this.sendToChannel(sk, exchange);
            }
        };

        final Provides p = service2provides.getProvides(sk);

        final ServiceEndpointKey key;
        final QName interfaceName;
        final boolean generateDescription;
        if (p != null) {
            key = new ServiceEndpointKey(p);
            interfaceName = p.getInterfaceName();
            if (p.getWsdl() == null) {
                generateDescription = true;
            } else {
                // we will use the description managed by the ServiceUnitManager, the component will retrieve it
                generateDescription = false;
            }
        } else {
            key = generateSEK(sk);
            generateDescription = true;
            interfaceName = sk.interfaceName;
        }
        assert interfaceName != null;

        data.key = key;

        if (generateDescription) {
            // note: data.description can be null!
            final Document description = BcGatewayServiceEndpointHelper.generateDescription(data.description, sk, key,
                    interfaceName, logger);
            matcher.register(key, provider, description);
        } else {
            matcher.register(key, provider);
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
        final ServiceEndpointKey key = new ServiceEndpointKey(sk.service, endpointName);
        return key;
    }

    /**
     * This is used to send to the channel for (1st step) exchanges arriving on JBI
     * 
     * 3rd is taken care of by {@link AbstractDomain}.
     */
    private void sendToChannel(final ServiceKey service, final Exchange exchange) {
        // let's use the context of the client
        final ChannelHandlerContext ctx = client.getDomainContext();
        // it can't be null because it would mean that the component is stopped and in that case we
        // wouldn't be receiving messages!
        assert ctx != null;

        sendFromNMRToChannel(ctx, service, null, exchange);
    }

    /**
     * Connect to the provider partner
     * 
     * @param force
     *            if <code>true</code>, then if we are already connected, we will first be disconnected
     */
    public void connect(final boolean force) {
        client.connect(force);
    }
    
    /**
     * Disconnect from the provider partner
     */
    public void disconnect() {
        client.disconnect();
    }
    
    public JbiProviderDomain getJPD() {
        return jpd;
    }

    public void close() {
        // this is like a disconnect... but emanating from the other side
        updatePropagatedServices(TransportedPropagations.EMPTY);
    }

    @Override
    protected String buildTimeoutErrorMessage(final TransportedMessage m, final JBISender jbiSender) {

        final FlowAttributes consumeExtStep = PetalsExecutionContext.getFlowAttributes();
        // it was set by the CDK
        assert consumeExtStep != null;

        final Provides currentProvides = this.service2provides.getProvides(m.service);
        final String interfaceName = currentProvides.getInterfaceName().toString();
        final String serviceName = currentProvides.getServiceName() == null
                ? StepLogHelper.TIMEOUT_ERROR_MSG_UNDEFINED_REF
                : currentProvides.getServiceName().toString();
        final String endpointName = m.exchange.getEndpoint().getEndpointName() == null
                ? StepLogHelper.TIMEOUT_ERROR_MSG_UNDEFINED_REF
                : m.exchange.getEndpoint().getEndpointName();
        final String operationName = m.exchange.getOperation() == null ? StepLogHelper.TIMEOUT_ERROR_MSG_UNDEFINED_REF
                : m.exchange.getOperation().toString();

        // jbiSender is transmit through AbstractDomain, and for a ConsumerDomain, it's an instnace of
        // BcGatewayJBISender
        assert jbiSender instanceof BcGatewayJBISender;
        final long timeout = ((BcGatewayJBISender) jbiSender).getTimeout(currentProvides);

        final FlowAttributes provideStep = PetalsExecutionContext.getFlowAttributes();

        return String.format(StepLogHelper.TIMEOUT_ERROR_MSG_PATTERN, timeout, interfaceName, serviceName, endpointName,
                operationName, provideStep.getFlowInstanceId(), provideStep.getFlowStepId());
    }

    @Override
    protected void logAfterReceivingFromChannel(final TransportedMessage m) {
        // can't happen in provider domain
        assert m.step > 1;

        PetalsExecutionContext.putFlowAttributes(m.provideExtStep);

        if (m.step == 2) {
            // this is the end of provides ext that started in ProviderDomain.send
            this.logMonitTrace(m,
                    StepLogHelper.getMonitExtEndOrFailureTrace(m.exchange, m.provideExtStep, false));
        }
    }

    @Override
    protected void logBeforeSendingToChannel(final TransportedMessage m) {
        if (m.step == 1) {
            // current provide step
            final FlowAttributes provideStep = PetalsExecutionContext.getFlowAttributes();
            // step for the external call
            final FlowAttributes provideExtStep = PetalsExecutionContext.nextFlowStepId();

            // it will be used by the ConsumerDomain.logAfterReceivingFromChannel
            m.provideExtStep = provideExtStep;

            this.logMonitTrace(m,
                    new BcGatewayProvideExtFlowStepBeginLogData(provideExtStep, provideStep, jpd.getId()));

            this.propagateFlowTracing(m);

        } else {
            PetalsExecutionContext.putFlowAttributes(m.provideExtStep);
        }
    }

    @Override
    protected boolean isFlowTracingActivationPropagated(final TransportedMessage m) {

        return this.component.isFlowTracingActivationPropagated();
    }

    @Override
    protected boolean isFlowTracingActivated(final TransportedMessage m) {
        assert m != null;

        if (m.initialExternalFlowTracingActivation == null) {
            return this.component.isFlowTracingActivated();
        } else {
            return m.initialExternalFlowTracingActivation;
        }
    }

    /**
     * <p>
     * Log a MONIT trace if needed, according to the parameters 'activate-flow-tracing' defined at message exchange
     * level, service unit level and component level.
     * </p>
     * 
     * @param m
     *            The message that will be received or sent from/to the channel. Not {@code null}.
     * @param provides
     *            The service unit provider for which the flow tracing activation will be checked if needed. Can be
     *            {@code null}.
     * @param monitTrace
     *            The MONIT trace to log if needed.
     * 
     */
    private void logMonitTrace(final TransportedMessage m, final AbstractFlowLogData monitTrace) {
        assert m != null;

        if (this.isFlowTracingActivated(m)) {
            this.component.getLogger().log(Level.MONIT, "", monitTrace);
        }
    }

    private void propagateFlowTracing(final TransportedMessage m) {
        assert m != null;

        if (this.component.isFlowTracingActivationPropagated()) {
            m.externalFlowTracingActivation = this.isFlowTracingActivated(m);
        } else {
            m.externalFlowTracingActivation = null;
        }
    }
}
