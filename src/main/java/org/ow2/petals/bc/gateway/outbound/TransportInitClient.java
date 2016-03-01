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

import java.util.logging.Logger;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.messages.TransportedAuthentication;
import org.ow2.petals.bc.gateway.messages.TransportedPropagatedConsumesList;
import org.ow2.petals.commons.log.Level;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;

public class TransportInitClient extends SimpleChannelInboundHandler<TransportedPropagatedConsumesList> {

    private final ProviderDomain pd;

    private final Logger logger;

    public TransportInitClient(final Logger logger, final ProviderDomain pd) {
        this.logger = logger;
        this.pd = pd;
    }

    @Override
    public void exceptionCaught(final @Nullable ChannelHandlerContext ctx, final @Nullable Throwable cause)
            throws Exception {
        logger.log(Level.WARNING, "Exception caught", cause);
    }

    @Override
    public void channelActive(final @Nullable ChannelHandlerContext ctx) throws Exception {
        assert ctx != null;
        ctx.writeAndFlush(new TransportedAuthentication(pd.getAuthName()));
    }

    @Override
    protected void channelRead0(final @Nullable ChannelHandlerContext ctx,
            final @Nullable TransportedPropagatedConsumesList msg) throws Exception {
        assert msg != null;
        assert ctx != null;
        try {
            pd.initProviderServices(msg);

            // use replace because we want the logger to be last
            ctx.pipeline().replace(this, "client", new TransportClient(logger, pd));
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }
}
