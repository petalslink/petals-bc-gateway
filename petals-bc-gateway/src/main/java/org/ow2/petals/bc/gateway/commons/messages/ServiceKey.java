/**
 * Copyright (c) 2015-2021 Linagora
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

import java.io.Serializable;

import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Represents a service that is propagated.
 * 
 * It is used by provider partner to inform consumer partner of available services, and by consumer partner to tell a
 * provider partner to which service a message is addressed
 * 
 * @author vnoel
 *
 */
public class ServiceKey implements Serializable {

    private static final long serialVersionUID = -959719213091759241L;

    @Nullable
    public final String endpointName;

    public final QName service;

    /**
     * Interface name is never null
     */
    public final QName interfaceName;

    public ServiceKey(final @Nullable String endpointName, final QName service, final QName interfaceName) {
        this.endpointName = endpointName;
        this.service = service;
        this.interfaceName = interfaceName;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        final String _endpointName = this.endpointName;
        result = prime * result + ((_endpointName == null) ? 0 : _endpointName.hashCode());
        result = prime * result + ((interfaceName == null) ? 0 : interfaceName.hashCode());
        final QName _service = this.service;
        result = prime * result + ((_service == null) ? 0 : _service.hashCode());
        return result;
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ServiceKey other = (ServiceKey) obj;
        final String _endpointName = this.endpointName;
        if (_endpointName == null) {
            if (other.endpointName != null)
                return false;
        } else if (!_endpointName.equals(other.endpointName))
            return false;
        if (!interfaceName.equals(other.interfaceName))
            return false;
        final QName _service = this.service;
        if (!_service.equals(other.service))
            return false;
        return true;
    }


}