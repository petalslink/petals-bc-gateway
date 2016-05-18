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
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.basisapi.exception.PetalsException;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.utils.BcGatewayJbiHelper;
import org.ow2.petals.binding.gateway.clientserver.api.AdminService;
import org.ow2.petals.component.framework.DefaultBootstrap;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.mbean.MBeanHelper;

/**
 * There is one instance of this class for the whole component. The class is declared in the jbi.xml.
 * 
 * @author vnoel
 *
 */
public class BcGatewayBootstrap extends DefaultBootstrap implements AdminService {

    @Override
    public Collection<String> getMBeanOperationsNames() {
        final Collection<String> methods = super.getMBeanOperationsNames();

        methods.addAll(MBeanHelper.getMethodsNames(AdminService.class));

        return methods;
    }

    /**
     * This will automatically be saved in the jbi.xml by the bootstrap before install of the component!
     */
    @Override
    public void addTransportListener(final @Nullable String id, final int port) throws PetalsException {
        assert id != null;
        try {
            BcGatewayJbiHelper.addTransportListener(id, port, this.getJbiComponentConfiguration().getComponent());
        } catch (final PEtALSCDKException e) {
            final PetalsException ex = new PetalsException(e.getMessage());
            ex.setStackTrace(e.getStackTrace());
            throw ex;
        }
    }

    @Override
    public void setTransportListenerPort(final @Nullable String id, final int port) throws PetalsException {
        assert id != null;
        try {
            BcGatewayJbiHelper.setTransportListenerPort(id, port, this.getJbiComponentConfiguration().getComponent());
        } catch (final PEtALSCDKException e) {
            final PetalsException ex = new PetalsException(e.getMessage());
            ex.setStackTrace(e.getStackTrace());
            throw ex;
        }
    }

    @Override
    public Boolean removeTransportListener(final @Nullable String id) throws PetalsException {
        assert id != null;
        try {
            return BcGatewayJbiHelper.removeTransportListener(id,
                    this.getJbiComponentConfiguration().getComponent()) != null;
        } catch (final PEtALSCDKException e) {
            final PetalsException ex = new PetalsException(e.getMessage());
            ex.setStackTrace(e.getStackTrace());
            throw ex;
        }
    }

    @Override
    public Map<String, Object[]> getTransportListeners() throws PetalsException {
        try {
            final Collection<JbiTransportListener> transportListeners = BcGatewayJbiHelper
                    .getTransportListeners(this.getJbiComponentConfiguration().getComponent());
            final Map<String, Object[]> results = new HashMap<>();
            for (final JbiTransportListener transportListener : transportListeners) {
                results.put(transportListener.getId(),
                        new Object[] { Integer.valueOf(transportListener.getPort()), false, "" });
            }
            return results;
        } catch (final PEtALSCDKException e) {
            final PetalsException ex = new PetalsException(e.getMessage());
            ex.setStackTrace(e.getStackTrace());
            throw ex;
        }
    }

}
