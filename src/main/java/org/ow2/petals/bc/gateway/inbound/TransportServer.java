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
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.JbiGatewayJBISender;
import org.ow2.petals.bc.gateway.messages.Transported.TransportedToProvider;
import org.ow2.petals.bc.gateway.messages.TransportedException;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;

/**
 * 
 * Responsible of dispatching, for a given consumer partner {@link ConsumerDomain} once it has been authenticated by
 * {@link TransportDispatcher}, the received messages to the {@link JbiGatewayJBISender}.
 * 
 * There is one instance of this class per active connection.
 * 
 * @author vnoel
 *
 */
public class TransportServer extends SimpleChannelInboundHandler<TransportedToProvider> {

    private final ConsumerDomain cd;

    private final JBISender sender;

    // TODO we need a logger per server maybe... or per connection...
    public TransportServer(final JBISender sender, final ConsumerDomain cd) {
        this.sender = sender;
        this.cd = cd;
    }

    /**
     * TODO which exceptions are caught here? all that are not already caught by a {@link ChannelFuture} handler?
     */
    @Override
    public void exceptionCaught(final @Nullable ChannelHandlerContext ctx, final @Nullable Throwable cause)
            throws Exception {
        assert ctx != null;
        assert cause != null;
        // TODO log
        // TODO do something else? removing myself? does it make sense?
        ctx.close();
    }

    /**
     * At that point we know the {@link Channel} is already active
     */
    @Override
    public void handlerAdded(final @Nullable ChannelHandlerContext ctx) throws Exception {
        assert ctx != null;
        cd.registerChannel(ctx);
    }

    /**
     * TODO is that correct?
     */
    @Override
    public void channelInactive(final @Nullable ChannelHandlerContext ctx) throws Exception {
        assert ctx != null;
        cd.deregisterChannel(ctx);
    }

    @Override
    protected void channelRead0(final @Nullable ChannelHandlerContext ctx, final @Nullable TransportedToProvider msg)
            throws Exception {
        assert ctx != null;
        assert msg != null;

        try {
            if (msg instanceof TransportedException) {
                cd.exceptionReceived(ctx, ((TransportedException) msg));
            } else if (msg instanceof TransportedMessage) {
                sender.send(ctx, (TransportedMessage) msg);
            } else {
                throw new RuntimeException("Impossible case");
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }
}