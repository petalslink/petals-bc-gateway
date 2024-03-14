/**
 * Copyright (c) 2015-2024 Linagora
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
package org.ow2.petals.bc.gateway.inbound;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import javax.jbi.JBIException;
import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.BcGatewayJBISender;
import org.ow2.petals.bc.gateway.BcGatewaySUManager;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.commons.AbstractDomain;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.bc.gateway.commons.messages.TransportedDocument;
import org.ow2.petals.bc.gateway.commons.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.commons.messages.TransportedPropagations;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.utils.BcGatewayConsumeExtFlowStepBeginLogData;
import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.logger.StepLogHelper;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
import org.w3c.dom.Document;

import com.ebmwebsourcing.easycommons.lang.StringHelper;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
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
 * @author vnoel
 *
 */
public class ConsumerDomain extends AbstractDomain {

    /**
     * The {@link Consumes} propagated to this consumer domain.
     */
    private final Collection<Consumes> consumes;

    private final BcGatewaySUManager sum;

    private final TransportListener tl;

    /**
     * Lock for synchronising changes to {@link #channels}, {@link #open}, {@link #propagations} and {@link #jcd}.
     */
    private final Lock mainLock = new ReentrantLock(true);

    /**
     * The channels from this consumer domain (there can be more than one in case of HA or stuffs like that for example)
     */
    private final Set<Channel> channels = new HashSet<>();

    private JbiConsumerDomain jcd;

    private volatile boolean open = false;

    private TransportedPropagations propagations = TransportedPropagations.EMPTY;

    /**
     * @see #polling
     */
    private final Lock pollingLock = new ReentrantLock();

    /**
     * Access is controlled by {@link #pollingLock} (except for the first poll that is controlled by {@link #mainLock} in
     * {@link #open()}).
     */
    private @Nullable ScheduledFuture<?> polling = null;

    public ConsumerDomain(final ServiceUnitDataHandler handler, final TransportListener tl,
            final BcGatewaySUManager sum, final JbiConsumerDomain jcd, final Collection<Consumes> consumes,
            final BcGatewayJBISender sender, final Logger logger) throws PEtALSCDKException {
        super(sender, handler, logger);
        this.tl = tl;
        this.sum = sum;
        this.jcd = jcd;
        this.consumes = consumes;
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
        mainLock.lock();
        try {
            if (!jcd.getAuthName().equals(newJCD.getAuthName())
                    || !StringHelper.equal(jcd.getCertificate(), newJCD.getCertificate())
                    || !StringHelper.equal(jcd.getRemoteCertificate(), newJCD.getRemoteCertificate())
                    || !StringHelper.equal(jcd.getKey(), newJCD.getKey())
                    || !StringHelper.equal(jcd.getPassphrase(), newJCD.getPassphrase())) {
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
            mainLock.unlock();
        }
    }

    public JbiConsumerDomain getJCD() {
        return jcd;
    }

    /**
     * Consumer partner will be disconnected
     */
    public void disconnect() {
        mainLock.lock();
        try {
            tl.deregistrer(jcd.getAuthName());
            for (final Channel c : channels) {
                // this will trigger deregisterChannel btw
                // Note: locking is ok, simply all the close will be sent before the deregistration is executed
                c.close();
            }
        } finally {
            mainLock.unlock();
        }
    }

    public void open() {
        mainLock.lock();
        try {
            open = true;

            sendPropagations(true);

            final long propagationPollingMaxDelay = jcd.getPropagationPollingMaxDelay();
            if (propagationPollingMaxDelay > 0) {
                final double propagationPollingAccel = jcd.getPropagationPollingAcceleration();
                if (logger.isLoggable(Level.CONFIG)) {
                    logger.config(
                            "Propagation refresh polling is enabled (max delay: " + propagationPollingMaxDelay
                                    + "ms, acceleration: " + propagationPollingAccel + ")");
                }
                scheduleNextPolling(5000, propagationPollingAccel, propagationPollingMaxDelay);
            } else {
                logger.config("Propagation refresh polling is disabled");
            }
        } finally {
            mainLock.unlock();
        }
    }

    private void scheduleNextPolling(final long currentDelay, final double accel, final long maxDelay) {
        final Runnable command = new Runnable() {
            @Override
            public void run() {
                final long nextDelay;
                if (accel > 1) {
                    nextDelay = Math.min((long) (currentDelay * accel), maxDelay);
                } else {
                    nextDelay = maxDelay;
                }

                try {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine("Propagation refresh polling (next in " + nextDelay + "ms)");
                    }

                    // TODO catch exceptions?!
                    if (sendPropagations(false)) {
                        logger.info("Changes in propagations detected: refreshed!");
                    }

                } finally {
                    try {
                        // in case it was interrupted during the propagation sending
                        // this will also reset the interrupted flag
                        pollingLock.lockInterruptibly();
                        try {
                            // polling corresponds to the current task
                            // if it's null, it was cancelled (thus the test for
                            // isCancelled is not really needed but well...)
                            if (polling != null && !polling.isCancelled()) {
                                scheduleNextPolling(nextDelay, accel, maxDelay);
                            }
                        } finally {
                            pollingLock.unlock();
                        }
                    } catch (final InterruptedException e) {
                        // we were interrupted, it's ok, we stop there
                    }
                }
            }
        };

        final long delay;
        if (accel > 1) {
            delay = Math.min(currentDelay, maxDelay);
        } else {
            delay = maxDelay;
        }

        polling = (ScheduledFuture<?>) GlobalEventExecutor.INSTANCE.schedule(command, delay, TimeUnit.MILLISECONDS)
                .addListener(new FutureListener<Object>() {
                    @Override
                    public void operationComplete(final @Nullable Future<Object> future) throws Exception {
                        assert future != null;
                        if (!future.isSuccess() && !future.isCancelled()) {
                            logger.log(Level.WARNING, "Error during propagation refresh polling", future.cause());
                        }
                    }
                });

    }

