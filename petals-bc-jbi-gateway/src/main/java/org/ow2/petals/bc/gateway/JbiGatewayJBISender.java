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
package org.ow2.petals.bc.gateway;

import java.util.HashSet;
import java.util.Set;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.messaging.MessagingException;
import javax.jbi.messaging.NormalizedMessage;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.inbound.TransportServer;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.outbound.TransportClient;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.listener.AbstractListener;
import org.ow2.petals.component.framework.process.async.AsyncContext;
import org.ow2.petals.component.framework.process.async.AsyncMessageManager;

import io.netty.channel.ChannelHandlerContext;

/**
 * This is responsible of managing the bridge between the {@link TransportServer} (who gives us
 * {@link TransportedMessage}s and {@link ChannelHandlerContext}s to send answers back) and the bus from the provider
 * partner point of view.
 * 
 * Or between {@link TransportClient} (who gives us {@link TransportedMessage}s and {@link ChannelHandlerContext}s as
 * answers to our exchanges) and the bus from the consumer partner point of view.
 */
public class JbiGatewayJBISender extends AbstractListener implements JBISender {

    public JbiGatewayJBISender(final JbiGatewayComponent component) {
        init(component);
    }

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }

    private static void updateProperties(final @Nullable MessageExchange from, final @Nullable MessageExchange to) {
        assert from != null;
        assert to != null;

        // we remove all the properties of the exchange to update
        @SuppressWarnings("unchecked")
        final Set<String> oldProps = new HashSet<>(to.getPropertyNames());
        for (final String oldProp : oldProps) {
            if (ignoreProperty(oldProp)) {
                // TODO find a better solution...
                // let's skip this one, we don't want to remove it!
                continue;
            }
            to.setProperty(oldProp, null);
        }

        // and put all of those from the source exchange
        setProperties(from, to);
    }

    private static void setProperties(final @Nullable MessageExchange from, final @Nullable MessageExchange to) {
        assert from != null;
        assert to != null;

        // and put all of those from the source exchange
        @SuppressWarnings("unchecked")
        final Set<String> props = from.getPropertyNames();
        for (final String prop : props) {
            if (ignoreProperty(prop)) {
                // TODO find a better solution...
                // let's skip this one, we don't want to propagate it!
                continue;
            }
            to.setProperty(prop, from.getProperty(prop));
        }
    }

    private static boolean ignoreProperty(final String prop) {
        return prop.startsWith("org.ow2.petals.microkernel.jbi.messaging.exchange.DeliveryChannelImpl.")
                || prop.startsWith(AsyncMessageManager.ASYNC_MESSAGE_PROPERTY_PREFIX)
                || prop.equals("javax.jbi.messaging.sendSync");
    }

    /**
     * As provider partner (so called by {@link ConsumerDomain}): this handle the first and third parts of an exchange,
     * i.e., when we receive a message from a consumer partner.
     * 
     * As consumer partner (so called by {@link ProviderDomain}): this handle the second and fourth (in case of
     * InOutOnly) parts of an exchange, i.e., when we receive answers from a provider partner.
     * 
     * TODO this is not so good... it relies both on the {@link TransportedMessage} and the {@link Exchange} to know
     * what to do... maybe move the convert logic to {@link AbstractDomain} instead?
     */
    @Override
    public void sendToNMR(final DomainContext ctx, final @Nullable Exchange exchange) {
        final TransportedMessage m = ctx.getMessage();
        if (m.step == 1) {
            // provider: it is the first part of an exchange
            assert exchange == null;
            sendNewToNMR(ctx);
        } else if (m.last) {
            assert exchange != null;
            // provider: it is the third part of an exchange (if not active)
            // consumer: it is the second/fourth part of an exchange (if not active)
            sendLastToNMR(ctx, exchange);
        } else {
            assert exchange != null;
            // provider: it is the third part of an exchange (if still active)
            // consumer: it is the second part of an exchange (if still active)
            sendMiddleToNMR(ctx, exchange);
        }
        // provider: second and fourth parts of exchange happens in sendToChannel
        // consumer: third part of exchange happens in sendToChannel
    }

    private void sendNewToNMR(final DomainContext ctx) {

        final TransportedMessage m = ctx.getMessage();
        final MessageExchange hisMex = m.exchange;

        try {
            // this is a Consumes I propagated on the other side
            final ServiceKey service = m.service;

            final Exchange exchange = createExchange(service.interfaceName, service.service, service.endpointName,
                    hisMex.getPattern());

            setProperties(hisMex, exchange.getMessageExchange());

            exchange.setOperation(hisMex.getOperation());

            exchange.setInMessage(hisMex.getMessage(Exchange.IN_MESSAGE_NAME));

            sendAsync(exchange, new JbiGatewaySenderAsyncContext(ctx, this));
        } catch (final Exception e) {
            ctx.sendToChannel(e);
        }
    }

    private void sendMiddleToNMR(final DomainContext ctx, final Exchange exchange) {

        final TransportedMessage m = ctx.getMessage();
        final MessageExchange hisMex = m.exchange;

        try {
            updateProperties(hisMex, exchange.getMessageExchange());

            final NormalizedMessage out = hisMex.getMessage(Exchange.OUT_MESSAGE_NAME);
            if (out != null && !exchange.isOutMessage()) {
                exchange.setOutMessage(out);
            } else if (hisMex.getFault() != null && exchange.getFault() == null) {
                exchange.setFault(hisMex.getFault());
            }

            sendAsync(exchange, new JbiGatewaySenderAsyncContext(ctx, this));
        } catch (final Exception e) {
            ctx.sendToChannel(e);
        }
    }

    private void sendLastToNMR(final DomainContext ctx, final Exchange exchange) {

        final TransportedMessage m = ctx.getMessage();
        final MessageExchange hisMex = m.exchange;

        try {
            assert hisMex.getStatus() != ExchangeStatus.ACTIVE;

            updateProperties(hisMex, exchange.getMessageExchange());

            if (hisMex.getStatus() == ExchangeStatus.ERROR) {
                exchange.setErrorStatus();
                exchange.setError(hisMex.getError());
            } else if (hisMex.getStatus() == ExchangeStatus.DONE) {
                exchange.setDoneStatus();
            }

            send(exchange);
        } catch (final Exception e) {
            ctx.sendToChannel(e);
        }
    }

    /**
     * As a provider partner: this handles the second and fourth (in case of inoptout) parts of an exchange, i.e., when
     * we send back an answer to the consumer partner.
     * 
     * As a consumer partner: this handles the third part of an exchange, i.e., when we get aback an answer from the
     * provider partner.
     */
    private void sendExchangeToChannel(final Exchange exchange, final DomainContext ctx)
            throws MessagingException {

        final TransportedMessage m = ctx.getMessage();

        final MessageExchange hisMex = m.exchange;

        updateProperties(exchange.getMessageExchange(), hisMex);

        // Note: we do not verify the validity of the state/mep transitions!
        if (exchange.isErrorStatus()) {
            hisMex.setStatus(ExchangeStatus.ERROR);
            hisMex.setError(exchange.getError());
        } else if (exchange.isDoneStatus()) {
            // for InOnly, RobustInOnly and InOptOnly (2nd, 3rd or 4th part)
            hisMex.setStatus(ExchangeStatus.DONE);
        } else if (exchange.isFaultMessage()) {
            // for RobustInOnly, InOut and InOptOut (2nd or 3rd part)
            hisMex.setFault(exchange.getFault());
        } else {
            // for InOut and InOptOnly (2nd part)
            // (all other cases are covered by previous tests)
            hisMex.setMessage(exchange.getOutMessage(), Exchange.OUT_MESSAGE_NAME);
        }

        ctx.sendToChannel(exchange);
    }

    private void sendTimeoutToChannel(final DomainContext ctx) {
        ctx.sendTimeoutToChannel();
    }

    public static class JbiGatewaySenderAsyncContext extends AsyncContext {

        /**
         * TODO when doing sendSync, it would be best if the CDK return the exchange to us instead of one of the jbi
         * listeners of the processors... until then, we need to be called back by {@link JbiGatewayJBIListener} using
         * {@link #sender}.
         */
        private final JbiGatewayJBISender sender;

        private final DomainContext ctx;

        public JbiGatewaySenderAsyncContext(final DomainContext ctx, final JbiGatewayJBISender sender) {
            this.ctx = ctx;
            this.sender = sender;
        }

        public void handleAnswer(final Exchange exchange) throws MessagingException {
            this.sender.sendExchangeToChannel(exchange, ctx);
        }

        public void handleTimeout() {
            this.sender.sendTimeoutToChannel(ctx);
        }
    }
}
