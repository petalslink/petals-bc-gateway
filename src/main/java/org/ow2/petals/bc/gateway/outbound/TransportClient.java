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
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainAddedConsumes;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainRemovedConsumes;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class TransportClient extends ChannelInboundHandlerAdapter {

    private final JBISender sender;

    private final ProviderDomain pd;

    public TransportClient(final JBISender sender, final ProviderDomain pd) {
        this.sender = sender;
        this.pd = pd;
    }

    @Override
    public void channelActive(final @Nullable ChannelHandlerContext ctx) throws Exception {
        assert ctx != null;
        ctx.writeAndFlush(pd.getAuthName());
    }

    @Override
    public void channelRead(final @Nullable ChannelHandlerContext ctx, final @Nullable Object msg) throws Exception {
        assert ctx != null;
        assert msg != null;

        if (msg instanceof Exception) {
            pd.exceptionReceived((Exception) msg);
        } else if (msg instanceof TransportedMessage) {
            // this can't happen, we are the one sending new exchanges!
            assert !(msg instanceof TransportedNewMessage);
            sender.send(ctx, (TransportedMessage) msg);
        } else if (msg instanceof TransportedToConsumerDomainAddedConsumes) {
            final TransportedToConsumerDomainAddedConsumes m = (TransportedToConsumerDomainAddedConsumes) msg;
            pd.addedProviderService(m.service, m.description);
        } else if (msg instanceof TransportedToConsumerDomainRemovedConsumes) {
            pd.removedProviderService(((TransportedToConsumerDomainRemovedConsumes) msg).service);
        } else {
            // TODO handle unexpected content
        }
    }

}
