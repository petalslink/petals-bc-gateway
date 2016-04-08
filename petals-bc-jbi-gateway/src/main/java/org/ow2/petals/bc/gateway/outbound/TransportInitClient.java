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
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.messages.TransportedAuthentication;
import org.ow2.petals.bc.gateway.messages.TransportedPropagatedConsumesList;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.commons.log.Level;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCountUtil;

public class TransportInitClient extends ChannelInboundHandlerAdapter {

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

        final JbiProviderDomain jpd = pd.getJPD();

        ctx.writeAndFlush(new TransportedAuthentication(jpd.getRemoteAuthName()));

        // TODO is that enough to do that here to ensure that anything arriving after I sent the authentication will be
        // treated by SSL?
        final String remoteCertificate = jpd.getRemoteCertificate();
        if (remoteCertificate != null) {
            final SslContextBuilder builder = SslContextBuilder.forClient().sslProvider(SslProvider.JDK)
                    .trustManager(JbiGatewayJBIHelper.getFile(remoteCertificate))
                    .ciphers(null, IdentityCipherSuiteFilter.INSTANCE).sessionCacheSize(0).sessionTimeout(0);

            final String certificate = jpd.getCertificate();
            final String key = jpd.getKey();
            if (certificate != null && key != null) {
                builder.keyManager(JbiGatewayJBIHelper.getFile(certificate), JbiGatewayJBIHelper.getFile(key),
                        jpd.getPassphrase());
            }

            ctx.pipeline().addAfter(ProviderDomain.LOG_DEBUG_HANDLER, ProviderDomain.SSL_HANDLER,
                    builder.build().newHandler(ctx.alloc()));
        }
    }

    @Override
    public void channelRead(final @Nullable ChannelHandlerContext ctx, final @Nullable Object msg) throws Exception {
        assert msg != null;
        assert ctx != null;

        boolean release = true;
        try {
            if (msg instanceof TransportedPropagatedConsumesList) {
                // use replace because we want the logger to be last
                ctx.pipeline().replace(this, "client", new TransportClient(logger, pd));
                release = false;
                // it will be taken care of by the newly instantiated client!
                ctx.fireChannelRead(msg);
            } else if (msg instanceof String) {
                logger.severe("Authentication failed: " + msg);
                // TODO should we do something else such as marking the SU as disabled or something like that?!
                ctx.close();
            } else {
                throw new IllegalArgumentException("Impossible");
            }
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }
}
