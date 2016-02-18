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
package org.ow2.petals.bc.gateway.outbound;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;

/**
 * This is used to initialise a connection (a {@link Channel}) towards a provider partner (represented by a
 * {@link ProviderDomain})
 * 
 * TODO this class could be removed and Channel directly owned by {@link ProviderDomain}...
 * 
 * TODO what about reconnect?
 *
 */
public class TransportConnection {

    private final Bootstrap bootstrap;

    @Nullable
    private Channel channel;

    public TransportConnection(final JBISender sender, final ProviderDomain pd, final Bootstrap partialBootstrap) {
        final Bootstrap bootstrap = partialBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // This mirror the protocol used in TransporterListener
                final ChannelPipeline p = ch.pipeline();
                p.addLast(new ObjectEncoder());
                p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                p.addLast(new TransportClient(sender, pd));
            }
        }).remoteAddress(pd.jpd.getIp(), pd.jpd.getPort());
        assert bootstrap != null;
        this.bootstrap = bootstrap;
    }

    public void connect() throws InterruptedException {
        // TODO should I do that async?
        final Channel channel = bootstrap.connect().sync().channel();
        assert channel != null;
        this.channel = channel;
    }

    public void disconnect() {
        final Channel channel = this.channel;
        if (channel != null && channel.isOpen()) {
            // TODO should I do that sync?
            channel.close();
            this.channel = null;
        }
    }

    public void send(final TransportedMessage m) {
        final Channel channel = this.channel;
        // we can't be disconnected in that case because the component is stopped and we don't process messages!
        assert channel != null;
        channel.writeAndFlush(m);
    }
}
