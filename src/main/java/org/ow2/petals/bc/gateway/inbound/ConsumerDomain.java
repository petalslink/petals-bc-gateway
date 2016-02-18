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
package org.ow2.petals.bc.gateway.inbound;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainAddedConsumes;
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
 * TODO how do we close this consumer domain?
 * 
 * @author vnoel
 *
 */
public class ConsumerDomain {

    /**
     * This lock is here to prevent concurrent modifications of {@link #services} and {@link #channels}.
     * 
     * It ensures that any ordering of adding channels and listeners will be valid.
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

    public ConsumerDomain(final JbiConsumerDomain jcd, final Collection<Consumes> consumes) {
        this.jcd = jcd;
        for (final Consumes c : consumes) {
            assert c != null;
            services.put(new ServiceKey(c), c);
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
        return jcd.getTransport().equals(transportId);
    }
}
