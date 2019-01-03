/**
 * Copyright (c) 2016-2019 Linagora
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
package org.ow2.petals.bc.gateway.commons;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import javax.jbi.messaging.MessagingException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.bc.gateway.commons.messages.TransportedException;
import org.ow2.petals.bc.gateway.commons.messages.TransportedForExchange;
import org.ow2.petals.bc.gateway.commons.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.utils.BcGatewayJbiHelper.Pair;
import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public abstract class AbstractDomain {

    // this is not really used as an exception for knowing where it happened
    // we can thus reuse it and avoid the overhead of creating the exception
    public static final MessagingException TIMEOUT_EXCEPTION = new MessagingException(
            "A timeout happened while the BC Gateway sent an exchange to a JBI service");

    static {
        TIMEOUT_EXCEPTION.setStackTrace(new StackTraceElement[0]);
    }

    protected final Logger logger;

    protected final ServiceUnitDataHandler handler;

    private final JBISender sender;

    /**
     * Exchange is added
     */
    private final ConcurrentMap<String, Pair<Exchange, FlowAttributes>> exchangesInProgress = new ConcurrentHashMap<>();

    public AbstractDomain(final JBISender sender, final ServiceUnitDataHandler handler, final Logger logger) {
        this.sender = sender;
        this.handler = handler;
        this.logger = logger;
    }

    public ServiceUnitDataHandler getSUHandler() {
        return handler;
    }

    public abstract String getId();

    protected abstract void logAfterReceivingFromChannel(TransportedMessage m);

    /**
     * TODO add tests about flow attributes
     */
    public void receiveFromChannel(final ChannelHandlerContext ctx, final TransportedForExchange m) {
        // in all case where I receive something, I remove the exchange I stored before!
        // if it has to come back (e.g., for InOptOut fault after out) it will be put back
        final Pair<Exchange, FlowAttributes> stored = exchangesInProgress.remove(m.exchangeId);

        if (m instanceof TransportedException) {
            final TransportedException te = (TransportedException) m;
            // in case of exception, we get back the step we sent before
            PetalsExecutionContext.putFlowAttributes(te.senderExtStep);
            // we receive exception only if we sent a non-active exchange (which are not stored)
            assert stored == null;

            logger.log(Level.WARNING,
                    "Received an exception from the other side, this is purely informative, we can't do anything about it",
                    te.cause);
        } else if (m instanceof TransportedMessage) {
            final TransportedMessage tm = (TransportedMessage) m;

            assert tm.step == 1 ^ stored != null;

            // do logs for starting the consumeExtStep of CD or provideExtStepEnd of PD
            logAfterReceivingFromChannel(tm);

            // this corresponds for CD to consumeExtStep and for PD to provideStep
            if (stored != null) {
                assert tm.step > 1;
                PetalsExecutionContext.putFlowAttributes(stored.getB());
            }

            try {
                // this gives us either the stored exchange updated or a new one (for step==1)
                final Exchange exchange = ExchangeHelper.updateStoredExchange(stored != null ? stored.getA() : null, tm,
                        this.sender);

                this.sender.sendToNMR(getContext(this, ctx, tm), exchange);
            } catch (final MessagingException e) {
                // if we couldn't update the exchange: it must have been in a wrong state... TODO is this a bug then?
                // if there was a problem sending the exchange: this is not a bug!!
                // in all case, we will reuse the exchange from the transported message
                sendErrorToChannel(ctx, tm, e);
            }
        } else {
            throw new IllegalArgumentException("Impossible case");
        }
    }

    private static DomainContext getContext(final AbstractDomain domain, final ChannelHandlerContext ctx,
            final TransportedMessage m) {
        return new DomainContext() {
            @Override
            public void sendToChannel(final Exchange exchange) {
                assert !m.last;
                domain.sendFromNMRToChannel(ctx, m.service, m, exchange);
            }

            @Override
            public void sendTimeoutToChannel() {
                // in this case, note that we will reuse the exchange that was received from the channel
                // because the one we sent to the NMR (which timed out) can't be modified anymore
                domain.sendErrorToChannel(ctx, m, TIMEOUT_EXCEPTION);
            }
        };
    }

    private void sendErrorToChannel(final ChannelHandlerContext ctx, final TransportedMessage m, final Exception e) {
        logger.log(Level.FINE, "Exception caught", e);

        if (m.last) {
            sendToChannel(ctx, new TransportedException(m, e));
        } else {
            m.exchange.setError(e);
            //
            sendToChannel(ctx, TransportedMessage.lastMessage(m, m.exchange));
        }
    }

    protected void sendFromNMRToChannel(final ChannelHandlerContext ctx, final ServiceKey service,
            final @Nullable TransportedMessage om, final Exchange exchange) {

        final TransportedMessage m = ExchangeHelper.updateTransportedExchange(om, service,
                exchange.getMessageExchange());

        if (!m.last) {
            // the current flow is either the provide step or the consume ext step
            final FlowAttributes fa = PetalsExecutionContext.getFlowAttributes();
            // it was set by the CDK (or us if it didn't have the time to go through the NMR)
            assert fa != null;
            final Pair<Exchange, FlowAttributes> prev = exchangesInProgress.putIfAbsent(m.exchangeId,
                    Pair.of(exchange, fa));
            assert prev == null;
        }

        sendToChannel(ctx, m);
    }

    protected abstract void logBeforeSendingToChannel(TransportedMessage m);

    private void sendToChannel(final ChannelHandlerContext ctx, final TransportedForExchange m) {

        if (m instanceof TransportedMessage) {
            final TransportedMessage tm = (TransportedMessage) m;
            logBeforeSendingToChannel(tm);
            m.senderExtStep = PetalsExecutionContext.getFlowAttributes();
        }

        ctx.writeAndFlush(m).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                assert future != null;
                // TODO add tests for these use cases!
                if (!future.isSuccess()) {
                    // TODO introduce some basic retrying before cancelling the send
                    // Careful because if we reconnect, I guess the channel is not the same one?!?!
                    final Throwable cause = future.cause();
                    // TODO is the channel notified of the error too?
                    // see https://groups.google.com/d/msg/netty/yWMRRS6zaQ0/2MYNvRZQAQAJ
                    if (m instanceof TransportedMessage && !((TransportedMessage) m).last) {
                        final TransportedMessage tm = (TransportedMessage) m;
                        tm.exchange.setError(new MessagingException(cause));
                        // TODO what about the other side waiting for this exchange?! it should be removed there... but
                        // if there is a connection problem, then maybe it is simply that it was stopped?
                        // we could take note of which exchangeId failed and send them on reconnect for cleaning?
                        logger.log(Level.WARNING,
                                "Can't send message over the channel, sending back the error over the NMR: " + m,
                                cause);
                        // TODO we have to wrap the modification in a transported message even though it hasn't been
                        // so there may be extra useless operations... improve that!
                        receiveFromChannel(ctx, TransportedMessage.lastMessage(tm, tm.exchange));
                    } else {
                        logger.log(Level.WARNING, "Can't send message over the channel but nothing I can do now: " + m,
                                cause);
                    }
                }
            }
        });
    }
}
