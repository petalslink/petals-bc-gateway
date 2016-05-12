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

import java.net.URISyntaxException;
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
import org.ow2.easywsdl.extensions.wsdl4complexwsdl.WSDL4ComplexWsdlFactory;
import org.ow2.easywsdl.extensions.wsdl4complexwsdl.api.Description;
import org.ow2.easywsdl.extensions.wsdl4complexwsdl.api.WSDL4ComplexWsdlException;
import org.ow2.easywsdl.wsdl.api.Endpoint;
import org.ow2.easywsdl.wsdl.api.Service;
import org.ow2.easywsdl.wsdl.api.WSDLException;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.commons.AbstractDomain;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.bc.gateway.commons.messages.TransportedDocument;
import org.ow2.petals.bc.gateway.commons.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.commons.messages.TransportedPropagations;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.bc.gateway.utils.JbiGatewayProvideExtFlowStepBeginLogData;
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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.serialization.ClassResolver;

/**
 * There is one instance of this class per opened connection to a provider partner.
 * 
 * It maintains the list of Provides we should create on our side (based on the Consumes propagated)
 *
 * {@link #connect()} and {@link #disconnect()} corresponds to components start and stop. {@link #connect()} should
 * trigger {@link #updatePropagatedServices(TransportedPropagations)} by the {@link Channel} normally.
 * 
 * {@link #register()} and {@link #deregister()} corresponds to SU init and shutdown.
 * 
 */
public class ProviderDomain extends AbstractDomain {

    /**
     * immutable, all the provides for this domain.
     * 
     * See {@link #validate(Map)} for the conditions it holds.
     */
    private final Map<QName, Map<QName, Map<String, Provides>>> provides;

    private final ProviderMatcher matcher;

    private final TransportClient client;

    /**
     * lock for manipulating the {@link #services}, {@link #jpd} and {@link #init},
     */
    private final Lock mainLock = new ReentrantLock();

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

    public ProviderDomain(final ProviderMatcher matcher, final ServiceUnitDataHandler handler,
            final JbiProviderDomain jpd, final Collection<Pair<Provides, JbiProvidesConfig>> provides,
            final JBISender sender, final Bootstrap partialBootstrap, final Logger logger, final ClassResolver cr)
            throws PEtALSCDKException {
        super(sender, handler, logger);

        this.matcher = matcher;
        this.jpd = jpd;

        // TODO maybe move all of that to JbiGatewayJBIHelper?
        this.provides = new HashMap<>();
        for (final Pair<Provides, JbiProvidesConfig> pair : provides) {
            final Provides p = pair.getA();
            final JbiProvidesConfig config = pair.getB();

            if (StringHelper.isNullOrEmpty(p.getWsdl())) {
                // TODO maybe later we can do simple mirroring and rewriting w.r.t. the JbiProvidesConfig
                throw new PEtALSCDKException("The provides " + p.getServiceName() + " must have a WSDL defined");
            }

            addToProvides(p, config);
        }

        assert validate(this.provides);

        this.client = new TransportClient(handler, partialBootstrap, logger, cr, this);
    }

