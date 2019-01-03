/**
 * Copyright (c) 2015-2019 Linagora
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
package org.ow2.petals.bc.gateway.commons.messages;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.commons.messages.Transported.TransportedToConsumer;
import org.ow2.petals.bc.gateway.commons.messages.Transported.TransportedToProvider;
import org.ow2.petals.commons.log.FlowAttributes;

public abstract class TransportedForExchange implements TransportedToProvider, TransportedToConsumer {

    private static final long serialVersionUID = 1884695104410740307L;

    /**
     * This identifies the exchanges between provider and consumer partners, but not the exchange id of the transported
     * exchange.
     */
    public final String exchangeId;

    /**
     * This contains the flow step attributes of the sender: for the PD it is the ProvideExtStep and for the CD it is
     * the ConsumeExtStep.
     * 
     * It is updated before each send
     */
    @Nullable
    public FlowAttributes senderExtStep;

    public TransportedForExchange(final String exchangeId) {
        this.exchangeId = exchangeId;
    }
}
