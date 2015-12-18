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

import javax.jbi.messaging.MessagingException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Responsible of dispatching requests to the adequate {@link JbiGatewayExternalListener}.
 * 
 * There is one instance of this class per active connection.
 * 
 * @author vnoel
 *
 */
public class TransportDispatcher extends ChannelInboundHandlerAdapter {

    private final TransportListener tl;

    /**
     * When this is <code>null</code>, it means we haven't yet identified the consumer domain contacting us and when it
     * is not, it means we have an active connections and we can pass messages in it.
     */
    @Nullable
    private ConsumerDomainDispatcher dispatcher = null;

    public TransportDispatcher(final TransportListener tl) {
        this.tl = tl;
    }

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
        final ConsumerDomainDispatcher dispatcher = this.dispatcher;
        // TODO ensure this can be called while a read is done below concurrently...
        if (dispatcher != null) {
            dispatcher.deregisterChannel(ctx);
        }
    }

    @Override
    public void channelRead(final @Nullable ChannelHandlerContext ctx, final @Nullable Object msg) throws Exception {
        assert ctx != null;
        assert msg != null;

        if (dispatcher == null) {
            final String consumerAuthName = (String) msg;

            final ConsumerDomainDispatcher dispatcher = tl.getConsumerDomainDispatcher(consumerAuthName);

            if (dispatcher != null) {
                // TODO verify it's ok to keep this context for the whole session of this connection...
                // apparently yes but... is it?
                dispatcher.registerChannel(ctx);
                this.dispatcher = dispatcher;
            } else {
                ctx.writeAndFlush(String.format("Unknown %s '%s",
                        JbiGatewayJBIHelper.EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME.getLocalPart(), consumerAuthName));
                // TODO is that all I have to do??
                ctx.close();
            }

        } else {
            final ConsumerDomainDispatcher dispatcher = this.dispatcher;
            assert dispatcher != null;

            if (msg instanceof TransportedMessage) {
                try {
                    dispatcher.dispatch(ctx, (TransportedMessage) msg);
                } catch (final MessagingException e) {
                    // TODO forward error back!
                }
            } else {
                // TODO notification or other things?
            }
        }
    }

}