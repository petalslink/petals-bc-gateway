/**
 * Copyright (c) 2016-2024 Linagora
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
package org.ow2.petals.bc.gateway.outbound;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.util.ServiceEndpointKey;
import org.w3c.dom.Document;

public interface ProviderMatcher {

    @Nullable
    ProviderService matches(ServiceEndpointKey key);

    void register(ServiceEndpointKey key, ProviderService ps, Document description) throws PEtALSCDKException;

    void register(ServiceEndpointKey key, ProviderService ps) throws PEtALSCDKException;

    /**
     * @return <code>true</code> if it was actually deregistered
     * @throws PEtALSCDKException
     *             if there was an error during deregistration
     */
    boolean deregister(ServiceEndpointKey sk) throws PEtALSCDKException;
}
