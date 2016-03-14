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

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.messages.TransportedException;
import org.ow2.petals.bc.gateway.messages.TransportedForService;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.messages.TransportedTimeout;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.message.Exchange;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public abstract class AbstractDomain {

    protected final Logger logger;

    private final JBISender sender;

    /**
     * Exchange is added
     */
    private final ConcurrentMap<String, Exchange> exchangesInProgress = new ConcurrentHashMap<>();

    public AbstractDomain(final JBISender sender, final Logger logger) {
        this.sender = sender;
        this.logger = logger;
    }

    protected abstract void logAfterReceivingFromChannel(TransportedForService m);

    public void receiveFromChannel(final ChannelHandlerContext ctx, final TransportedForService m) {
        // let's get the flow attribute from the received exchange and put them in context as soon as we get it
        // TODO add tests!
        PetalsExecutionContext.putFlowAttributes(m.flowAttributes);

        logAfterReceivingFromChannel(m);

        final Exchange exchange;
        // in all case where I receive something, I remove the exchange I stored before!
        // if it has to come back (e.g., for InOptOut fault after out) it will be put back
        if (m.step > 1) {
            exchange = exchangesInProgress.remove(m.exchangeId);
            assert exchange != null;
        } else {
            // in that case, it is the first one, so there is no exchange stored!
            exchange = null;
        }

        if (m instanceof TransportedException) {
            logger.log(Level.WARNING,
                    "Received an exception from the other side, this is purely informative, we can't do anything about it",
                    ((TransportedException) m).cause);
        } else if (m instanceof TransportedMessage) {
            sendToNMR(ctx, (TransportedMessage) m, exchange);
        } else if (m instanceof TransportedTimeout) {
            logger.log(Level.FINE,
                    "Received a timeout from the other side, this is purely informative, we can't do anything about it");
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

        final TransportedForService msg;
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
        if (exchange.isActiveStatus()) {
            msg = TransportedMessage.middleMessage(m, exchange.getMessageExchange());
            // we will be expecting an answer
        } else {
            msg = TransportedMessage.lastMessage(m, exchange.getMessageExchange());
        }

        sendToChannel(ctx, msg, exchange);
    }


    private void sendTimeoutFromNMRToChannel(final ChannelHandlerContext ctx, final TransportedMessage m) {
        sendToChannel(ctx, new TransportedTimeout(m));
    }

    protected void sendToChannel(final ChannelHandlerContext ctx, final TransportedMessage m, final Exchange exchange) {
        if (!m.last) {
            final Exchange prev = exchangesInProgress.putIfAbsent(m.exchangeId, exchange);
            assert prev == null;
        }

        sendToChannel(ctx, m);
    }

    protected abstract void logBeforeSendingToChannel(TransportedForService m);

    private void sendToChannel(final ChannelHandlerContext ctx, final TransportedForService m) {

        logBeforeSendingToChannel(m);

        ctx.writeAndFlush(m).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                assert future != null;
                // TODO add tests for these use cases!
                if (!future.isSuccess()) {
                    final Throwable cause = future.cause();
                    // TODO is the channel notified of the error too?
                    if (!m.last && cause instanceof Exception) {
                        final TransportedMessage tm = (TransportedMessage) m;
                        tm.exchange.setError((Exception) cause);
                        // TODO what about the other side waiting for this exchange?! it should be removed there...
                        // TODO there will be double copy of the error in the exchange again by the JBI Sender...
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
