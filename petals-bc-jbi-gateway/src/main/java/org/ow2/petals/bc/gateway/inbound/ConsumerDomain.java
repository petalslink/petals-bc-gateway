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
package org.ow2.petals.bc.gateway.inbound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

import javax.jbi.JBIException;
import javax.jbi.component.ComponentContext;
import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.AbstractDomain;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedForService;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.messages.TransportedPropagatedConsumes;
import org.ow2.petals.bc.gateway.messages.TransportedPropagatedConsumesList;
import org.ow2.petals.bc.gateway.utils.JbiGatewayConsumeExtFlowStepBeginLogData;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.logger.StepLogHelper;
import org.w3c.dom.Document;

import io.netty.channel.Channel;

/**
 * There is one instance of this class per consumer domain in an SU configuration (jbi.xml).
 * 
 * It is responsible of notifying the channels (to consumer partner) of existing Consumes propagated to them.
 * 
 * The main idea is that a given consumer partner can contact us (a provider partner) with multiple connections (for
 * example in case of HA) and each of these needs to know what are the consumes propagated to them.
 * 
 * @author vnoel
 *
 */
public class ConsumerDomain extends AbstractDomain {

    /**
     * The keys of the {@link Consumes} propagated to this consumer domain.
     */
    private final Map<ServiceKey, Consumes> services = new HashMap<>();

    /**
     * The channels from this consumer domain (there can be more than one in case of HA or stuffs like that for example)
     */
    @SuppressWarnings("null")
    private final Set<Channel> channels = Collections.newSetFromMap(new ConcurrentHashMap<Channel, Boolean>());

    private final ComponentContext cc;

    private JbiConsumerDomain jcd;

    private final TransportListener tl;

    private volatile boolean open = false;

    private final ReadWriteLock channelsLock = new ReentrantReadWriteLock();

    public ConsumerDomain(final TransportListener tl, final ComponentContext cc, final JbiConsumerDomain jcd,
            final Collection<Consumes> consumes,
            final JBISender sender, final Logger logger) {
        super(sender, logger);
        this.tl = tl;
        this.cc = cc;
        this.jcd = jcd;
        for (final Consumes c : consumes) {
            assert c != null;
            services.put(new ServiceKey(c), c);
        }
    }

    public void onPlaceHolderValuesReloaded(final JbiConsumerDomain newJCD) throws PEtALSCDKException {
        if (!jcd.getAuthName().equals(newJCD.getAuthName())) {
            tl.register(newJCD.getAuthName(), this);
            tl.deregistrer(jcd.getAuthName());
            jcd = newJCD;
            // this will disconnect clients and they should reconnect by themselves
            disconnect();
        }
    }

    public JbiConsumerDomain getJCD() {
        return jcd;
    }

    /**
     * Consumer partner will be able to connect to us
     */
    public void register() throws PEtALSCDKException {
        tl.register(jcd.getAuthName(), this);
    }

    /**
     * No new connection can be created by the consumer partner
     */
    public void deregister() {
        tl.deregistrer(jcd.getAuthName());
    }

    /**
     * Consumer partner will be disconnected
     */
    public void disconnect() {
        channelsLock.readLock().lock();
        try {
            for (final Channel c : channels) {
                // this will trigger deregisterChannel btw
                c.close();
            }
        } finally {
            channelsLock.readLock().unlock();
        }
    }

    public void open() {
        // note: open and close are never called concurrently so read lock is ok
        channelsLock.readLock().lock();
        try {
            open = true;
            for (final Channel c : channels) {
                assert c != null;
                sendPropagatedServices(c);
            }
        } finally {
            channelsLock.readLock().unlock();
        }
    }

    public void close() {
        // note: open and close are never called concurrently so read lock is ok
        channelsLock.readLock().lock();
        try {
            open = false;
            
            for (final Channel c : channels) {
                c.writeAndFlush(
                        new TransportedPropagatedConsumesList(new ArrayList<TransportedPropagatedConsumes>()));
            }
        } finally {
            channelsLock.readLock().unlock();
        }
    }

    public void registerChannel(final Channel c) {
        channelsLock.writeLock().lock();
        try {
            channels.add(c);
            if (open) {
                sendPropagatedServices(c);
            }
        } finally {
            channelsLock.writeLock().unlock();
        }
    }

    public void deregisterChannel(final Channel c) {
        channelsLock.writeLock().lock();
        try {
            channels.remove(c);
        } finally {
            channelsLock.writeLock().unlock();
        }
    }

    public void refreshPropagations() {
        if (open) {
            open();
        }
    }

