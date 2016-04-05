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
public class TransportedMessage extends TransportedForExchange {

    private static final long serialVersionUID = 7102614527427146536L;

    /**
     * this identify the consumes that is targeted by the consumer partner
     */
    public final ServiceKey service;

    /**
     * the step of the exchange (starts with 1)
     */
    public final int step;

    /**
     * marker to denote that this message is the last one
     */
    public final boolean last;

    /**
     * This contains the exchange that one side received via the NMR and that the other side must use to fill its own
     * version of the exchange that he stored (or must create in case of {@link TransportedNewMessage}).
     * 
     * Note: {@link MessageExchange} is not serializable as an interface, but we know its implementation is in Petals.
     */
    @SuppressWarnings("squid:S1948")
    public final MessageExchange exchange;

    protected TransportedMessage(final ServiceKey service, final FlowAttributes previous, final FlowAttributes current,
            final String exchangeId, final MessageExchange exchange, final int step, final boolean last) {
        super(previous, current, exchangeId);
        assert step > 0;
        this.service = service;
        this.step = step;
        this.last = last;
        this.exchange = exchange;
    }

    protected TransportedMessage(final TransportedMessage m, final boolean last, final MessageExchange exchange) {
        this(m.service, m.previous, m.current, m.exchangeId, exchange, m.step + 1, last);
        assert !m.last;
        assert m.step > 0;
    }

    public static TransportedMessage newMessage(final ServiceKey service, final FlowAttributes previous,
            final FlowAttributes current, final MessageExchange exchange) {
        return new TransportedMessage(service, previous, current, exchange.getExchangeId(), exchange, 1, false);
    }

    public static TransportedMessage middleMessage(final TransportedMessage m, final MessageExchange exchange) {
        return new TransportedMessage(m, false, exchange);
    }

    public static TransportedMessage lastMessage(final TransportedMessage m, final MessageExchange exchange) {
        return new TransportedMessage(m, true, exchange);
    }
}
