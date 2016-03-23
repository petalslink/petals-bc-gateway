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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.utils.LastLoggingHandler;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;

import com.ebmwebsourcing.easycommons.lang.UncheckedException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.serialization.ClassResolver;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * There is one instance of this class per listener in a component configuration (jbi.xml).
 * 
 * It is responsible of creating the basic listener with netty which will dispatch connections to
 * {@link TransportServer}.
 * 
 * TODO for now, we use ONE channel for both technical messages and business messages: we should check what are the
 * shortcoming of this in terms of performances...
 * 
 * @author vnoel
 *
 */
public class TransportListener implements ConsumerAuthenticator {

    public static final String LOG_ERRORS_HANDLER = "log-errors";

    public static final String LOG_DEBUG_HANDLER = "log-debug";

    private final ServerBootstrap bootstrap;

    private final JbiTransportListener jtl;

    @Nullable
    private Channel channel;

    /**
     * These are the actual consumer partner actually connected to us, potentially through multiple {@link Channel}.
     * 
     * Careful, here the key is the auth-name, so it must be unique across SUs!!
     * 
     */
    private final ConcurrentMap<String, ConsumerDomain> consumers = new ConcurrentHashMap<>();

    public TransportListener(final JbiTransportListener jtl,
            final ServerBootstrap partialBootstrap, final Logger logger, final ClassResolver cr) {
        this.jtl = jtl;

        // shared between all the connections of this listener
        final TransportDispatcher dispatcher = new TransportDispatcher(logger, this);
        final LoggingHandler debugs = new LoggingHandler(logger.getName() + ".dispatcher", LogLevel.TRACE);
        final ChannelHandler errors = new LastLoggingHandler(logger.getName() + ".errors");

        final ObjectEncoder objectEncoder = new ObjectEncoder();

        final ServerBootstrap _bootstrap = partialBootstrap.handler(new LoggingHandler(logger.getName() + ".listener"))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final @Nullable Channel ch) throws Exception {
                        assert ch != null;
                        final ChannelPipeline p = ch.pipeline();
                        p.addLast(LOG_DEBUG_HANDLER, debugs);
                        p.addLast(objectEncoder);
                        p.addLast(new ObjectDecoder(cr));
                        p.addLast("dispatcher", dispatcher);
                        p.addLast(LOG_ERRORS_HANDLER, errors);
                    }
                }).localAddress(jtl.getPort());
        assert _bootstrap != null;
        bootstrap = _bootstrap;
    }

    /**
     * Start listening
     */
    public void bind() {
        final Channel _channel;
        try {
            // we must use sync so that we know if a problem arises
            _channel = bootstrap.bind().sync().channel();
        } catch (final InterruptedException e) {
            throw new UncheckedException(e);
        }
        assert _channel != null;
        channel = _channel;
    }

    /**
     * Stop listening
     */
    public void unbind() {
        final Channel _channel = channel;
        if (_channel != null && _channel.isOpen()) {
            try {
                // we must use sync so that we know if a problem arises
                _channel.close().sync();
            } catch (final InterruptedException e) {
                throw new UncheckedException(e);
            }
            channel = null;
        }
    }

    @Override
    public @Nullable ConsumerDomain authenticate(final String authName) {
        return consumers.get(authName);
    }

    public void register(final JbiConsumerDomain jcd, final ConsumerDomain cd) throws PEtALSCDKException {
        if (consumers.putIfAbsent(jcd.getAuthName(), cd) != null) {
            throw new PEtALSCDKException(
                    "A consumer partner with the auth-name '" + jcd.getAuthName()
                            + "' is already registered for the transporter '" + jtl.getId() + "'");
        }
    }

    public void deregistrer(final JbiConsumerDomain jcd) {
        consumers.remove(jcd.getAuthName());
    }
}
