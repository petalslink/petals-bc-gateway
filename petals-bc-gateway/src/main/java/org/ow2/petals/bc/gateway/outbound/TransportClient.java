/**
 * Copyright (c) 2016-2020 Linagora
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
package org.ow2.petals.bc.gateway.outbound;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.commons.handlers.AuthenticatorSSLHandler;
import org.ow2.petals.bc.gateway.commons.handlers.AuthenticatorSSLHandler.AuthRefuseException;
import org.ow2.petals.bc.gateway.commons.handlers.AuthenticatorSSLHandler.DomainHandlerBuilder;
import org.ow2.petals.bc.gateway.commons.handlers.HandlerConstants;
import org.ow2.petals.bc.gateway.commons.handlers.LastLoggingHandler;
import org.ow2.petals.bc.gateway.commons.messages.Transported.TransportedToConsumer;
import org.ow2.petals.bc.gateway.commons.messages.TransportedForExchange;
import org.ow2.petals.bc.gateway.commons.messages.TransportedPropagations;
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
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

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

    private final Lock mainLock = new ReentrantLock();

    private int retries = 0;

    private @Nullable Channel channel;

    private @Nullable Future<Void> connectOrNext;

    private @Nullable Future<Channel> authenticationFuture;

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
                final AuthenticatorSSLHandler authHandler = new AuthenticatorSSLHandler(pd, logger,
                        new DomainHandlerBuilder<ProviderDomain>() {
                            @Override
                            public ChannelHandler build(final ProviderDomain domain) {
                                assert domain == pd;
                                return new DomainHandler();
                            }
                        });
                authenticationFuture = authHandler.authenticationFuture();
                p.addLast(HandlerConstants.DOMAIN_HANDLER, authHandler);
                p.addLast(HandlerConstants.LOG_ERRORS_HANDLER, errors);
            }
        });
        assert _bootstrap != null;
        bootstrap = _bootstrap;
    }

    /**
     * Connect to the provider partner
     * 
     * @param force
     *            if <code>true</code>, then if we are already connected, we will first be disconnected
     */
    public void connect(boolean force) {
        mainLock.lock();
        try {
            if (!force && channel != null && channel.isActive()) {
                return;
            }

            // if we were not connected, it won't do anything specific
            // if we were between retries, it will cancel them
            // if we were connected, it will disconnect
            disconnect();

            // reset the number of retries
            retries = 0;

            // connect and setup reconnect if needed
            doConnect();
        } finally {
            mainLock.unlock();
        }
    }

    private void doConnect() {
        final String ip = pd.getJPD().getRemoteIp();
        // it should have been checked already at deploy
        final int port = Integer.parseInt(pd.getJPD().getRemotePort());

        logger.info("Connecting to " + pd.getJPD().getId() + " (" + ip + ":" + port + ")"
                + (retries > 0 ? ", retry " + retries + " of " + pd.getJPD().getRetryMax() : ""));

        // it will be set by the channel initializer during connect!
        authenticationFuture = null;
        connectOrNext = bootstrap.remoteAddress(ip, port).connect().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final @Nullable ChannelFuture future) throws Exception {
                assert future != null;
                final Channel ch = future.channel();
                assert ch != null;
                if (!future.isSuccess()) {
                    // here the connect itself failed, the cause will most certainly be non-null
                    setupReconnectIfNeeded(ch, future.cause(), false);
                } else {
                    // here the connect succeeded, but maybe the authentication will fail
                    assert authenticationFuture != null;
                    authenticationFuture.addListener(new FutureListener<Channel>() {
                        @Override
                        public void operationComplete(final @Nullable Future<Channel> future) throws Exception {
                            assert future != null;
                            if (future.isSuccess()) {
                                // we set it only once we are sure everything went well
                                channel = ch;
                                // now we can setup reconnect for close
                                ch.closeFuture().addListener(new ChannelFutureListener() {
                                    @Override
                                    public void operationComplete(final @Nullable ChannelFuture future)
                                            throws Exception {
                                        assert future != null;
                                        // here the channel was closed, the cause will most certainly be null
                                        setupReconnectIfNeeded(ch, future.cause(), true);
                                    }
                                });
                            } else {
                                // in that case, authentication failed, the cause will most certainly be non-null
                                setupReconnectIfNeeded(ch, future.cause(), false);
                            }
                        }
                    });
                }
            }
        });
    }

    private void setupReconnectIfNeeded(final Channel ch, final @Nullable Throwable cause, final boolean closed) {
        mainLock.lock();
        try {
            // let's close it in case it is not already the case
            ch.close();

            if (connectOrNext == null) {
                // we are explicitly disconnected
                return;
            }

            final int retryMax = pd.getJPD().getRetryMax();

            // negative means infinite, 0 means no retry
            final boolean shouldRetry = retryMax != 0 && (retryMax < 0 || (retryMax - retries) > 0);

            final StringBuilder msg;
            final Throwable error;
            if ((shouldRetry && logger.isLoggable(Level.WARNING))
                    || (!shouldRetry && logger.isLoggable(Level.SEVERE))) {
                msg = new StringBuilder("Connection to provider domain ");
                msg.append(pd.getId());
                if (closed) {
                    msg.append(" (").append(ch.remoteAddress()).append(") was closed unexpectedly.");
                } else {
                    msg.append(" failed.");
                }
                // TODO is there other causes where we don't want to bother with the whole stacktrace?
                if (cause instanceof AuthRefuseException) {
                    msg.append(" Reason was: " + cause.getMessage() + ".");
                    error = null;
                } else {
                    error = cause;
                }
            } else {
                msg = null;
                error = cause;
            }

            if (shouldRetry) {
                retries++;

                // this can't be negative, it was verified at deploy
                final long retryDelay = pd.getJPD().getRetryDelay();

                if (logger.isLoggable(Level.WARNING)) {
                    assert msg != null;
                    msg.append(" Reconnecting in ").append(retryDelay).append("ms");
                    msg.append(" (retry ").append(retries);
                    if (retryMax > 0) {
                        msg.append(" of ").append(retryMax).append(")");
                    } else {
                        msg.append(")");
                    }
                    logger.log(Level.WARNING, msg.toString(), error);
                }

                connectOrNext = ch.eventLoop().schedule(new Callable<Void>() {
                    @Override
                    public @Nullable Void call() throws Exception {
                        mainLock.lock();
                        try {
                            if (connectOrNext == null) {
                                // we are explicitly disconnected
                                return null;
                            }
                            doConnect();
                            return null;
                        } finally {
                            mainLock.unlock();
                        }
                    }
                }, retryDelay, TimeUnit.MILLISECONDS);

            } else {
                if (logger.isLoggable(Level.SEVERE)) {
                    assert msg != null;
                    logger.log(Level.SEVERE, msg.toString(), error);
                }
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Disconnect from the provider partner
     */
    public void disconnect() {
        mainLock.lock();
        try {
            final Channel _channel = channel;
            channel = null;
            authenticationFuture = null;

            final Future<Void> _connectOrNext = connectOrNext;
            if (_connectOrNext != null) {
                connectOrNext = null;
                _connectOrNext.cancel(true);
            }

            if (_channel != null && _channel.isOpen()) {
                // Note: this should trigger a call to ProviderDomain.close() as defined in DomainHandler!
                _channel.close();
            }
        } finally {
            mainLock.unlock();
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
            } else if (msg instanceof TransportedPropagations) {
                pd.updatePropagatedServices((TransportedPropagations) msg);
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
