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

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.commons.handlers.AuthenticatorSSLHandler;
import org.ow2.petals.bc.gateway.commons.handlers.AuthenticatorSSLHandler.DomainHandlerBuilder;
import org.ow2.petals.bc.gateway.commons.handlers.HandlerConstants;
import org.ow2.petals.bc.gateway.commons.handlers.LastLoggingHandler;
import org.ow2.petals.bc.gateway.commons.messages.Transported.TransportedToConsumer;
import org.ow2.petals.bc.gateway.commons.messages.TransportedForExchange;
import org.ow2.petals.bc.gateway.commons.messages.TransportedPropagatedConsumes;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.serialization.ClassResolver;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * This is responsible of connecting to {@link TransportListener}.
 * 
 * It handles retry of connect on connection failure as well as unexpected connection close.
 * 
 * It setup SSL if needed.
 * 
 * @author vnoel
 *
 */
public class TransportClient {

    private final Logger logger;

    private final ProviderDomain pd;

    private final Bootstrap bootstrap;

    private int retries = 0;

    @Nullable
    private Channel channel;

    public TransportClient(final ServiceUnitDataHandler handler, final Bootstrap partialBootstrap, final Logger logger,
            final ClassResolver cr, final ProviderDomain pd) {
        this.logger = logger;
        this.pd = pd;

        final LoggingHandler debugs = new LoggingHandler(logger.getName() + ".client", LogLevel.TRACE);
        final LastLoggingHandler errors = new LastLoggingHandler(logger.getName() + ".errors");
        final ObjectEncoder objectEncoder = new ObjectEncoder();

        final Bootstrap _bootstrap = partialBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // This mirror the protocol used in TransporterListener
                final ChannelPipeline p = ch.pipeline();
                p.addFirst(HandlerConstants.LOG_DEBUG_HANDLER, debugs);
                p.addLast(objectEncoder);
                p.addLast(new ObjectDecoder(cr));
                p.addLast(HandlerConstants.DOMAIN_HANDLER,
                        new AuthenticatorSSLHandler(pd, logger, new DomainHandlerBuilder<ProviderDomain>() {
                            @Override
                            public ChannelHandler build(final ProviderDomain domain) {
                                assert domain == pd;
                                return new DomainHandler();
                            }
                        }));
                p.addLast(HandlerConstants.LOG_ERRORS_HANDLER, errors);
            }
        });
        assert _bootstrap != null;
        bootstrap = _bootstrap;
    }

    /**
     * Connect to the provider partner
     */
    public void connect() {
        // reset the number of retries
        retries = 0;
        doConnect();
    }

    private void doConnect() {
        // it should have been checked already by JbiGatewayJBIHelper
        final int port = Integer.parseInt(pd.getJPD().getRemotePort());

        bootstrap.remoteAddress(pd.getJPD().getRemoteIp(), port).connect().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                assert future != null;
                if (!future.isSuccess()) {
                    reconnect(future, false);
                } else {
                    final Channel _channel = future.channel();
                    channel = _channel;
                    _channel.closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                            assert future != null;
                            reconnect(future, true);
                        }
                    });
                }
            }
        });
    }

    /**
     * TODO add tests
     */
    private void reconnect(final ChannelFuture future, final boolean closed) {
        final Channel ch = future.channel();
        final StringBuilder msg = new StringBuilder("Connection to provider domain ");
        msg.append(pd.getId());
        if (closed) {
            msg.append(" (").append(ch.remoteAddress()).append(") was closed unexpectedly");
        } else {
            msg.append(" failed");
        }
        final Integer retryMax = pd.getJPD().getRetryMax();
        // TODO do we really want to log the whole exception for both??
        // negative means infinite, null or 0 means no retry
        if (retryMax != null && (retryMax < 0 || (retryMax-retries) > 0)) {
            retries++;
            // this can't be null or negative, it was verified at deploy
            final long retryDelay = pd.getJPD().getRetryDelay();
            msg.append(", reconnecting in ").append(retryDelay).append("ms");
            msg.append(" (retry ").append(retries);
            if (retryMax > 0) {
                msg.append(" of ").append(retryMax).append(")");
            } else {
                msg.append(")");
            }
            // Note: in the case of closed, the cause is null and it's normal
            logger.log(Level.WARNING, msg.toString(), future.cause());
            // TODO propose another way to force reconnect, e.g. with an admin command
            ch.eventLoop().schedule(new Callable<Void>() {
                @Override
                public @Nullable Void call() throws Exception {
                    doConnect();
                    return null;
                }
            }, retryDelay, TimeUnit.MILLISECONDS);
        } else {
            // Note: in the case of closed, the cause is null and it's normal
            logger.log(Level.SEVERE, msg.toString(), future.cause());
        }
    }

    /**
     * Disconnect from the provider partner
     */
    public void disconnect() {
        final Channel _channel = channel;
        channel = null;
        if (_channel != null && _channel.isOpen()) {
            // Note: this should trigger a call to ProviderDomain.close() as defined in DomainHandler!
            _channel.close();
        }
    }

    public @Nullable ChannelHandlerContext getDomainContext() {
        final Channel _channel = channel;
        if (_channel != null) {
            return _channel.pipeline().context(DomainHandler.class);
        } else {
            return null;
        }
    }

    private class DomainHandler extends SimpleChannelInboundHandler<TransportedToConsumer> {
        @Override
        protected void channelRead0(final @Nullable ChannelHandlerContext ctx,
                final @Nullable TransportedToConsumer msg) throws Exception {
            assert ctx != null;
            assert msg != null;

            if (msg instanceof TransportedForExchange) {
                pd.receiveFromChannel(ctx, (TransportedForExchange) msg);
            } else if (msg instanceof TransportedPropagatedConsumes) {
                pd.updatePropagatedServices((TransportedPropagatedConsumes) msg);
            } else {
                throw new IllegalArgumentException("Impossible case");
            }
        }

        @Override
        public void channelInactive(final @Nullable ChannelHandlerContext ctx) throws Exception {
            pd.close();
        }
    }
}
