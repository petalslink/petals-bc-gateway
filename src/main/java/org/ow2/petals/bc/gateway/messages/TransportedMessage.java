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
package org.ow2.petals.bc.gateway.messages;

import javax.jbi.messaging.MessageExchange;

import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;

/**
 * {@link TransportedMessage}s contains:
 * 
 * The {@link ServiceKey} of the concerned {@link Consumes} from the provider partner (sent to a consumer partner in
 * {@link ConsumerDomain}).
 * 
 * The {@link MessageExchange} of the consumer partner that serves as a container for the exchanged changes. Once sent
 * it is not meant to be reused to be sent in the NMR because its class won't be in the correct classpath.
 * 
 * @author vnoel
 *
 */
public abstract class TransportedMessage extends TransportedForService {

    private static final long serialVersionUID = 7102614527427146536L;

    /**
     * This contains the exchange that one side received via the NMR and that the other side must use to fill its own
     * version of the exchange that he stored (or must create in case of {@link TransportedNewMessage}).
     * 
     * Note: {@link MessageExchange} is not serializable as an interface, but we know its implementation is in Petals.
     */
    @SuppressWarnings("squid:S1948")
    public final MessageExchange exchange;

    public TransportedMessage(final ServiceKey service, final FlowAttributes flowAttributes, final String exchangeId,
            final MessageExchange exchange, final int step) {
        super(service, flowAttributes, exchangeId, step);
        this.exchange = exchange;
    }

    public TransportedMessage(final TransportedMessage m, final MessageExchange exchange) {
        // we keep the service and the exchangeId, they should not change!
        this(m.service, m.flowAttributes, m.exchangeId, exchange, m.step + 1);
        assert m instanceof TransportedNewMessage || m instanceof TransportedMiddleMessage;
    }
}
