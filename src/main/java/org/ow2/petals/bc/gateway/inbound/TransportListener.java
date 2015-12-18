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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiTransportListener;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;

/**
 * There is one instance of this class per listener in a component configuration (jbi.xml).
 * 
 * @author vnoel
 *
 */
public class TransportListener {

    public final JbiTransportListener jtl;

    public final ServerBootstrap bootstrap;

    /**
     * This {@link Channel} can be used whenever we want to send things to the client! could make sense to send updates
     * or notifications or whatever...
     */
    @Nullable
    public Channel channel;

    /**
     * This must be accessed with synchonized: the access are not very frequent, so no need to introduce a specific
     * performance oriented locking
     * 
     * TODO The key is for now the auth-name declared in the jbi.xml, but later we need to introduce something better to
     * identify consumer and not simply a string Because this corresponds to a validity check of the consumer. e.g., a
     * public key fingerprint or something like that
     */
    @SuppressWarnings("null")
    public final Map<String, ConsumerDomainDispatcher> dispatchers = Collections
            .synchronizedMap(new HashMap<String, ConsumerDomainDispatcher>());

    private final JbiGatewayComponent component;

    public TransportListener(final JbiGatewayComponent component, final JbiTransportListener jtl,
            final ServerBootstrap partialBootstrap) {
        this.component = component;
        this.jtl = jtl;
        final ServerBootstrap bootstrap = partialBootstrap.childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // TODO change to something better than objects...
                // we could have some kind of nice protocol?
                final ChannelPipeline p = ch.pipeline();
                p.addLast(new ObjectEncoder());
                p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                p.addLast(new TransportDispatcher(TransportListener.this));
            }
        }).localAddress(jtl.port);
        assert bootstrap != null;
        this.bootstrap = bootstrap;
    }

    public @Nullable ConsumerDomainDispatcher getConsumerDomainDispatcher(final String consumerAuthName) {
        return dispatchers.get(consumerAuthName);
    }

    public void createConsumerDomainDispatcherIfNeeded(final String consumerAuthName) {
        if (!dispatchers.containsKey(consumerAuthName)) {
            dispatchers.put(consumerAuthName, new ConsumerDomainDispatcher(component));
        }
    }
}
