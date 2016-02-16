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
import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

/**
 * Dispatch a connection to the correct {@link ConsumerDomain}.
 * 
 * @author vnoel
 *
 */
@Sharable
public class TransportDispatcher extends ChannelInboundHandlerAdapter {

    private final JbiGatewayComponent component;

    private final JbiTransportListener jtl;

    public TransportDispatcher(final JbiGatewayComponent component, final JbiTransportListener jtl) {
        this.component = component;
        this.jtl = jtl;
    }

    @Override
    public void channelRead(final @Nullable ChannelHandlerContext ctx, final @Nullable Object msg) throws Exception {
        assert msg != null;
        assert ctx != null;

        if (!(msg instanceof String)) {
            // TODO replace that with an exception!
            ctx.writeAndFlush("Unexpected content");
            // TODO is that all I have to do??
            ctx.close();
            return;
        }

        // TODO test that too?
        final String consumerAuthName = (String) msg;

        // this corresponds to authenticating the consumer partner
        // TODO make that better
        final ConsumerDomain cd = component.getServiceUnitManager().getConsumerDomain(consumerAuthName);

        // accept corresponds to validate that the current transport can be used for this consumer partner
        if (cd == null || !cd.accept(jtl.getId())) {
            // TODO replace that with an exception!
            ctx.writeAndFlush(String.format("Unauthorised auth-name '%s", consumerAuthName));
            // TODO is that all I have to do??
            ctx.close();
            return;
        }

        final ChannelPipeline p = ctx.pipeline();
        p.remove(this);
        p.addLast(new TransportServer(cd));

    }
}
