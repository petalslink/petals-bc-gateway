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

import javax.jbi.messaging.MessageExchange.Role;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.messages.Transported;
import org.ow2.petals.bc.gateway.messages.TransportedException;
import org.ow2.petals.bc.gateway.messages.TransportedLastMessage;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.messages.TransportedMiddleMessage;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.messages.TransportedTimeout;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.logger.ProvideExtFlowStepFailureLogData;
import org.ow2.petals.component.framework.logger.Utils;

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

    public void sendFromChannelToNMR(final ChannelHandlerContext ctx, final TransportedMessage m) {
        final String exchangeId = m.exchangeId;

        // let's get the flow attribute from the received exchange and put them in context as soon as we get it
        // TODO add tests!
        PetalsExecutionContext.putFlowAttributes(m.flowAttributes);

        final Exchange exchange;
        if (m instanceof TransportedNewMessage) {
            exchange = null;
        } else {
            // we remove it now, if it has to come back (normally for InOptOut fault after out) it will be put back
            exchange = exchangesInProgress.remove(exchangeId);
            assert exchange != null;
        }

        sendToNMR(ctx, m, exchange);
    }

    private void sendToNMR(final ChannelHandlerContext ctx, final TransportedMessage m,
            final @Nullable Exchange exchange) {
        beforeSendingToNMR(m);

        this.sender.sendToNMR(getContext(this, ctx, m), exchange);
    }

    protected abstract void beforeSendingToNMR(TransportedMessage m);

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

        // for the other it is not needed
        if (m instanceof TransportedNewMessage) {
            Utils.addMonitFailureTrace(logger, PetalsExecutionContext.getFlowAttributes(), e.getMessage(),
                    Role.CONSUMER);
        }

        logger.log(Level.FINE, "Exception caught", e);

        final Transported msg;
        if (m instanceof TransportedLastMessage) {
            msg = new TransportedException(e);
        } else {
            m.exchange.setError(e);
            msg = new TransportedLastMessage(m, m.exchange);
        }

        sendToChannel(ctx, msg);
    }

    private void sendFromNMRToChannel(final ChannelHandlerContext ctx, final TransportedMessage m,
            final Exchange exchange) {
        assert !(m instanceof TransportedLastMessage);

        // for the other it is not needed
        if (m instanceof TransportedNewMessage) {
            Utils.addMonitEndOrFailureTrace(logger, exchange, PetalsExecutionContext.getFlowAttributes());
        }

        final TransportedMessage msg;
        if (exchange.isActiveStatus()) {
            msg = new TransportedMiddleMessage(m, exchange.getMessageExchange());
            // we will be expecting an answer
        } else {
            msg = new TransportedLastMessage(m, exchange.getMessageExchange());
        }

        sendToChannel(ctx, msg, exchange);
    }


    private void sendTimeoutFromNMRToChannel(final ChannelHandlerContext ctx, final TransportedMessage m) {

        // for the other it is not needed
        if (m instanceof TransportedNewMessage) {
            Utils.addMonitFailureTrace(logger, PetalsExecutionContext.getFlowAttributes(), "Timeout on the other side",
                    Role.CONSUMER);
        }

        sendToChannel(ctx, new TransportedTimeout(m));
    }

    protected void sendToChannel(final ChannelHandlerContext ctx, final TransportedMessage m, final Exchange exchange) {
        if (!(m instanceof TransportedLastMessage)) {
            final Exchange prev = exchangesInProgress.putIfAbsent(m.exchangeId, exchange);
            assert prev == null;
        }

        sendToChannel(ctx, m);
    }

    private void sendToChannel(final ChannelHandlerContext ctx, final Transported m) {
        ctx.writeAndFlush(m).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                assert future != null;
                // TODO add tests for these use cases!
                if (!future.isSuccess()) {
                    final Throwable cause = future.cause();
                    // TODO is the channel notified of the error too?
                    if (m instanceof TransportedMessage && !(m instanceof TransportedLastMessage)
                            && cause instanceof Exception) {
                        final TransportedMessage tm = (TransportedMessage) m;
                        // TODO what about the other side waiting for this exchange?!
                        final Exchange exchange = exchangesInProgress.remove(tm.exchangeId);
                        assert exchange != null;
                        exchange.setError((Exception) cause);
                        final TransportedLastMessage tlm = new TransportedLastMessage(tm,
                                exchange.getMessageExchange());
                        // TODO I logged ConsumeExtEnd/Failure in the sendFromNMRToChannel methods, but now, should I
                        // have logged instead this error?! (in ConsumerDomain.beforeSendingToNMR)
                        // TODO if this fail, the channel will get notified again and this will form a loop...
                        sendToNMR(ctx, tlm, exchange);
                    } else {
                        logger.log(Level.WARNING, "Can't send message over the channel but nothing I can do now: " + m,
                                cause);
                    }
                }
            }
        });
    }

    public void timeoutReceived(final TransportedTimeout m) {
        final Exchange removed = exchangesInProgress.remove(m.exchangeId);
        assert removed != null;
        
        if (this instanceof ProviderDomain
                && !(removed.isInOptionalOutPattern() && removed.isOutMessage() && removed.isFaultMessage())) {
            logger.log(Level.MONIT, "", new ProvideExtFlowStepFailureLogData(m.flowAttributes.getFlowInstanceId(),
                    m.flowAttributes.getFlowStepId(), "Timeout on the other side"));
        }
    }
}
