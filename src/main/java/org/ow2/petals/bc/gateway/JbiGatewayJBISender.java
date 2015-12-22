/**
 * Copyright (c) 2015 Linagora
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

import java.util.Set;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.messaging.MessagingException;

import org.ow2.petals.bc.gateway.inbound.TransportServer;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedLastMessage;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.messages.TransportedMiddleMessage;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.outbound.TransportClient;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.listener.AbstractListener;
import org.ow2.petals.component.framework.message.ExchangeImpl;
import org.ow2.petals.component.framework.process.async.AsyncContext;

import io.netty.channel.ChannelHandlerContext;

/**
 * This is responsible of managing the bridge between the {@link TransportServer} (who gives us
 * {@link TransportedMessage}s and {@link ChannelHandlerContext}s to send answers back) and the bus from the provider
 * partner point of view.
 * 
 * Or between {@link TransportClient} (who gives us {@link TransportedMessage}s and {@link ChannelHandlerContext}s as
 * answers to our exchanges) and the bus from the consumer partner point of view.
 */
public class JbiGatewayJBISender extends AbstractListener {

    public JbiGatewayJBISender(final JbiGatewayComponent component) {
        init(component);
    }

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }

    /**
     * As provider partner: this handle the first and third parts of an exchange, i.e., when we receive a message from a
     * consumer partner.
     * 
     * As consumer partner: this handle the second and fourth (in case of InOutOnly) parts of an exchange, i.e., when we
     * receive answers from a provider partner.
     */
    public void send(final ChannelHandlerContext ctx, final TransportedMessage m) {
        if (m instanceof TransportedNewMessage) {
            // provider: it is the first part of an exchange
            send(ctx, (TransportedNewMessage) m);
        } else if (m instanceof TransportedMiddleMessage) {
            // provider: it is the third part of an exchange (if still active)
            // consumer: it is the second part of an exchange (if still active)
            send(ctx, (TransportedMiddleMessage) m);
        } else if (m instanceof TransportedLastMessage) {
            // provider: it is the third part of an exchange that (if not active)
            // consumer: it is the second/fourth part of an exchange (if not active)
            send(ctx, (TransportedLastMessage) m);
        } else {
            // provider: second and fourth parts of exchange happens in handleAnswer
            // consumer: third part of exchange happens in handleAnswer
            assert false;
        }
    }

    private void send(final ChannelHandlerContext ctx, final TransportedNewMessage m) {

        final MessageExchange hisMex = m.senderExchange;

        try {
            final ServiceKey service = m.service;
            final Exchange exchange = createExchange(service.interfaceName, service.service, service.endpointName,
                    hisMex.getPattern());
            exchange.setInMessage(hisMex.getMessage(Exchange.IN_MESSAGE_NAME));
            @SuppressWarnings("unchecked")
            final Set<String> propertyNames = (Set<String>) hisMex.getPropertyNames();
            for (final String propName : propertyNames) {
                exchange.setProperty(propName, hisMex.getProperty(propName));
            }

            sendAsync(exchange, new JbiGatewaySenderAsyncContext(ctx, m, this));
        } catch (final Exception e) {
            hisMex.setError(e);
            ctx.writeAndFlush(new TransportedLastMessage(m));
        }
    }

    private void send(final ChannelHandlerContext ctx, final TransportedMiddleMessage m) {
        try {
            // it has been updated on the other side (see handleAnswer)
            final Exchange exchange = new ExchangeImpl(m.receiverExchange);

            sendAsync(exchange, new JbiGatewaySenderAsyncContext(ctx, m, this));
        } catch (final Exception e) {
            final MessageExchange hisMex = m.senderExchange;
            hisMex.setError(e);
            ctx.writeAndFlush(new TransportedLastMessage(m));
        }
    }

    private void send(final ChannelHandlerContext ctx, final TransportedLastMessage m) {
        try {
            // it has been updated on the other side (see handleAnswer)
            send(new ExchangeImpl(m.receiverExchange));
        } catch (final Exception e) {
            ctx.writeAndFlush(e);
        }
    }

    /**
     * As a provider partner: this handles the second and fourth (in case of inoptout) parts of an exchange, i.e., when
     * we send back an answer to the consumer partner.
     * 
     * As a consumer partner: this handles the third part of an exchange, i.e., when we get aback an answer from the
     * provider partner.
     */
    public void handleAnswer(final Exchange exchange, final JbiGatewaySenderAsyncContext context)
            throws MessagingException {

        final MessageExchange hisMex;
        // let's check what was the received message
        if (context.m instanceof TransportedNewMessage) {
            hisMex = ((TransportedNewMessage) context.m).senderExchange;
        } else if (context.m instanceof TransportedMiddleMessage) {
            hisMex = ((TransportedMiddleMessage) context.m).senderExchange;
        } else {
            // this can't happen since we use send() for TransportedLastMessage
            hisMex = null;
        }
        
        assert hisMex != null;

        // TODO what about properties?
        // Note: we do not verify the validity of the state/mep transitions!
        if (exchange.isErrorStatus()) {
            hisMex.setError(exchange.getError());
        } else if (exchange.isDoneStatus()) {
            // for InOnly, RobustInOnly and InOptOnly (2nd or 4th part)
            hisMex.setStatus(ExchangeStatus.DONE);
        } else if (exchange.getFault() != null) {
            // for RobustInOnly, InOut and InOptOnly
            hisMex.setFault(exchange.getFault());
        } else {
            // for InOut and InOptOnly (all other cases will be covered by previous tests)
            hisMex.setMessage(exchange.getOutMessage(), Exchange.OUT_MESSAGE_NAME);
        }

        if (exchange.isActiveStatus()) {
            // we will be expecting an answer
            final MessageExchange myMex = exchange.getMessageExchange();
            assert myMex != null;
            // TODO where are the error sent?
            context.ctx.writeAndFlush(new TransportedMiddleMessage(context.m.service, hisMex, myMex));
        } else {
            // TODO where are the error sent?
            context.ctx.writeAndFlush(new TransportedLastMessage(context.m.service, hisMex));
        }
    }

    public static class JbiGatewaySenderAsyncContext extends AsyncContext {

        /**
         * TODO when doing sendSync, it would be best if the CDK return the exchange to us instead of one of the jbi
         * listeners of the processors... until then, we need to be called back by {@link JbiGatewayJBIListener} using
         * {@link #sender}.
         */
        public final JbiGatewayJBISender sender;

        private final TransportedMessage m;

        private final ChannelHandlerContext ctx;

        public JbiGatewaySenderAsyncContext(final ChannelHandlerContext ctx, final TransportedMessage m,
                final JbiGatewayJBISender sender) {
            this.m = m;
            this.ctx = ctx;
            this.sender = sender;
        }
    }
}
