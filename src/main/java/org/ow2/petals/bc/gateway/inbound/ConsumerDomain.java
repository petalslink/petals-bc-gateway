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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainAddedConsumes;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainRemovedConsumes;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiConsumerDomain;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;

import io.netty.channel.ChannelHandlerContext;

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
public class ConsumerDomain {

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

    public final JbiConsumerDomain jcd;

    private final JbiGatewayComponent component;

    public ConsumerDomain(final JbiGatewayComponent component, final JbiConsumerDomain jcd) {
        this.component = component;
        this.jcd = jcd;
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
            for (final ServiceKey key : services.keySet()) {
                assert key != null;
                ctx.write(new TransportedToConsumerDomainAddedConsumes(key));
            }
            ctx.flush();
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

    /**
     * Ensure the transport is accepted for this consumer domain
     * 
     * TODO support many transports?
     */
    public boolean accept(final String transportId) {
        return jcd.transport.equals(transportId);
    }

    public void send(final ChannelHandlerContext ctx, final TransportedMessage m) {
        component.getSender().send(ctx, m);
    }
}
