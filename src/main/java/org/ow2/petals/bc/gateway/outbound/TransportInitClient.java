/**
 * Copyright (c) 2016 Linagora
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

import java.util.List;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainPropagatedConsumes;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

public class TransportInitClient extends ChannelInboundHandlerAdapter {

    private final JBISender sender;

    private final ProviderDomain pd;

    public TransportInitClient(final JBISender sender, final ProviderDomain pd) {
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

        if (!(msg instanceof List)) {
            // TODO replace that with an exception!
            ctx.writeAndFlush("Unexpected content");
            // TODO is that all I have to do??
            ctx.close();
            return;
        } else {
            final List<?> list = (List<?>)msg;
            for (final Object e : list) {
                if (!(e instanceof TransportedToConsumerDomainPropagatedConsumes)) {
                    // TODO replace that with an exception!
                    ctx.writeAndFlush("Unexpected content");
                    // TODO is that all I have to do??
                    ctx.close();
                    return;
                }
            }
        }
        
        @SuppressWarnings("unchecked")
        final List<TransportedToConsumerDomainPropagatedConsumes> consumes = (List<TransportedToConsumerDomainPropagatedConsumes>) msg;

        pd.initProviderServices(consumes);

        final ChannelPipeline p = ctx.pipeline();
        p.remove(this);
        p.addLast(new TransportClient(sender, pd));
    }
}
