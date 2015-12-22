package org.ow2.petals.bc.gateway.outbound;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JbiGatewayComponent;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;

/**
 * TODO reconnect??!
 *
 */
public class TransportConnection {

    private final Bootstrap bootstrap;

    @Nullable
    private Channel channel;

    public TransportConnection(final JbiGatewayComponent component, final ProviderDomain pd,
            final Bootstrap partialBootstrap) {
        final Bootstrap bootstrap = partialBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final @Nullable Channel ch) throws Exception {
                assert ch != null;
                // This mirror the protocol used in TransporterListener
                final ChannelPipeline p = ch.pipeline();
                p.addLast(new ObjectEncoder());
                p.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                p.addLast(new TransportClient(component, pd));
            }
        }).remoteAddress(pd.jpd.ip, pd.jpd.port);
        assert bootstrap != null;
        this.bootstrap = bootstrap;
    }

    public void connect() throws InterruptedException {
        final Channel channel = bootstrap.connect().sync().channel();
        assert channel != null;
        this.channel = channel;
    }

    public void disconnect() {
        final Channel channel = this.channel;
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }
}
