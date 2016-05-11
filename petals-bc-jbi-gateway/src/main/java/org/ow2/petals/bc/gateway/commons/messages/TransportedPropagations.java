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
package org.ow2.petals.bc.gateway.commons.messages;

import java.util.Collections;
import java.util.Map;

import org.ow2.petals.bc.gateway.commons.messages.Transported.TransportedToConsumer;

public class TransportedPropagations implements TransportedToConsumer {

    @SuppressWarnings("null")
    public static final TransportedPropagations EMPTY = new TransportedPropagations(
            Collections.<ServiceKey, TransportedDocument> emptyMap());

    private static final long serialVersionUID = -2818905233890110391L;

    private final Map<ServiceKey, TransportedDocument> propagations;

    public TransportedPropagations(final Map<ServiceKey, TransportedDocument> propagations) {
        this.propagations = propagations;
    }

    public Map<ServiceKey, TransportedDocument> getPropagations() {
        return propagations;
    }
}
