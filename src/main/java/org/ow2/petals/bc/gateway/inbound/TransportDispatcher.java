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
package org.ow2.petals.bc.gateway.inbound;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiTransportListener;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Responsible of dispatching requests to the adequate {@link ConsumerDomain} which is initialised at the
 * beginning of the connection.
 * 
 * There is one instance of this class per active connection.
 * 
 * @author vnoel
 *
 */
public class TransportDispatcher extends ChannelInboundHandlerAdapter {

    private final JbiGatewayComponent component;

    private final JbiTransportListener jtl;

    /**
     * When this is <code>null</code>, it means we haven't yet identified the consumer domain contacting us and when it
     * is not, it means we have an active connections and we can pass messages to the bus.
     */
    @Nullable
    private ConsumerDomain cd = null;

    public TransportDispatcher(final JbiGatewayComponent component, final JbiTransportListener jtl) {
        this.component = component;
        this.jtl = jtl;
    }

    /**
     * TODO which exceptions are caught here? all that are not already caught by a {@link ChannelFuture} handler?
     */
    @Override
    public void exceptionCaught(@Nullable ChannelHandlerContext ctx, @Nullable Throwable cause) throws Exception {
        assert ctx != null;
        assert cause != null;
        // TODO log
        // TODO do something else? removing myself? does it make sense?
        ctx.close();
    }

    @Override
    public void channelInactive(final @Nullable ChannelHandlerContext ctx) throws Exception {
        assert ctx != null;
        final ConsumerDomain cd = this.cd;
        // TODO ensure this can be called while a read is done below concurrently...
        if (cd != null) {
            cd.deregisterChannel(ctx);
            this.cd = null;
        }
    }

    @Override
    public void channelRead(final @Nullable ChannelHandlerContext ctx, final @Nullable Object msg) throws Exception {
        assert ctx != null;
        assert msg != null;

        if (this.cd == null) {
            final String consumerAuthName = (String) msg;

            final ConsumerDomain cd = component.getServiceUnitManager().getConsumerDomain(consumerAuthName);

            if (cd != null) {
                // let's check
                if (cd.accept(jtl.id)) {
                    // TODO verify it's ok to keep this context for the whole session of this connection...
                    // apparently yes but... is it?
                    cd.registerChannel(ctx);
                    this.cd = cd;
                } else {
                    ctx.writeAndFlush(String.format("Transport not supported for '%s", consumerAuthName));
                    // TODO is that all I have to do??
                    ctx.close();
                }
            } else {
                ctx.writeAndFlush(String.format("Unknown %s '%s",
                        JbiGatewayJBIHelper.EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME.getLocalPart(), consumerAuthName));
                // TODO is that all I have to do??
                ctx.close();
            }

        } else {
            if (msg instanceof TransportedMessage) {
                component.getSender().send(ctx, (TransportedMessage) msg);
            } else if (msg instanceof Exception) {
                // TODO print: if we could have sent it back, we would have gotten a TransportedLastMessage!
            } else {
                // TODO notification or other things?
            }
        }
    }

}