/**
 * Copyright (c) 2016-2018 Linagora
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
package org.ow2.petals.binding.gateway.clientserver.api;

import java.util.Map;

import org.ow2.petals.basisapi.exception.PetalsException;

/**
 * MBean operations commons to {@link AdminService} and {@link AdminRuntimeService}.
 * 
 * @author vnoel
 *
 */
public interface CommonAdminService {

    /**
     * Add a new transport listener.
     * 
     * @param id
     *            The transport listener identifier
     * @param port
     *            The port the new transport listener will listen to for incoming requests
     * @throws PetalsException
     *             An error occurs adding the new transport listener
     */
    public void addTransportListener(final String id, final int port) throws PetalsException;

    /**
     * Set the port of a transport listener.
     * 
     * @param id
     *            The transport listener identifier
     * @param port
     *            The port the transport listener should listen to for incoming requests
     * @throws PetalsException
     *             An error occurs setting the port of the transport listener
     */
    public void setTransportListenerPort(final String id, final int port) throws PetalsException;

    /**
     * Remove a transport listener.
     * 
     * @param id
     *            The identifier of the transport listener to remove
     * @throws PetalsException
     *             An error occurs removing the transport listener
     */
    public Boolean removeTransportListener(final String id) throws PetalsException;

    /**
     * @return The transport listeners declared on the current Petals BC Gateway, with, in order in the array: a port
     *         (Integer), a listening status (Boolean) and an error status (String) potentially empty but not null
     * @throws PetalsException
     *             An error occurs getting the transport listeners
     */
    public Map<String, Object[]> getTransportListeners() throws PetalsException;
}
