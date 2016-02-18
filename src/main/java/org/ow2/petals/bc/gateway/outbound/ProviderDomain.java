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
package org.ow2.petals.bc.gateway.outbound;

import java.util.Collection;

import javax.jbi.messaging.MessageExchange;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.JbiGatewayJBISender;
import org.ow2.petals.bc.gateway.inbound.ConsumerAuthenticator;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.api.message.Exchange;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;

/**
 * There is one instance of this class per opened connection to a provider partner.
 *
 */
public class ProviderDomain {

    private final JbiProviderDomain jpd;

    private final ProviderMatcher matcher;

    private final Bootstrap bootstrap;

    @Nullable
    private Channel channel;

    // TODO add a logger
    public ProviderDomain(final ProviderMatcher matcher, final JbiProviderDomain jpd,
            final Collection<JbiProvidesConfig> provides, final JBISender sender, final Bootstrap partialBootstrap)
                    throws PEtALSCDKException {
        this.matcher = matcher;
        this.jpd = jpd;

        for (final JbiProvidesConfig jpc : provides) {
            assert jpc != null;
            matcher.register(new ServiceKey(jpc), this, false);
        }

        final Bootstrap bootstrap = partialBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // This mirror the protocol used in TransporterListener
                final ChannelPipeline p = ch.pipeline();
                p.addLast(new ObjectEncoder());
                p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                p.addLast(new TransportClient(sender, ProviderDomain.this));
            }
        }).remoteAddress(jpd.getIp(), jpd.getPort());
        assert bootstrap != null;
        this.bootstrap = bootstrap;
    }

    /**
     * This corresponds to consumes being declared in the provider domain that we mirror on this side
     */
    public void addedProviderService(final ServiceKey service) {
        try {
            matcher.register(service, this, true);
        } catch (final Exception e) {
            // TODO send exception over the channel
        }
    }

    public void removedProviderService(final ServiceKey service) {
        try {
            matcher.deregister(service);
        } catch (final Exception e) {
            // TODO log exception
        }
    }

    /**
     * This is used to send to the channel for (1st step) exchanges arriving on JBI
     * 
     * 3rd is taken care of by {@link JbiGatewayJBISender} direcly!
     */
    public void send(final ServiceKey service, final Exchange exchange) {
        final MessageExchange mex = exchange.getMessageExchange();
        assert mex != null;
        final TransportedNewMessage m = new TransportedNewMessage(service, mex);
        final Channel channel = this.channel;
        // we can't be disconnected in that case because the component is stopped and we don't process messages!
        assert channel != null;
        channel.writeAndFlush(m);
    }

    public void connect() throws InterruptedException {
        // TODO should I do that async?
        final Channel channel = bootstrap.connect().sync().channel();
        assert channel != null;
        this.channel = channel;
    }

    public void disconnect() {
        final Channel channel = this.channel;
        if (channel != null && channel.isOpen()) {
            // TODO should I do that sync?
            channel.close();
            this.channel = null;
        }
    }

    public void exceptionReceived(final Exception msg) {
        // TODO just log it: receiving an exception here means that there is nothing to do, it is just
        // information for us.
    }

    /**
     * TODO replace that by something complexer, see {@link ConsumerAuthenticator}.
     */
    public String getAuthName() {
        final String authName = jpd.getAuthName();
        assert authName != null;
        return authName;
    }
}
