/**
 * Copyright (c) 2016-2023 Linagora
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
 * along with this program/library; If not, see http://www.gnu.org/licenses/
 * for the GNU Lesser General Public License version 2.1.
 */
package org.ow2.petals.bc.gateway.commons.handlers;

import java.util.logging.Logger;

import org.eclipse.jdt.annotation.Nullable;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Used to log in error the events we don't handle but most certainly should!
 *
 */
@Sharable
public class LastLoggingHandler extends ChannelInboundHandlerAdapter {

    private final Logger logger;

    public LastLoggingHandler(final String loggerName) {
        this.logger = Logger.getLogger(loggerName);
    }

    @Override
    public void channelRead(final @Nullable ChannelHandlerContext ctx, final @Nullable Object msg) throws Exception {
        assert ctx != null;
        logger.severe(String.format(
                "Discarded inbound message %s that reached the tail of the pipeline. There is something wrong!",
                msg));
        // let the last handler free resources and stuffs like this
        ctx.fireChannelRead(msg);
    }

}