    public void close() {
        mainLock.lock();
        try {
            open = false;
            pollingLock.lock();
            try {
                if (polling != null) {
                    // interruption will stop any current sending
                    polling.cancel(true);
                    polling = null;
                }
            } finally {
                pollingLock.unlock();
            }
            sendPropagations(TransportedPropagations.EMPTY);
        } finally {
            mainLock.unlock();
        }
    }

    public void registerChannel(final Channel c) {
        mainLock.lock();
        try {
            channels.add(c);

            if (open) {
                if (!sendPropagations(false)) {
                    // let's simply notify the new channel with the already known propagations
                    c.writeAndFlush(propagations);
                }
            }
        } finally {
            mainLock.unlock();
        }
    }

    public void deregisterChannel(final Channel c) {
        mainLock.lock();
        try {
            channels.remove(c);
        } finally {
            mainLock.unlock();
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
     * <code>false</code> and the interrupt flag is kept as set to be handled by the caller.
     * 
     * Sends the propagation to all channels if the domain is open, and either if force is <code>true</code> or if there
     * is some changes.
     * 
     * @return <code>true</code> if the propagations were sent.
     */
    private boolean sendPropagations(final boolean force) {
        mainLock.lock();
        try {
            if (!open) {
                return false;
            }
            boolean changes = false;
            final Map<ServiceKey, TransportedDocument> propagated = new HashMap<>();
            for (final Consumes c : consumes) {
                assert c != null;
                final String endpointName = c.getEndpointName();
                final QName interfaceName = c.getInterfaceName();
                assert interfaceName != null;

                final Collection<ServiceEndpoint> allEndpoints = sum.getEndpointsForConsumes(c);
                assert allEndpoints != null;

                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }

                // there will be one propagation per interface/service, even if the consumes only declares an interface!
                for (final Entry<QName, Collection<ServiceEndpoint>> entry : splitPerService(allEndpoints).entrySet()) {
                    final QName serviceName = entry.getKey();
                    assert serviceName != null;
                    final Collection<ServiceEndpoint> endpoints = entry.getValue();

                    final TransportedDocument description = getFirstDescription(endpoints);

                    if (Thread.currentThread().isInterrupted()) {
                        return false;
                    }

                    final ServiceKey service = new ServiceKey(endpointName, serviceName, interfaceName);

                    if (!force) {
                        // careful, the map can contains null for some keys!
                        // so containsKey is not the same as get != null!
                        if (!propagations.getPropagations().containsKey(service)) {
                            // it means an endpoint was added/changed
                            changes = true;
                        } else if (description != null && propagations.getPropagations().get(service) == null) {
                            // if they have the same value, but the description changed from null to non-null,
                            // then it means something changed too!
                            changes = true;
                        }
                    }

                    propagated.put(service, description);

                    if (Thread.currentThread().isInterrupted()) {
                        return false;
                    }
                }
            }

            if (!force) {
                for (ServiceKey prevKey : propagations.getPropagations().keySet()) {
                    if (!propagated.containsKey(prevKey)) {
                        // it means endpoints were removed
                        changes = true;
                        break;
                    }
                }
            }

            final boolean sendPropagations = force || changes;
            if (sendPropagations) {
                sendPropagations(new TransportedPropagations(propagated));
            }

            return sendPropagations;
        } finally {
            mainLock.unlock();
        }
    }

    private static Map<QName, Collection<ServiceEndpoint>> splitPerService(
            final Collection<ServiceEndpoint> endpoints) {
        final Map<QName, Collection<ServiceEndpoint>> res = new HashMap<>();
        for (final ServiceEndpoint endpoint : endpoints) {
            final QName serviceName = endpoint.getServiceName();
            Collection<ServiceEndpoint> c = res.get(serviceName);
            if (c == null) {
                c = new LinkedList<>();
                res.put(serviceName, c);
            }
            c.add(endpoint);
        }

        assert validate(res);

        return res;
    }

    /**
     * each collection shouldn't be empty!
     */
    private static boolean validate(final Map<QName, Collection<ServiceEndpoint>> res) {

        for (final Collection<ServiceEndpoint> c : res.values()) {
            if (c.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private void sendPropagations(final TransportedPropagations toPropagate) {
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
    protected String buildTimeoutErrorMessage(final TransportedMessage m, final JBISender jbiSender) {

        final FlowAttributes consumeExtStep = PetalsExecutionContext.getFlowAttributes();
        // it was set by the CDK
        assert consumeExtStep != null;

        // jbiSender is transmit through AbstractDomain, and for a ConsumerDomain, it's an instnace of
        // BcGatewayJBISender
        assert jbiSender instanceof BcGatewayJBISender;
        final Consumes currentConsumes = this.retrieveConsumes(m);

        final String interfaceName = currentConsumes.getInterfaceName().toString();
        final String serviceName = m.service.service == null ? StepLogHelper.TIMEOUT_ERROR_MSG_UNDEFINED_REF
                : m.service.service.toString();
        final String endpointName = m.service.endpointName == null
                ? StepLogHelper.TIMEOUT_ERROR_MSG_UNDEFINED_REF
                : m.service.endpointName;
        final String operationName = m.exchange.getOperation() == null ? StepLogHelper.TIMEOUT_ERROR_MSG_UNDEFINED_REF
                : m.exchange.getOperation().toString();
        final long timeout = ((BcGatewayJBISender) jbiSender).getTimeout(currentConsumes);

        final FlowAttributes currentFA = PetalsExecutionContext.getFlowAttributes();

        return String.format(StepLogHelper.TIMEOUT_ERROR_MSG_PATTERN, timeout, interfaceName, serviceName, endpointName,
                operationName, currentFA.getFlowInstanceId(), currentFA.getFlowStepId());
    }

    @Override
    protected void logAfterReceivingFromChannel(final TransportedMessage m) {
        // acting as a provider partner, i.e. we are going to consume a service
        // a new consumes ext starts here
        if (m.step == 1) {
            final FlowAttributes consumeExtStep = PetalsExecutionContext.initFlowAttributes();

            final FlowAttributes provideExtStep = m.provideExtStep;
            assert provideExtStep != null;

            // we remember the step of the consumer partner through the correlated flow attributes
            logger.log(Level.MONIT, "",
                    new BcGatewayConsumeExtFlowStepBeginLogData(consumeExtStep,
                            // this is a correlated flow id
                            provideExtStep,
                            // TODO id unique inside the SU, not the component
                            getId()));
        }
    }

    @Override
    protected void logBeforeSendingToChannel(final TransportedMessage m) {

        final FlowAttributes consumeExtStep = PetalsExecutionContext.getFlowAttributes();
        // it was set by the CDK
        assert consumeExtStep != null;

        // the end of the one started in ConsumerDomain.logBeforeSendingToNMR
        if (m.step == 2) {
            StepLogHelper.addMonitExtEndOrFailureTrace(logger, m.exchange, consumeExtStep, true);
        }
    }

    /**
     * Retrieve a service consumer definition from a transported message
     * 
     * @param m
     *            The transported message. Not {@code null}
     * @return The service consumer definition matching the transported message
     */
    private Consumes retrieveConsumes(final TransportedMessage m) {
        assert m != null;

        final ServiceKey service = m.service;
        final Consumes currentConsumes = this.sum.getConsumesFromDestination(service.endpointName, service.service,
                service.interfaceName, m.exchange.getOperation());
        if (currentConsumes == null) {
            return this.sum.getConsumesFromDestination(service.endpointName, service.service, service.interfaceName);
        } else {
            return currentConsumes;
        }
    }
}
