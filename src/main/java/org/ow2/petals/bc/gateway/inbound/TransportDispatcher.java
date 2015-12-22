package org.ow2.petals.bc.gateway.inbound;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiTransportListener;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

/**
 * Dispatch a connection to the correct {@link ConsumerDomain}.
 * 
 * @author vnoel
 *
 */
@Sharable
public class TransportDispatcher extends ChannelInboundHandlerAdapter {

    private final JbiGatewayComponent component;

    private final JbiTransportListener jtl;

    public TransportDispatcher(final JbiGatewayComponent component, final JbiTransportListener jtl) {
        this.component = component;
        this.jtl = jtl;
    }

    @Override
    public void channelRead(final @Nullable ChannelHandlerContext ctx, final @Nullable Object msg) throws Exception {
        assert msg != null;
        assert ctx != null;

        if (!(msg instanceof String)) {
            // TODO replace that with an exception!
            ctx.writeAndFlush("Unexpected content");
            // TODO is that all I have to do??
            ctx.close();
            return;
        }

        // TODO test that too?
        final String consumerAuthName = (String) msg;

        // this corresponds to authenticating the consumer partner
        // TODO make that better
        final ConsumerDomain cd = component.getServiceUnitManager().getConsumerDomain(consumerAuthName);

        // accept corresponds to validate that the current transport can be used for this consumer partner
        if (cd == null || !cd.accept(jtl.id)) {
            // TODO replace that with an exception!
            ctx.writeAndFlush(String.format("Unauthorised %s '%s",
                    JbiGatewayJBIHelper.EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME.getLocalPart(), consumerAuthName));
            // TODO is that all I have to do??
            ctx.close();
            return;
        }

        final ChannelPipeline p = ctx.pipeline();
        p.remove(this);
        p.addLast(new TransportServer(cd));

    }
}
