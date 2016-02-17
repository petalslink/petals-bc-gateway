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

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;

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
 * It is responsible of creating the basic listener with netty which will dispatch connections to
 * {@link TransportServer}.
 * 
 * TODO for now, we use ONE channel for both technical messages and business messages: we should check what are the
 * shortcoming of this in terms of performances...
 * 
 * @author vnoel
 *
 */
public class TransportListener implements ConsumerAuthenticator {

    private final ServerBootstrap bootstrap;

    private final ConsumerAuthenticator authenticator;

    private final JbiTransportListener jtl;

    @Nullable
    private Channel channel;

    public TransportListener(final ConsumerAuthenticator authenticator, final JbiTransportListener jtl,
            final ServerBootstrap partialBootstrap) {

        this.authenticator = authenticator;
        this.jtl = jtl;

        // shared between all the connections of this listener
        final TransportDispatcher dispatcher = new TransportDispatcher(this);

        final ServerBootstrap bootstrap = partialBootstrap.childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // TODO change to something better than objects...
                // we could have some kind of nice protocol?
                final ChannelPipeline p = ch.pipeline();
                p.addLast(new ObjectEncoder());
                p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                p.addLast(dispatcher);
            }
        }).localAddress(jtl.getPort());
        assert bootstrap != null;
        this.bootstrap = bootstrap;
    }

    public void bind() throws InterruptedException {
        // TODO should I do that async?
        final Channel channel = bootstrap.bind().sync().channel();
        assert channel != null;
        this.channel = channel;
    }

    public void unbind() {
        final Channel channel = this.channel;
        if (channel != null && channel.isOpen()) {
            // TODO should I do that sync?
            channel.close();
            this.channel = null;
        }
    }

    @Override
    public @Nullable ConsumerDomain authenticate(final String authName) {
        final ConsumerDomain cd = this.authenticator.authenticate(authName);
        if (cd != null && cd.accept(jtl.getId())) {
            return cd;
        } else {
            return null;
        }
    }
}