    private void sendPropagatedServices(final Channel c) {
        final List<TransportedPropagatedConsumes> consumes = new ArrayList<>();
        for (final Entry<ServiceKey, Consumes> entry : services.entrySet()) {
            final ServiceEndpoint[] endpoints = getEndpoints(entry.getValue());
            // only add the consumes if there is an activated endpoint for it!
            // TODO poll for newly added endpoints, removed ones and updated descriptions (who knows if the endpoint has
            // been deactivated then reactivated with an updated description!)
            if (endpoints.length > 0) {
                final Document description = getFirstDescription(endpoints);
                consumes.add(new TransportedPropagatedConsumes(entry.getKey(), description));
            }
        }
        c.writeAndFlush(new TransportedPropagatedConsumesList(consumes));
    }

    /**
     * This will return the first {@link Document} that is non-null on a {@link ServiceEndpoint} that matches the
     * {@link Consumes}.
     * 
     * TODO maybe factor that into CDK?
     */
    private ServiceEndpoint[] getEndpoints(final Consumes consumes) {
        final String endpointName = consumes.getEndpointName();
        final QName serviceName = consumes.getServiceName();
        final QName interfaceName = consumes.getInterfaceName();
        final ServiceEndpoint[] endpoints;
        if (endpointName != null && serviceName != null) {
            final ServiceEndpoint endpoint = cc.getEndpoint(serviceName, endpointName);
            if (endpoint != null) {
                if (interfaceName == null || matches(endpoint, interfaceName)) {
                    endpoints = new ServiceEndpoint[] { endpoint };
                } else {
                    logger.warning(String.format(
                            "Endpoint found for Consumes %s/%s/%s but interface does not match (was %s)", endpointName,
                            serviceName, interfaceName, Arrays.deepToString(endpoint.getInterfaces())));
                    endpoints = new ServiceEndpoint[0];
                }
            } else {
                logger.warning(String.format("No endpoint found for Consumes %s/%s/%s ", endpointName, serviceName,
                        interfaceName));
                endpoints = new ServiceEndpoint[0];
            }
        } else if (serviceName != null) {
            final ServiceEndpoint[] preMatch = cc.getEndpointsForService(serviceName);
            if (interfaceName != null) {
                final List<ServiceEndpoint> matched = new ArrayList<>();
                for (final ServiceEndpoint endpoint : preMatch) {
                    assert endpoint != null;
                    if (matches(endpoint, interfaceName)) {
                        matched.add(endpoint);
                    } else {
                        logger.warning(
                                String.format("Endpoint found for Consumes %s/%s but interface does not match (was %s)",
                                        serviceName, interfaceName, Arrays.deepToString(endpoint.getInterfaces())));
                    }
                }
                endpoints = matched.toArray(new ServiceEndpoint[matched.size()]);
            } else {
                endpoints = preMatch;
            }
        } else {
            endpoints = cc.getEndpoints(interfaceName);
        }

        return endpoints;
    }

    private @Nullable Document getFirstDescription(final ServiceEndpoint[] endpoints) {
        for (final ServiceEndpoint endpoint : endpoints) {
            try {
                Document desc = cc.getEndpointDescriptor(endpoint);
                if (desc != null) {
                    return desc;
                }
            } catch (final JBIException e) {
                logger.log(Level.WARNING, "Failed to retrieve endpoint descriptor of " + endpoint, e);
            }
        }

        return null;
    }

    private static boolean matches(final ServiceEndpoint endpoint, final QName interfaceName) {
        for (final QName itf : endpoint.getInterfaces()) {
            if (interfaceName.equals(itf)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void logAfterReceivingFromChannel(final TransportedForService m) {
        // let's get the flow attribute from the received exchange and put them in context as soon as we get it
        // TODO add tests!
        PetalsExecutionContext.putFlowAttributes(m.current);

        // acting as a provider partner, a new consumes ext starts here
        if (m instanceof TransportedMessage && m.step == 1) {
            // Note: the previous step is the provide step of the consumer, and the current step
            // is the SAME one as the provide ext step of the consumer!
            // TODO do we want to do something else?!
            logger.log(Level.MONIT, "",
                    new JbiGatewayConsumeExtFlowStepBeginLogData(m.current, m.previous.getFlowStepId(), jcd.getId()));
        }
    }

    @Override
    protected void logBeforeSendingToChannel(final TransportedForService m) {
        // the end of the one started in ConsumerDomain.logBeforeSendingToNMR
        if (m instanceof TransportedMessage && m.step == 2) {
            StepLogHelper.addMonitExtEndOrFailureTrace(logger, ((TransportedMessage) m).exchange, m.current, true);
        }
    }
}
