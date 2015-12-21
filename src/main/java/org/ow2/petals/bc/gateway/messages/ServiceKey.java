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
package org.ow2.petals.bc.gateway.messages;

import java.io.Serializable;

import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;

/**
 * Represents a {@link Consumes} that is propagated.
 * 
 * @author vnoel
 *
 */
public class ServiceKey implements Serializable {

    private static final long serialVersionUID = -959719213091759241L;

    @Nullable
    public final String endpointName;

    @Nullable
    public final QName service;

    @Nullable
    public final QName interfaceName;

    public ServiceKey(final @Nullable String endpointName, final @Nullable QName service,
            final @Nullable QName interfaceName) {
        this.endpointName = endpointName;
        this.service = service;
        this.interfaceName = interfaceName;
    }

    public ServiceKey(final Consumes consumes) {
        this(consumes.getEndpointName(), consumes.getServiceName(), consumes.getInterfaceName());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((endpointName == null) ? 0 : endpointName.hashCode());
        result = prime * result + ((interfaceName == null) ? 0 : interfaceName.hashCode());
        result = prime * result + ((service == null) ? 0 : service.hashCode());
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
        if (endpointName == null) {
            if (other.endpointName != null)
                return false;
        } else if (!endpointName.equals(other.endpointName))
            return false;
        if (interfaceName == null) {
            if (other.interfaceName != null)
                return false;
        } else if (!interfaceName.equals(other.interfaceName))
            return false;
        if (service == null) {
            if (other.service != null)
                return false;
        } else if (!service.equals(other.service))
            return false;
        return true;
    }


}