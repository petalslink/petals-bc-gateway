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
 * along with this program/library; If not, see http://www.gnu.org/licenses/
 * for the GNU Lesser General Public License version 2.1.
 */
package org.ow2.petals.bc.gateway.commons.handlers;

import java.io.Serializable;
import java.util.logging.Logger;

import javax.net.ssl.SSLException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.commons.AbstractDomain;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.utils.BcGatewayJbiHelper.Either;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
import org.ow2.petals.component.framework.util.ServiceUnitUtil;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * This represents the StartTLS protocol used by the BC Gateway:
 * 
 * <ul>
 * <li>client sends an authentification name</li>
 * <li>based on that string, server find the right consumer domain and verify requirements for SSL
 * <ol>
 * <li>if it's ok
 * <ul>
 * <li>a {@link SslHandler} is setup if needed</li>
 * <li>an acceptation is sent to the client
 * <li>the client setup its {@link SslHandler} if needed</li>
 * <li>the ssl handshake takes place</li>
 * <li>when it's done, client and server set up their handler</li>
 * </ul>
 * </li>
 * <li>else it sends an error back to the client and close the connection</li></li>
 * </ol>
 * </ul>
 * 
 * @author vnoel
 *
 */
public class AuthenticatorSSLHandler extends SimpleChannelInboundHandler<AuthenticatorSSLHandler.AuthMessage> {

    /**
     * A function that returns the business logic handler for a given domain
     */
    public interface DomainHandlerBuilder<T extends AbstractDomain> {
        ChannelHandler build(T domain);
    }

    /**
     * A function to find the {@link ConsumerDomain} that corresponds to an auth name.
     * 
     * The implementation must be thread-safe!
     */
    public interface ConsumerAuthenticator {
        @Nullable
        ConsumerDomain authenticate(String authName);
    }

    enum SSLType {
        NONE, SERVER, CLIENTSERVER
    }

    interface AuthMessage extends Serializable {

    }

    static class AuthRequest implements AuthMessage {

        private static final long serialVersionUID = -5247784429778231571L;

        public final String authName;

        public final SSLType sslType;

        public AuthRequest(final String authName, final SSLType sslType) {
            this.authName = authName;
            this.sslType = sslType;
        }
    }

    static class AuthAccept implements AuthMessage {

        private static final long serialVersionUID = 511548153508643571L;

    }

    static class AuthRefuse implements AuthMessage {

        private static final long serialVersionUID = 689355936708210289L;

        public final String message;

        public AuthRefuse(final String message) {
            this.message = message;
        }
    }

    public static class AuthRefuseException extends Exception {

        private static final long serialVersionUID = 2741816195480769061L;

        public AuthRefuseException(final String msg) {
            super(msg);
        }
    }

    private final Either<ProviderDomain, ConsumerAuthenticator> pdOrAuth;

    /**
     * It is not final because in the case of a {@link ConsumerDomain}, we will update it in
     * {@link #channelRead0(ChannelHandlerContext, TransportedAuthentication)}.
     */
    private Logger logger;

    private final DomainHandlerBuilder<AbstractDomain> dhb;

    /**
     * Can be used to know the reason of the close
     */
    private final LazyChannelPromise authenticationFuture = new LazyChannelPromise();

    @Nullable
    private volatile ChannelHandlerContext ctx;

    /**
     * For the client
     */
    public AuthenticatorSSLHandler(final ProviderDomain pd, final Logger logger,
            final DomainHandlerBuilder<ProviderDomain> dhb) {
        this(Either.<ProviderDomain, ConsumerAuthenticator> ofA(pd), logger, dhb);
    }

    /**
     * For the server
     */
    public AuthenticatorSSLHandler(final ConsumerAuthenticator authenticator, final Logger logger,
            final DomainHandlerBuilder<ConsumerDomain> dhb) {
        this(Either.<ProviderDomain, ConsumerAuthenticator> ofB(authenticator), logger, dhb);
    }

    private AuthenticatorSSLHandler(final Either<ProviderDomain, ConsumerAuthenticator> pdOrAuth, final Logger logger,
            final DomainHandlerBuilder<? extends AbstractDomain> dhb) {
        // we need to cheat a bit with generic type, but we are sure that we call it correctly :)
        @SuppressWarnings("unchecked")
        final DomainHandlerBuilder<AbstractDomain> genericDhb = (DomainHandlerBuilder<AbstractDomain>) dhb;
        this.dhb = genericDhb;
        this.pdOrAuth = pdOrAuth;
        this.logger = logger;
    }

    @Override
    public void handlerAdded(final @Nullable ChannelHandlerContext ctx) throws Exception {
        assert ctx != null;
        this.ctx = ctx;

        // if we were added after the channel was active
        if (ctx.channel().isActive()) {
            authenticate(ctx);
        }
    }

    @Override
    public void channelActive(final @Nullable ChannelHandlerContext ctx) throws Exception {
        assert ctx != null;

        authenticate(ctx);

        // maybe another handler needs to know about this
        ctx.fireChannelActive();
    }

