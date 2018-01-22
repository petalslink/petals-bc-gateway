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

import org.ow2.petals.basisapi.exception.PetalsException;

/**
 * Client/Server interface of the service 'Runtime Administration' part dedicated to the BC Gateway
 * 
 * @author Christophe DENEUX - Linagora
 * 
 */
public interface AdminRuntimeService extends CommonAdminService {

    /**
     * Repropagate endpoints to all consumer domains of a component.
     * 
     * @throws PetalsException
     *             An error occurs during refresh
     */
    public void refreshPropagations() throws PetalsException;

    /**
     * Repropagate endpoints to all consumer domains of a given Service Unit.
     * 
     * @param suName
     *            the name of the SU to refresh
     * @throws PetalsException
     *             An error occurs during refresh
     */
    public void refreshPropagations(String suName) throws PetalsException;

    /**
     * Repropagate endpoints to a given consumer domain.
     * 
     * @param suName
     *            the name of the SU where the consumer domain is declared
     * @param consumerDomain
     *            the name of the consumer domain to refresh
     * @throws PetalsException
     *             An error occurs during refresh
     */
    public void refreshPropagations(String suName, String consumerDomain) throws PetalsException;

    /**
     * Trigger reconnection to all provider domain of the component.
     *
     * @param all
     *            if <code>true</code>, even connected domain will be reconnected.
     * @throws PetalsException
     */
    public void reconnectDomains(boolean force) throws PetalsException;

    /**
     * Trigger reconnection to all provider domain of a given Service Unit.
     *
     * @param suName
     *            the name of the SU to reconnect
     * @param all
     *            if <code>true</code>, even connected domain will be reconnected.
     * @throws PetalsException
     */
    public void reconnectDomains(String suName, boolean force) throws PetalsException;

    /**
     * Trigger reconnection to a given provider domain.
     *
     * @param suName
     *            the name of the SU where the provider domain is declared
     * @param providerDomain
     *            the name of the provider domain to refresh
     * @param all
     *            if <code>true</code>, even connected domain will be reconnected.
     * @throws PetalsException
     */
    public void reconnectDomains(String suName, String providerDomain, boolean force) throws PetalsException;

}
