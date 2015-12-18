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
package org.ow2.petals.bc.gateway.inbound;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.messaging.MessagingException;

import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainAddedConsumes;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainInit;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainRemovedConsumes;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.process.async.AsyncContext;

import io.netty.channel.ChannelHandlerContext;

/**
 * There is one instance of this class per consumer domain in an SU configuration (jbi.xml).
 * 
 * @author vnoel
 *
 */
public class ConsumerDomainDispatcher {

    /**
     * This lock is here to prevent concurrent modifications of {@link #services} and {@link #channels}.
     * 
     * It ensures that any ordering of adding channels and listeners will be valid.
     * 
     * For example so that the consumer partner domain connected will get ALL the updates about services added or
     * removed.
     * 
     * Note: actually, it is not SO important, because consumer domain and consumes are initialised together when an SU
     * is loaded, but who knows in the future if we change the architecture and consumer domain are attached to the
     * component directly too...
     */
    private final Lock lock = new ReentrantLock();

    /**
     * The keys of the {@link Consumes} propagated to this consumer domain.
     */
    private final Map<ServiceKey, Consumes> services = new HashMap<>();

    /**
     * The channels from this consumer domain (there can be more than one in case of HA or stuffs like that for example)
     */
    private final Set<ChannelHandlerContext> channels = new HashSet<>();

    /**
     * These are the exchanges currently being exchanged on the channels of this consumer domain.
     * 
     * TODO if the channel of one exchange has been closed, can it be sent back on another one? The problem is that the
     * sender has kept a copy of the sent exchange, and is expecting the answer to come back to him and not to one of
     * its other instances of the component...
     */
    private final Map<String, Exchange> exchanges = new ConcurrentHashMap<>();

    private final JbiGatewayComponent component;

    public ConsumerDomainDispatcher(final JbiGatewayComponent component) {
        this.component = component;
    }

    public void register(final Consumes consumes) {
        final ServiceKey key = new ServiceKey(consumes);
        lock.lock();
        try {
            // TODO what if the key is already registered? it shouldn't be a problem in practice, but shouldn't we warn
            // about it? Because it means there is overlapping consumes defined in the jbi descriptor... 
            services.put(key, consumes);
            for (final ChannelHandlerContext ctx : channels) {
                ctx.writeAndFlush(new TransportedToConsumerDomainAddedConsumes(key));
            }
        } finally {
            lock.unlock();
        }
    }

    public void deregister(final Consumes consumes) {
        final ServiceKey key = new ServiceKey(consumes);
        lock.lock();
        try {
            for (final ChannelHandlerContext ctx : channels) {
                ctx.writeAndFlush(new TransportedToConsumerDomainRemovedConsumes(key));
            }
            services.remove(key);
        } finally {
            lock.unlock();
        }
    }

    public void registerChannel(final ChannelHandlerContext ctx) {
        lock.lock();
        try {
            channels.add(ctx);
            final ServiceKey[] keys = services.keySet().toArray(new ServiceKey[services.size()]);
            assert keys != null;
            ctx.writeAndFlush(new TransportedToConsumerDomainInit(keys));
        } finally {
            lock.unlock();
        }
    }

    public void deregisterChannel(ChannelHandlerContext ctx) {
        lock.lock();
        try {
            channels.remove(ctx);
        } finally {
            lock.unlock();
        }
    }

    public void dispatch(final ChannelHandlerContext ctx, final TransportedMessage m) throws MessagingException {
        final JbiGatewaySender sender = component.getSender();
        // TODO we could avoid storing exchanges if we were able to create new exchanges with a given exchangeId...
        // actually we can, but it's not very safe...
        Exchange exchange = exchanges.get(m.id);
        if (exchange == null) {
            exchange = sender.createExchange(m.service.interfaceName, m.service.service, m.service.endpointName,
                    m.exchange.getPattern());
            // TODO create new exchange based on the received one and send it!
        } else {
            // TODO update exchange content
        }

        if (ExchangeStatus.ACTIVE.equals(exchange.getStatus())) {
            sender.sendAsync(exchange, new ConsumerDomainAsyncContext(m.service, ctx, this, m.id));
        } else {
            // we won't be expecting any more messages for this exchange
            exchanges.remove(m.id);
            sender.send(exchange);
        }
    }

    public void handleAnswer(final Exchange exchange, final ConsumerDomainAsyncContext context) {
        if (ExchangeStatus.ACTIVE.equals(exchange.getStatus())) {
            // let's remember it (TODO what about timeout?!) for the answer we will get back
            exchanges.put(context.partnerExchangeId, exchange);
        }

        final MessageExchange mex = exchange.getMessageExchange();
        assert mex != null;
        context.ctx.writeAndFlush(new TransportedMessage(context.service, context.partnerExchangeId, mex));
    }

    public static class ConsumerDomainAsyncContext extends AsyncContext {

        public final ConsumerDomainDispatcher cd;

        private final ChannelHandlerContext ctx;

        private final String partnerExchangeId;

        private final ServiceKey service;

        public ConsumerDomainAsyncContext(final ServiceKey service, final ChannelHandlerContext ctx,
                final ConsumerDomainDispatcher cd, final String partnerExchangeId) {
            this.service = service;
            this.ctx = ctx;
            this.cd = cd;
            this.partnerExchangeId = partnerExchangeId;
        }
    }
}
