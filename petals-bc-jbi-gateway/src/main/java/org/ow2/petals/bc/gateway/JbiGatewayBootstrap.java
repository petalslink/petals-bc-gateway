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
package org.ow2.petals.bc.gateway;

import java.util.Collection;

import org.ow2.petals.basisapi.exception.PetalsException;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.binding.gateway.clientserver.api.AdminService;
import org.ow2.petals.component.framework.DefaultBootstrap;

/**
 * There is one instance of this class for the whole component. The class is declared in the jbi.xml.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayBootstrap extends DefaultBootstrap implements AdminService {

    public static final String METHOD_ADD_TRANSPORT = "addTransportListener";

    public static final String METHOD_REMOVE_TRANSPORT = "removeTransportListener";

    @Override
    public Collection<String> getMBeanOperationsNames() {
        final Collection<String> methods = super.getMBeanOperationsNames();

        methods.add(METHOD_ADD_TRANSPORT);
        methods.add(METHOD_REMOVE_TRANSPORT);

        return methods;
    }

    /**
     * {@inheritDoc}
     * 
     * This will automatically be saved in the jbi.xml by the bootstrap before install of the component!
     */
    @Override
    public void addTransportListener(final String id, final int port) throws PetalsException {
        JbiGatewayJBIHelper.addTransportListener(id, port, getJbiComponentConfiguration().getComponent());
    }

    @Override
    public Boolean removeTransportListener(final String id) throws PetalsException {
        final JbiTransportListener removed = JbiGatewayJBIHelper.removeTransportListener(id,
                getJbiComponentConfiguration().getComponent());
        return removed != null;
    }

}
