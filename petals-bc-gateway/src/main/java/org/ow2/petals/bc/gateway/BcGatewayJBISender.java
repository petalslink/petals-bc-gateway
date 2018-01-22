/**
 * Copyright (c) 2015-2018 Linagora
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
package org.ow2.petals.bc.gateway;

import javax.jbi.messaging.MessagingException;

import org.ow2.petals.bc.gateway.commons.DomainContext;
import org.ow2.petals.bc.gateway.commons.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.listener.AbstractListener;
import org.ow2.petals.component.framework.process.async.AsyncContext;

import io.netty.channel.ChannelHandlerContext;

/**
 * This is responsible of managing the bridge between the {@link ConsumerDomain} (who gives us
 * {@link TransportedMessage}s and {@link ChannelHandlerContext}s to send answers back) and the bus from the provider
 * partner point of view.
 * 
 * Or between {@link ProviderDomain} (who gives us {@link TransportedMessage}s and {@link ChannelHandlerContext}s as
 * answers to our exchanges) and the bus from the consumer partner point of view.
 */
public class BcGatewayJBISender extends AbstractListener implements JBISender {

    public BcGatewayJBISender(final BcGatewayComponent component) {
        init(component);
    }

    @Override
    public BcGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (BcGatewayComponent) component;
    }

    /**
     * As provider partner (so, called by {@link ConsumerDomain}): this handle the first and third parts of an exchange,
     * i.e., when we receive a message from a consumer partner.
     * 
     * As consumer partner (so, called by {@link ProviderDomain}): this handle the second and fourth (in case of
     * InOutOnly) parts of an exchange, i.e., when we receive answers from a provider partner.
     */
    @Override
    public void sendToNMR(final DomainContext ctx, final Exchange exchange) throws MessagingException {

        if (exchange.isActiveStatus()) {
            sendAsync(exchange, new BcGatewaySenderAsyncContext(ctx));
        } else {
            send(exchange);
        }
    }

    public static class BcGatewaySenderAsyncContext extends AsyncContext {

        private final DomainContext ctx;

        public BcGatewaySenderAsyncContext(final DomainContext ctx) {
            this.ctx = ctx;
        }

        /**
         * As a provider partner: this handles the second and fourth (in case of inoptout) parts of an exchange, i.e.,
         * when we send back an answer to the consumer partner.
         * 
         * As a consumer partner: this handles the third part of an exchange, i.e., when we get aback an answer from the
         * provider partner.
         */
        public void handleAnswer(final Exchange exchange) {
            ctx.sendToChannel(exchange);
        }

        public void handleTimeout() {
            ctx.sendTimeoutToChannel();
        }
    }
}
