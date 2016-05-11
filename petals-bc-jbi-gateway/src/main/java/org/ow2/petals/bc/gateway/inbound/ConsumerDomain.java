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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * There is one instance of this class per consumer domain in an SU configuration (jbi.xml).
 * 
 * It is responsible of notifying the channels (to consumer partner) of existing Consumes propagated to them.
 * 
 * The main idea is that a given consumer partner can contact us (a provider partner) with multiple connections (for
 * example in case of HA) and each of these needs to know what are the consumes propagated to them.
 * 
 * TODO should I propagate one servicekey per Service? because for a given interface, there could be endpoints with
 * different services!
 * 
 * @author vnoel
 *
 */
public class ConsumerDomain extends AbstractDomain {

    /**
     * The keys of the {@link Consumes} propagated to this consumer domain.
     */
    private final Map<ServiceKey, Consumes> services = new HashMap<>();

    private final JbiGatewaySUManager sum;

    private final TransportListener tl;

    /**
     * Lock for synchronising changes to {@link #channels}, {@link #open}, {@link #propagations} and {@link #jcd}.
     */
    private final Lock lock = new ReentrantLock();

    /**
     * The channels from this consumer domain (there can be more than one in case of HA or stuffs like that for example)
     */
    private final Set<Channel> channels = new HashSet<>();

    private JbiConsumerDomain jcd;

    private volatile boolean open = false;

    private TransportedPropagatedConsumes propagations = TransportedPropagatedConsumes.EMPTY;

    private @Nullable ScheduledFuture<?> polling = null;

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
        lock.lock();
        try {
            if (!jcd.getAuthName().equals(newJCD.getAuthName()) || !jcd.getCertificate().equals(newJCD.getCertificate())
                    || !jcd.getRemoteCertificate().equals(newJCD.getRemoteCertificate())
                    || !jcd.getKey().equals(newJCD.getKey()) || !jcd.getPassphrase().equals(newJCD.getPassphrase())) {
                if (!jcd.getAuthName().equals(newJCD.getAuthName())) {
                    tl.register(newJCD.getAuthName(), this);
                    tl.deregistrer(jcd.getAuthName());
                }
                jcd = newJCD;
                // this will disconnect clients and they should reconnect by themselves and use the new jcd and
                // authname!
                disconnect();
            }
        } finally {
            lock.unlock();
        }
    }

    public JbiConsumerDomain getJCD() {
        return jcd;
    }

    /**
     * Consumer partner will be disconnected
     */
    public void disconnect() {
        lock.lock();
        try {
            tl.deregistrer(jcd.getAuthName());
            for (final Channel c : channels) {
                // this will trigger deregisterChannel btw
                // Note: locking is ok, simply all the close will be sent before the deregistration is executed
                c.close();
            }
        } finally {
            lock.unlock();
        }
    }

    public void open() {
        lock.lock();
        try {
            open = true;
            sendPropagations(true);
            final long propagationPollingDelay = jcd.getPropagationPollingDelay();
            if (propagationPollingDelay > 0) {
                if (logger.isLoggable(Level.CONFIG)) {
                    logger.config("Propagation refresh polling is enabled (every " + propagationPollingDelay + "ms)");
                }
                polling = GlobalEventExecutor.INSTANCE.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        if (logger.isLoggable(Level.FINE)) {
                            logger.fine("Propagation refresh polling (next in " + propagationPollingDelay + "ms)");
                        }
                        if (sendPropagations(false)) {
                            logger.info("Changes in propagations detected: refreshed!");
                        }
                    }
                }, propagationPollingDelay, propagationPollingDelay, TimeUnit.MILLISECONDS);
            } else {
                logger.config("Propagation refresh polling is disabled");
            }
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            open = false;
            if (polling != null) {
                // interruption will stop any current sending!
                polling.cancel(true);
                polling = null;
            }
            sendPropagations(TransportedPropagatedConsumes.EMPTY);
        } finally {
            lock.unlock();
        }
    }

    public void registerChannel(final Channel c) {
        lock.lock();
        try {
            channels.add(c);

            if (open) {
                if (!sendPropagations(false)) {
                    // let's simply notify the new channel with the already known propagations
                    c.writeAndFlush(propagations);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void deregisterChannel(final Channel c) {
        lock.lock();
        try {
            channels.remove(c);
        } finally {
            lock.unlock();
        }
    }

    public void refreshPropagations() {
        if (logger.isLoggable(Level.INFO)) {
            logger.info("Refreshing propagations");
        }
        sendPropagations(true);
    }

    /**
     * Note, this can be interrupted, for example if the polling is cancelled: in that case we simply returns
     * <code>false</code>.
     * 
     * Sends the propagation to all channels if the domain is open, and if force is <code>true</code> or if there is
     * some changes.
     * 
     * @return <code>false</code> if the propagations were sent.
     */
    private boolean sendPropagations(final boolean force) {
        lock.lock();
        try {
            if (!open) {
                return false;
            }
            boolean changes = false;
            final Map<ServiceKey, TransportedDocument> consumes = new HashMap<>();
            for (final Entry<ServiceKey, Consumes> entry : services.entrySet()) {
                final ServiceKey service = entry.getKey();
                final Collection<ServiceEndpoint> endpoints = sum.getEndpointsForConsumes(entry.getValue());

                if (Thread.interrupted()) {
                    return false;
                }

                final boolean shouldBePropagated = !endpoints.isEmpty();

                final TransportedDocument description;
                if (shouldBePropagated) {
                    description = getFirstDescription(endpoints);
                    if (Thread.interrupted()) {
                        return false;
                    }
                } else {
                    description = null;
                }

                if (!force) {
                    // careful, the map can contains null for some keys! so containsKey is not the same as get != null!
                    final boolean wasPropagated = propagations.getConsumes().containsKey(service);

                    if (shouldBePropagated != wasPropagated) {
                        // if they have different values, then it means something changed
                        changes = true;
                    } else if (shouldBePropagated && description != null
                            && propagations.getConsumes().get(service) == null) {
                        // if they have the same value, but the description changed from null to non-null,
                        // then it means something changed too!
                        changes = true;
                    }
                }

                if (shouldBePropagated) {
                    consumes.put(service, description);
                }

                if (Thread.interrupted()) {
                    return false;
                }
            }

            final boolean sendPropagations = force || changes;
            if (sendPropagations) {
                sendPropagations(new TransportedPropagatedConsumes(consumes));
            }

            return sendPropagations;
        } finally {
            lock.unlock();
        }
    }

    private void sendPropagations(final TransportedPropagatedConsumes toPropagate) {
        propagations = toPropagate;
        for (final Channel c : channels) {
            assert c != null;
            c.writeAndFlush(toPropagate);
        }
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
