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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

import javax.jbi.JBIException;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.JbiGatewaySUManager;
import org.ow2.petals.bc.gateway.commons.AbstractDomain;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.bc.gateway.commons.messages.TransportedDocument;
import org.ow2.petals.bc.gateway.commons.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.commons.messages.TransportedPropagatedConsumes;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.utils.JbiGatewayConsumeExtFlowStepBeginLogData;
import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.logger.StepLogHelper;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
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

    private final JbiGatewaySUManager sum;

    private JbiConsumerDomain jcd;

    private final TransportListener tl;

    private volatile boolean open = false;

    private final ReadWriteLock channelsLock = new ReentrantReadWriteLock();

    public ConsumerDomain(final ServiceUnitDataHandler handler, final TransportListener tl,
            final JbiGatewaySUManager sum, final JbiConsumerDomain jcd, final Collection<Consumes> consumes,
            final JBISender sender, final Logger logger) throws PEtALSCDKException {
        super(sender, handler, logger);
        this.tl = tl;
        this.sum = sum;
        this.jcd = jcd;
        for (final Consumes c : consumes) {
            assert c != null;
            services.put(new ServiceKey(c), c);
        }
        // Consumer partner will be able to connect to us (but no consumes will be propagated until open() is called)
        tl.register(jcd.getAuthName(), this);
    }

    @Override
    public String getId() {
        final String id = jcd.getId();
        assert id != null;
        return id;
    }

    public void reload(final JbiConsumerDomain newJCD) throws PEtALSCDKException {
        if (!jcd.getAuthName().equals(newJCD.getAuthName()) || !jcd.getCertificate().equals(newJCD.getCertificate())
                || !jcd.getRemoteCertificate().equals(newJCD.getRemoteCertificate())
                || !jcd.getKey().equals(newJCD.getKey()) || !jcd.getPassphrase().equals(newJCD.getPassphrase())) {
            if (!jcd.getAuthName().equals(newJCD.getAuthName())) {
                tl.register(newJCD.getAuthName(), this);
                tl.deregistrer(jcd.getAuthName());
            }
            jcd = newJCD;
            // this will disconnect clients and they should reconnect by themselves and use the new jcd and authname!
            disconnect();
        }
    }

    public JbiConsumerDomain getJCD() {
        return jcd;
    }

    /**
     * Consumer partner will be disconnected
     */
    public void disconnect() {
        channelsLock.readLock().lock();
        try {
            tl.deregistrer(jcd.getAuthName());
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
                c.writeAndFlush(TransportedPropagatedConsumes.EMPTY);
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
            logger.info("Refreshing propagations");
            open();
        }
    }

    private void sendPropagatedServices(final Channel c) {
        final Map<ServiceKey, TransportedDocument> consumes = new HashMap<>();
        for (final Entry<ServiceKey, Consumes> entry : services.entrySet()) {
            final Collection<ServiceEndpoint> endpoints = sum.getEndpointsForConsumes(entry.getValue());
            // only add the consumes if there is an activated endpoint for it!
            // TODO poll for newly added endpoints, removed ones and updated descriptions (who knows if the endpoint has
            // been deactivated then reactivated with an updated description!)
            if (!endpoints.isEmpty()) {
                final TransportedDocument description = getFirstDescription(endpoints);
                consumes.put(entry.getKey(), description);
            }
        }
        c.writeAndFlush(new TransportedPropagatedConsumes(consumes));
    }

    private @Nullable TransportedDocument getFirstDescription(final Collection<ServiceEndpoint> endpoints) {
        for (final ServiceEndpoint endpoint : endpoints) {
            try {
                Document desc = sum.getComponent().getContext().getEndpointDescriptor(endpoint);
                if (desc != null) {
                    return new TransportedDocument(desc);
                }
            } catch (final JBIException e) {
                logger.log(Level.WARNING, "Failed to retrieve endpoint descriptor of " + endpoint, e);
            }
        }

        return null;
    }

    @Override
    protected void logAfterReceivingFromChannel(final TransportedMessage m) {
        // acting as a provider partner, a new consumes ext starts here
        if (m.step == 1) {
            // we remember the step of the consumer partner through the correlated flow attributes
            logger.log(Level.MONIT, "",
                    new JbiGatewayConsumeExtFlowStepBeginLogData(PetalsExecutionContext.getFlowAttributes(),
                            m.provideExtStep, getId()));
        }
    }

    @Override
    protected void logBeforeSendingToChannel(final TransportedMessage m) {
        // the end of the one started in ConsumerDomain.logBeforeSendingToNMR
        if (m.step == 2) {
            final FlowAttributes consumeExtStep = PetalsExecutionContext.getFlowAttributes();
            // it was set by the CDK
            assert consumeExtStep != null;
            StepLogHelper.addMonitExtEndOrFailureTrace(logger, m.exchange, consumeExtStep, true);
        }
    }
}
