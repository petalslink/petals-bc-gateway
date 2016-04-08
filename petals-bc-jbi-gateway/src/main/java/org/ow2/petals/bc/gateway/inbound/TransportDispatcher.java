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

import java.util.logging.Logger;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.messages.TransportedAuthentication;
import org.ow2.petals.bc.gateway.utils.LastLoggingHandler;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
import org.ow2.petals.component.framework.util.ServiceUnitUtil;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

/**
 * Dispatch a connection (from {@link TransportListener}) to the correct {@link ConsumerDomain}.
 * 
 * @author vnoel
 *
 */
@Sharable
public class TransportDispatcher extends SimpleChannelInboundHandler<TransportedAuthentication> {

    private final ConsumerAuthenticator authenticator;

    private final Logger logger;

    public TransportDispatcher(final Logger logger, final ConsumerAuthenticator authenticator) {
        this.logger = logger;
        this.authenticator = authenticator;
    }

    @Override
    public void exceptionCaught(final @Nullable ChannelHandlerContext ctx, final @Nullable Throwable cause)
            throws Exception {
        logger.log(Level.WARNING, "Exception caught", cause);
    }

    @Override
    protected void channelRead0(final @Nullable ChannelHandlerContext ctx,
            final @Nullable TransportedAuthentication msg) throws Exception {
        assert msg != null;
        assert ctx != null;

        final ConsumerDomain cd = authenticator.authenticate(msg.authName);

        // accept corresponds to validate that the current transport can be used for this consumer partner
        if (cd == null) {
            ctx.writeAndFlush(String.format("unknown auth-name '%s'", msg.authName));
            ctx.close();
            return;
        }

        final ChannelPipeline pipeline = ctx.pipeline();

        final JbiConsumerDomain jcd = cd.getJCD();
        final String certificate = jcd.getCertificate();
        final String key = jcd.getKey();
        if ((certificate != null && key != null)) {
            final ServiceUnitDataHandler handler = cd.getSUHandler();
            final SslContextBuilder builder = SslContextBuilder
                    .forServer(ServiceUnitUtil.getFile(handler.getInstallRoot(), certificate),
                            ServiceUnitUtil.getFile(handler.getInstallRoot(), key), jcd.getPassphrase())
                    .sslProvider(SslProvider.JDK).ciphers(null, IdentityCipherSuiteFilter.INSTANCE).sessionCacheSize(0)
                    .sessionTimeout(0);

            final String remoteCertificate = jcd.getRemoteCertificate();
            if (remoteCertificate != null) {
                builder.trustManager(ServiceUnitUtil.getFile(handler.getInstallRoot(), remoteCertificate))
                        .clientAuth(ClientAuth.REQUIRE);
            }

            pipeline.addAfter(TransportListener.LOG_DEBUG_HANDLER, TransportListener.SSL_HANDLER,
                    builder.build().newHandler(ctx.alloc()));
        }

        // getName should contain the transporter name
        final String logName = logger.getName() + "." + jcd.getId();

        // let's replace the debug logger with something specific to this consumer
        pipeline.replace(TransportListener.LOG_DEBUG_HANDLER, TransportListener.LOG_DEBUG_HANDLER,
                new LoggingHandler(logName + ".server", LogLevel.TRACE));

        // remove dispatcher
        pipeline.replace(this, "server", new TransportServer(logger, cd));

        pipeline.replace(TransportListener.LOG_ERRORS_HANDLER, TransportListener.LOG_ERRORS_HANDLER,
                new LastLoggingHandler(logName + ".errors"));

    }
}
