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
import org.ow2.petals.bc.gateway.inbound.ConsumerDomainDispatcher;
import org.ow2.petals.bc.gateway.inbound.JbiGatewayExternalListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

/**
 * Responsible of dispatching requests to the adequate {@link JbiGatewayExternalListener}.
 * 
 * @author vnoel
 *
 */
@Sharable
public class TransportDispatcher extends SimpleChannelInboundHandler<String> {

    private final TransportListener tl;

    public TransportDispatcher(final TransportListener tl) {
        this.tl = tl;
    }

    @Override
    protected void channelRead0(final @Nullable ChannelHandlerContext ctx, final @Nullable String consumerAuthName)
            throws Exception {
        assert ctx != null;
        assert consumerAuthName != null;

        final ConsumerDomainDispatcher dispatcher = tl.getConsumerDomainDispatcher(consumerAuthName);

        if (dispatcher != null) {
            final ChannelPipeline p = ctx.pipeline();
            // once we know for who it is we can remove this part of the protocol
            p.remove(this);
            p.remove(StringDecoder.class);
            p.remove(StringEncoder.class);
            p.remove(LineBasedFrameDecoder.class);

            // and continue with the codec for objects...
            p.addLast(new ObjectEncoder());
            p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
            // and our own handler
            p.addLast(new TransportHandler(dispatcher));
        } else {
            ctx.writeAndFlush(String.format("Unknown %s '%s",
                    JbiGatewayJBIHelper.EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME.getLocalPart(),
                    consumerAuthName));
            // TODO is that all I have to do??
            ctx.close();
        }
    }

}