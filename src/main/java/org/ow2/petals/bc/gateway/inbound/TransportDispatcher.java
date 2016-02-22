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
import org.ow2.petals.bc.gateway.messages.TransportedAuthentication;
import org.ow2.petals.bc.gateway.messages.TransportedException;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;

/**
 * Dispatch a connection (from {@link TransportListener}) to the correct {@link ConsumerDomain}.
 * 
 * @author vnoel
 *
 */
@Sharable
public class TransportDispatcher extends SimpleChannelInboundHandler<TransportedAuthentication> {

    private final ConsumerAuthenticator authenticator;

    private final JBISender sender;

    public TransportDispatcher(final JBISender sender, final ConsumerAuthenticator authenticator) {
        this.sender = sender;
        this.authenticator = authenticator;
    }

    @Override
    protected void channelRead0(final @Nullable ChannelHandlerContext ctx,
            final @Nullable TransportedAuthentication msg) throws Exception {
        assert msg != null;
        assert ctx != null;

        try {
            final ConsumerDomain cd = authenticator.authenticate(msg.authName);

            // accept corresponds to validate that the current transport can be used for this consumer partner
            if (cd == null) {
                ctx.writeAndFlush(new TransportedException(String.format("Unauthorised auth-name '%s", msg.authName)));
                ctx.close();
                return;
            }

            // use replace because we want the logger to be last
            // TODO ensure unique name...
            ctx.pipeline().replace(this, "consumer-" + msg.authName, new TransportServer(sender, cd));
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }
}
