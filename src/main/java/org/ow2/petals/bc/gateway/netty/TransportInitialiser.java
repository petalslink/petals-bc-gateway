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
package org.ow2.petals.bc.gateway.netty;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JbiGatewayComponent.TransportListener;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

@Sharable
public class TransportInitialiser extends ChannelInitializer<SocketChannel> {

    private final TransportListener tl;

    public TransportInitialiser(final TransportListener tl) {
        this.tl = tl;
    }

    @Override
    protected void initChannel(final @Nullable SocketChannel ch) throws Exception {
        assert ch != null;
        // TODO change to something better than objects...
        // we should have some kind of nice protocol
        final ChannelPipeline p = ch.pipeline();
        // TODO 80 should be long enough for a consumer name...
        p.addLast(new LineBasedFrameDecoder(80));
        p.addLast(new StringDecoder());
        p.addLast(new StringEncoder());
        p.addLast(new TransportDispatcher(tl));
    }

}
