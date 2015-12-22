package org.ow2.petals.bc.gateway.outbound;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.messages.TransportedMessage;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainAddedConsumes;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainRemovedConsumes;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class TransportClient extends ChannelInboundHandlerAdapter {

    private final JbiGatewayComponent component;

    private final ProviderDomain pd;

    // TODO we need a logger per connection maybe...
    public TransportClient(final JbiGatewayComponent component, final ProviderDomain pd) {
        this.component = component;
        this.pd = pd;
    }

    @Override
    public void channelActive(final @Nullable ChannelHandlerContext ctx) throws Exception {
        assert ctx != null;
        ctx.writeAndFlush(pd.jpd.authName);
    }

    @Override
    public void channelRead(final @Nullable ChannelHandlerContext ctx, final @Nullable Object msg) throws Exception {
        assert ctx != null;
        assert msg != null;

        if (msg instanceof Exception) {
            // TODO just log it: receiving an exception here means that there is nothing to do, it is just
            // information for us.
        } else if (msg instanceof TransportedMessage) {
            // this can't happen, we are the one sending new exchanges!
            assert !(msg instanceof TransportedNewMessage);
            component.getSender().send(ctx, (TransportedMessage) msg);
        } else if (msg instanceof TransportedToConsumerDomainAddedConsumes) {
            pd.addedProviderService(((TransportedToConsumerDomainAddedConsumes) msg).service);
        } else if (msg instanceof TransportedToConsumerDomainRemovedConsumes) {
            pd.removedProviderService(((TransportedToConsumerDomainRemovedConsumes) msg).service);
        }
    }

}
