package org.ow2.petals.bc.gateway.inbound;

import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.component.framework.listener.AbstractListener;

public class JbiGatewaySender extends AbstractListener {

    public JbiGatewaySender(final JbiGatewayComponent component) {
        init(component);
    }
}
