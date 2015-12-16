/**
 * Copyright (c) 2015 Linagora
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
package org.ow2.petals.bc.gateway.inbound;

import org.ow2.petals.bc.gateway.netty.TransportRequest;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;

public class ConsumerDomainDispatcher {

    public ConsumerDomainDispatcher() {
    }

    public void dispatch(final TransportRequest r) {
        // TODO dispatch based on the concerned consumes

    }

    public void register(final Consumes consumes) {
        // TODO Auto-generated method stub

    }

    public void deregister(final Consumes consumes) {
        // TODO Auto-generated method stub

    }

}
