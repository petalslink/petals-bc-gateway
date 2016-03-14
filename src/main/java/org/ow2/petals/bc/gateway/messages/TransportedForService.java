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

import org.ow2.petals.bc.gateway.messages.Transported.TransportedToConsumer;
import org.ow2.petals.bc.gateway.messages.Transported.TransportedToProvider;
import org.ow2.petals.commons.log.FlowAttributes;

public abstract class TransportedForService implements TransportedToProvider, TransportedToConsumer {

    private static final long serialVersionUID = 1884695104410740307L;

    /**
     * this identify the consumes that is targeted by the consumer partner
     */
    public final ServiceKey service;

    /**
     * This identifies the exchanges between provider and consumer partners, but not the exchange id of the transported
     * exchange.
     */
    public final String exchangeId;

    /**
     * The attributes of the step handled by the gateway: the idea is that both side will use following steps!
     */
    public final FlowAttributes flowAttributes;

    /**
     * 
     */
    public final int step;

    public final boolean last;

    public TransportedForService(final ServiceKey service, final FlowAttributes flowAttributes,
            final String exchangeId, final int step, final boolean last) {
        assert step > 0;
        this.service = service;
        this.exchangeId = exchangeId;
        this.flowAttributes = flowAttributes;
        this.step = step;
        this.last = last;
    }

    public TransportedForService(final TransportedForService m, final boolean last) {
        this(m.service, m.flowAttributes, m.exchangeId, m.step + 1, last);
        assert !m.last;
        assert m.step > 0;
    }
}