    public Future<Channel> authenticationFuture() {
        return authenticationFuture;
    }

    private void authenticate(final ChannelHandlerContext ctx) {
        // if we are client, we initiate the authentication process
        if (pdOrAuth.isA()) {
            final JbiProviderDomain jpd = pdOrAuth.getA().getJPD();

            final String remoteCertificate = jpd.getRemoteCertificate();
            final String certificate = jpd.getCertificate();
            final String key = jpd.getKey();

            final boolean clientCert = certificate != null && key != null;
            final boolean serverCert = remoteCertificate != null;

            // let's announce ourselves
            final String authName = jpd.getRemoteAuthName();
            assert authName != null;
            final AuthRequest msg = new AuthRequest(authName,
                    clientCert ? SSLType.CLIENTSERVER : (serverCert ? SSLType.SERVER : SSLType.NONE));

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Sending an AuthRequest (" + ctx.channel().remoteAddress() + ") for auth name "
                        + msg.authName + " and type " + msg.sslType);
            }

            ctx.writeAndFlush(msg);
        }
    }

    @Override
    protected void channelRead0(final @Nullable ChannelHandlerContext ctx, final @Nullable AuthMessage msg)
            throws Exception {

        assert ctx != null;
        assert msg != null;

        if (pdOrAuth.isB() && msg instanceof AuthRequest) {
            final AuthRequest req = (AuthRequest) msg;

            // the server receive the Request and must answer it with Accept or Refuse
            // then it can setup the handlers (ssl or not, and the domain handler)
            final ConsumerAuthenticator authenticator = pdOrAuth.getB();

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Received (" + ctx.channel().remoteAddress() + ") an AuthRequest for auth name "
                        + req.authName + " and type " + req.sslType);
            }

            // it validates that the current transport can be used for this consumer partner
            final ConsumerDomain cd = authenticator.authenticate(req.authName);

            if (cd == null) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Sending (" + ctx.channel().remoteAddress()
                            + ") an AuthRefuse because I don't recognise the auth name " + req.authName);
                }
                final String m = "unknown auth-name '" + req.authName + "'";
                ctx.writeAndFlush(new AuthRefuse(m));
                authenticationFuture.setFailure(new AuthRefuseException(m));
                return;
            }

            // let's update the logger by inserting the consumer domain name in it
            logger = Logger.getLogger(logger.getName() + "." + cd.getId());

            final JbiConsumerDomain jcd = cd.getJCD();
            final String certificate = jcd.getCertificate();
            final String remoteCertificate = jcd.getRemoteCertificate();

            boolean serverCert = certificate != null;
            boolean clientCert = remoteCertificate != null;

            String error = null;
            if (!serverCert) {
                if (req.sslType != SSLType.NONE) {
                    error = "expecting a server certificate";
                }
            } else if (!clientCert) {
                if (req.sslType == SSLType.NONE) {
                    error = "expecting a server certificate";
                } else if (req.sslType == SSLType.CLIENTSERVER) {
                    error = "expecting only a server certificate";
                }
            } else {
                if (req.sslType != SSLType.CLIENTSERVER) {
                    error = "expecting server and client certificates";
                }
            }

            if (error != null) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Sending (" + ctx.channel().remoteAddress() + ") an AuthRefuse because I'm " + error);
                }
                ctx.writeAndFlush(new AuthRefuse(error));
                authenticationFuture.setFailure(new AuthRefuseException(error));
            } else {
                setUpSslHandlers(ctx, cd, certificate, jcd.getKey(), jcd.getPassphrase(), remoteCertificate);
            }
        } else if (pdOrAuth.isA() && msg instanceof AuthAccept) {
            // if the client receives an accept, it can setup the handlers (ssl or not, and domain handler)

            final ProviderDomain pd = pdOrAuth.getA();

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Received (" + ctx.channel().remoteAddress() + ") an AuthAccept");
            }

            final JbiProviderDomain jpd = pd.getJPD();

            setUpSslHandlers(ctx, pd, jpd.getCertificate(), jpd.getKey(), jpd.getPassphrase(), jpd.getRemoteCertificate());

        } else if (pdOrAuth.isA() && msg instanceof AuthRefuse) {
            // if the client receives a refuse, it simply notifies the future
            this.authenticationFuture.setFailure(new AuthRefuseException(((AuthRefuse) msg).message));
        } else {
            throw new IllegalArgumentException(
                    "Impossible case: client=" + pdOrAuth.isA() + " and msg type is " + msg.getClass().getName());
        }
    }

    private void setUpSslHandlers(final ChannelHandlerContext ctx, final AbstractDomain domain,
            final @Nullable String certificate, final @Nullable String key, final @Nullable String passphrase,
            final @Nullable String remoteCertificate) throws SSLException {

        // TODO could we use certificate only for auth and not encryption?
        // TODO support openssl
        final SslHandler sslHandler;
        if (pdOrAuth.isB() && certificate != null && key != null) {
            // server side ssl, do not forget startTls so that our accept can be sent after the handler is added

            final ServiceUnitDataHandler handler = domain.getSUHandler();

            final SslContextBuilder builder = SslContextBuilder
                    .forServer(ServiceUnitUtil.getFile(handler.getInstallRoot(), certificate),
                            ServiceUnitUtil.getFile(handler.getInstallRoot(), key), passphrase)
                    .sslProvider(SslProvider.JDK).ciphers(null, IdentityCipherSuiteFilter.INSTANCE).sessionCacheSize(0)
                    .sessionTimeout(0);

            if (remoteCertificate != null) {
                builder.trustManager(ServiceUnitUtil.getFile(handler.getInstallRoot(), remoteCertificate))
                        .clientAuth(ClientAuth.REQUIRE);
            }

            // until https://github.com/netty/netty/issues/5170 is accepted
            // we need to create the handler by hand
            sslHandler = new SslHandler(builder.build().newEngine(ctx.alloc()), true);
        } else if (pdOrAuth.isA() && remoteCertificate != null) {
            // client side

            final String installRoot = domain.getSUHandler().getInstallRoot();
            final SslContextBuilder builder = SslContextBuilder.forClient().sslProvider(SslProvider.JDK)
                    .trustManager(ServiceUnitUtil.getFile(installRoot, remoteCertificate))
                    .ciphers(null, IdentityCipherSuiteFilter.INSTANCE).sessionCacheSize(0).sessionTimeout(0);

            if (certificate != null && key != null) {
                builder.keyManager(ServiceUnitUtil.getFile(installRoot, certificate),
                        ServiceUnitUtil.getFile(installRoot, key), passphrase);
            }

            sslHandler = builder.build().newHandler(ctx.alloc());
        } else {
            sslHandler = null;
        }

        // For a server, it contains the transporter name and the consumer domain name (it was updated in channelRead0)
        // For a client, it contains the provider domain name (it was set by the component)
        final String logName = logger.getName();

        // let's replace the debug logger with something specific to this consumer
        ctx.pipeline().replace(HandlerConstants.LOG_DEBUG_HANDLER, HandlerConstants.LOG_DEBUG_HANDLER,
                new LoggingHandler(logName, LogLevel.TRACE));

        ctx.pipeline().replace(HandlerConstants.LOG_ERRORS_HANDLER, HandlerConstants.LOG_ERRORS_HANDLER,
                new LastLoggingHandler(logName + ".errors"));

        if (sslHandler != null) {
            // if there is a sslHandler, then we can only add the domain handler after the handshake is finished
            // if not we risk sending things too early in it

            sslHandler.handshakeFuture().addListener(new FutureListener<Channel>() {
                @Override
                public void operationComplete(final @Nullable Future<Channel> future) throws Exception {
                    assert future != null;
                    if (!future.isSuccess()) {
                        authenticationFuture.setFailure(future.cause());
                    } else {
                        // I must keep the handler here until now in case there is an exception so that I can log it
                        ctx.pipeline().replace(HandlerConstants.DOMAIN_HANDLER, HandlerConstants.DOMAIN_HANDLER,
                                dhb.build(domain));
                        authenticationFuture.setSuccess(ctx.channel());
                    }
                }
            });

            ctx.pipeline().addAfter(HandlerConstants.LOG_DEBUG_HANDLER, HandlerConstants.SSL_HANDLER, sslHandler);
        }

        if (pdOrAuth.isB()) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Sending an Accept (" + ctx.channel().remoteAddress() + ")");
            }

            // this must be sent after the ssh handler is replaced (when using ssl) so that we are ready to receive ssl data right away
            // but this must be sent before the domain handler is replaced (when not using ssl), because it will send
            // data and it must arrive AFTER our Accept
            ctx.writeAndFlush(new AuthAccept());
        }

        // else it is done in the FutureListener
        if (sslHandler == null) {
            ctx.pipeline().replace(HandlerConstants.DOMAIN_HANDLER, HandlerConstants.DOMAIN_HANDLER, dhb.build(domain));
            authenticationFuture.setSuccess(ctx.channel());
        }
    }

    /**
     * inspired from {@link SslHandler}
     */
    private final class LazyChannelPromise extends DefaultPromise<Channel> {

        @Override
        protected EventExecutor executor() {
            final ChannelHandlerContext ctx2 = ctx;
            if (ctx2 != null) {
                return ctx2.executor();
            } else {
                throw new IllegalStateException();
            }
        }

        @Override
        protected void checkDeadLock() {
            if (ctx == null) {
                // If ctx is null the handlerAdded(...) callback was not called, in this case the checkDeadLock()
                // method was called from another Thread then the one that is used by ctx.executor(). We need to
                // guard against this as a user can see a race if handshakeFuture().sync() is called but the
                // handlerAdded(..) method was not yet as it is called from the EventExecutor of the
                // ChannelHandlerContext. If we not guard against this super.checkDeadLock() would cause an
                // IllegalStateException when trying to call executor().
                return;
            }
            super.checkDeadLock();
        }
    }
}