    private static boolean validate(final Map<QName, Map<QName, Map<String, Provides>>> provides) {

        // there can't be any interface
        if (provides.containsKey(null)) {
            return false;
        }

        for (final Map<QName, Map<String, Provides>> byServices : provides.values()) {
            final Map<String, Provides> anyServices = byServices.get(null);
            if (anyServices != null) {
                // normally for the any service key, there should only be at most the null key
                if (anyServices.size() > 1) {
                    return false;
                }
                if (anyServices.size() == 1 && !anyServices.containsKey(null)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * For now we only accept ONE provides per propagated service, but there is no reason to do so, we could have many
     * provides for a given matching service (as long as each provides matches only one service of course!).
     * 
     * TODO support multi provides per service (it's complicated because then we have to rething {@link #services} that
     * won't match the received services anymore)
     */
    private void addToProvides(final Provides p, final JbiProvidesConfig config) throws PEtALSCDKException {
        // can't be null
        final QName interfaceName = config.getProviderInterfaceName();
        Map<QName, Map<String, Provides>> byServices = provides.get(interfaceName);
        if (byServices == null) {
            byServices = new HashMap<>();
            provides.put(interfaceName, byServices);
        }

        // can be null
        final QName serviceName = config.getProviderServiceName();
        // null key is accepted in HashMap, it means any service name here!
        Map<String, Provides> byEndpoints = byServices.get(serviceName);
        if (byEndpoints == null) {
            byEndpoints = new HashMap<>();
            byServices.put(serviceName, byEndpoints);
        }

        // can be null
        final String endpointName = config.getProviderEndpointName();
        // null key is accepted in HashMap, it means any endpoint name here!
        if (byEndpoints.containsKey(endpointName)) {
            // TODO can be detect that earlier maybe in JbiGatewayJBIHelper
            throw new PEtALSCDKException(
                    String.format("Ambiguous provider configuration: duplicate matching service for %s/%s/%s",
                            interfaceName, serviceName, endpointName));
        } else {
            byEndpoints.put(endpointName, p);
        }
    }

    /**
     * TODO add tests related to that!
     */
    private @Nullable Provides getProvides(final ServiceKey key) {
        final Map<QName, Map<String, Provides>> byServices = this.provides.get(key.interfaceName);
        if (byServices != null) {
            final Map<String, Provides> byEndpoints = byServices.get(key.service);
            if (byEndpoints == null) {
                // if it doesn't match a specific provides, then maybe we have a generic one!
                // Note that there can't be any other key possible than null endpoint for a null service
                final Map<String, Provides> anyServices = byServices.get(null);
                if (anyServices != null) {
                    return anyServices.get(null);
                }
            } else {
                // note: key.service can't be null!
                return byEndpoints.get(key.endpointName);
            }
        }

        return null;
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
                    || !jpd.getCertificate().equals(newJPD.getCertificate())
                    || !jpd.getRemoteCertificate().equals(newJPD.getRemoteCertificate())
                    || !jpd.getKey().equals(newJPD.getKey()) || !jpd.getPassphrase().equals(newJPD.getPassphrase())) {
                jpd = newJPD;
                disconnect();
                connect();
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Register propagated consumes for the JBI listener, can be called after or before the component has started (i.e.,
     * {@link #connect()} has been called).
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
        mainLock.lock();
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

        final Provides p = getProvides(sk);

        if (p != null) {
            final ServiceEndpointKey key = new ServiceEndpointKey(p);
            data.key = key;
            // the description is managed by the ServiceUnitManager, the component will retrieve it
            // see also the Class constructor that verify that the description is present
            matcher.register(key, provider);
        } else {
            final ServiceEndpointKey key = generateSEK(sk);
            data.key = key;
            final Document description = generateDescription(data.description, sk, key);
            matcher.register(key, provider, description);
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

    /**
     * TODO prevent any throw exception?!
     */
    private Document generateDescription(final @Nullable Document originalDescription,
            final ServiceKey originalKey, final ServiceEndpointKey newKey) throws PEtALSCDKException {

        Description description = null;
        if (originalDescription != null) {
            // TODO reuse the reader, the instance, or whatever
            try {
                description = WSDL4ComplexWsdlFactory.newInstance().newWSDLReader().read(originalDescription);
            } catch (final WSDL4ComplexWsdlException | URISyntaxException e) {
                final String msg = "Couldn't read the received description for " + originalKey
                        + ", generating a lightweigth description";
                logger.warning(msg);
                logger.log(Level.FINE, msg, e);
            }

            if (description != null) {
                final Service service = description.getService(originalKey.service);

                if (service != null) {
                    final Endpoint endpoint;
                    if (originalKey.endpointName != null) {
                        endpoint = service.getEndpoint(originalKey.endpointName);
                    } else {
                        // TODO how do I know which endpoint is the right one? maybe the provider domain should send us
                        // this information on top of the rest?!

                        // for now let's take the first one!
                        endpoint = service.getEndpoints().isEmpty() ? null : service.getEndpoints().get(0);
                    }

                    // we always generate the endpoint name!
                    if (endpoint != null) {
                        endpoint.setName(newKey.getEndpointName());
                    } else {
                        logger.warning("Couldn't find the endpoint of " + originalKey
                                + " in the received description, generating a lightweigth description");
                        // TODO should I do that or just keep it...
                        description = null;
                    }
                } else {
                    logger.warning("Couldn't find the service of " + originalKey
                            + " in the received description, generating a lightweigth description");
                    // TODO should I do that or just keep it...
                    description = null;
                }
            }
        } else {
            logger.warning("No description received for " + originalKey + ", generating a lightweigth description");
        }

        if (description == null) {
            // let's generate a minimal one for now
            // but we won't store it, in case we get one from the other side later
            try {
            description = WSDLUtilImpl.createLightWSDL20Description(originalKey.interfaceName, newKey.getServiceName(),
                    newKey.getEndpointName());
            } catch (final WSDLException e) {
                throw new PEtALSCDKException(e);
            }
        }
        assert description != null;

        try {
            Document desc = WSDLUtilImpl.convertDescriptionToDocument(description);
            assert desc != null;
            return desc;
        } catch (final WSDLException e) {
            throw new PEtALSCDKException(e);
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

        // let's use the context of the client
        final ChannelHandlerContext ctx = client.getDomainContext();
        // it can't be null because it would mean that the component is stopped and in that case we
        // wouldn't be receiving messages!
        assert ctx != null;
        sendToChannel(ctx, m, exchange);
    }

    /**
     * Connect to the provider partner
     */
    public void connect() {
        client.connect();
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
