/**
 * Copyright (c) 2016 Linagora
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
package org.ow2.petals.bc.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import javax.jbi.messaging.MessageExchange;
import javax.jbi.messaging.MessagingException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.messages.TransportedException;
import org.ow2.petals.bc.gateway.messages.TransportedForExchange;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.message.Exchange;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public abstract class AbstractDomain {

    // this is not really used as an exception for knowing where it happened
    // we can thus reuse it and avoid the overhead of creating the exception
    public static final MessagingException TIMEOUT_EXCEPTION = new MessagingException(
            "A timeout happened while the JBI Gateway sent an exchange to a JBI service");

    static {
        TIMEOUT_EXCEPTION.setStackTrace(new StackTraceElement[0]);
    }

    protected final Logger logger;

    private final JBISender sender;

    /**
     * Exchange is added
     */
    private final ConcurrentMap<String, Pair<Exchange, FlowAttributes>> exchangesInProgress = new ConcurrentHashMap<>();

    public AbstractDomain(final JBISender sender, final Logger logger) {
        this.sender = sender;
        this.logger = logger;
    }

    protected abstract void logAfterReceivingFromChannel(TransportedMessage m);

    public void receiveFromChannel(final ChannelHandlerContext ctx, final TransportedForExchange m) {
        // in all case where I receive something, I remove the exchange I stored before!
        // if it has to come back (e.g., for InOptOut fault after out) it will be put back
        final Pair<Exchange, FlowAttributes> stored = exchangesInProgress.remove(m.exchangeId);

        // let's get the flow attribute from the received exchange and put them in context as soon as we get it
        // TODO add tests!
        if (stored != null) {
            PetalsExecutionContext.putFlowAttributes(stored.getB());
        } else {
            PetalsExecutionContext.initFlowAttributes();
        }

        if (m instanceof TransportedException) {
            assert stored != null;
            logger.log(Level.WARNING,
                    "Received an exception from the other side, this is purely informative, we can't do anything about it",
                    ((TransportedException) m).cause);
        } else if (m instanceof TransportedMessage) {
            final TransportedMessage tm = (TransportedMessage) m;

            assert tm.step == 1 ^ stored != null;

            // this will do logs
            logAfterReceivingFromChannel(tm);

            sendToNMR(ctx, tm, stored != null ? stored.getA() : null);
        } else {
            throw new IllegalArgumentException("Impossible case");
        }
    }

    private void sendToNMR(final ChannelHandlerContext ctx, final TransportedMessage m,
            final @Nullable Exchange exchange) {

        this.sender.sendToNMR(getContext(this, ctx, m), exchange);
    }

    private static DomainContext getContext(final AbstractDomain domain, final ChannelHandlerContext ctx,
            final TransportedMessage m) {
        return new DomainContext() {
            @Override
            public void sendToChannel(final Exchange exchange) {
                domain.sendFromNMRToChannel(ctx, m, exchange);
            }

            @Override
            public void sendToChannel(final Exception e) {
                domain.sendFromNMRToChannel(ctx, m, e);
            }

            @Override
            public void sendTimeoutToChannel() {
                domain.sendTimeoutFromNMRToChannel(ctx, m);
            }

            @Override
            public TransportedMessage getMessage() {
                return m;
            }
        };
    }

    private void sendFromNMRToChannel(final ChannelHandlerContext ctx, final TransportedMessage m,
            final Exception e) {

        logger.log(Level.FINE, "Exception caught", e);

        final TransportedForExchange msg;
        if (m.last) {
            msg = new TransportedException(m, e);
        } else {
            m.exchange.setError(e);
            msg = TransportedMessage.lastMessage(m, m.exchange);
        }

        sendToChannel(ctx, msg);
    }

    private void sendFromNMRToChannel(final ChannelHandlerContext ctx, final TransportedMessage m,
            final Exchange exchange) {
        assert !m.last;

        final TransportedMessage msg;
        final MessageExchange mex = exchange.getMessageExchange();
        assert mex != null;

        if (exchange.isActiveStatus()) {
            // we will be expecting an answer
            msg = TransportedMessage.middleMessage(m, mex);
        } else {
            msg = TransportedMessage.lastMessage(m, mex);
        }

        sendToChannel(ctx, msg, exchange);
    }

    private void sendTimeoutFromNMRToChannel(final ChannelHandlerContext ctx, final TransportedMessage m) {
        m.exchange.setError(TIMEOUT_EXCEPTION);
        sendToChannel(ctx, TransportedMessage.lastMessage(m, m.exchange));
    }

    protected void sendToChannel(final ChannelHandlerContext ctx, final TransportedMessage m, final Exchange exchange) {
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
            logBeforeSendingToChannel((TransportedMessage) m);
        }

        ctx.writeAndFlush(m).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                assert future != null;
                // TODO add tests for these use cases!
                if (!future.isSuccess()) {
                    final Throwable cause = future.cause();
                    // TODO is the channel notified of the error too?
                    if (m instanceof TransportedMessage && !((TransportedMessage) m).last
                            && cause instanceof Exception) {
                        final TransportedMessage tm = (TransportedMessage) m;
                        tm.exchange.setError((Exception) cause);
                        // TODO what about the other side waiting for this exchange?! it should be removed there...
                        // TODO there will be double copy of the error in the exchange again by the JBI Sender...
                        // improve that!
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
