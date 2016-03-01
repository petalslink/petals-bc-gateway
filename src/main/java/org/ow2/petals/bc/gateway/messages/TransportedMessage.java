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
     * {@link MessageExchange} is not serializable as an interface, but we know all its implementations are in Petals.
     */
    @SuppressWarnings("squid:S1948")
    public final MessageExchange exchange;

    public TransportedMessage(final ServiceKey service, final MessageExchange exchange) {
        super(service);
        this.exchange = exchange;
    }

    public TransportedMessage(final TransportedMessage m) {
        this(m.service, m.exchange);
        assert m instanceof TransportedNewMessage || m instanceof TransportedMiddleMessage;
    }
}
