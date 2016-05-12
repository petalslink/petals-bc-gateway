/**
 * Copyright (c) 2016 Linagora
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
package org.ow2.petals.bc.gateway.outbound;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;

/**
 * TODO add tests related to that!
 * 
 * @author vnoel
 */
public class Service2ProvidesMatcher {

    /**
     * See {@link #validate(Map)} for the conditions it holds.
     */
    private final Map<QName, Map<QName, Map<String, Provides>>> provides = new HashMap<>();

    public Service2ProvidesMatcher(final Collection<Pair<Provides, JbiProvidesConfig>> provides)
            throws PEtALSCDKException {
        for (final Pair<Provides, JbiProvidesConfig> pair : provides) {
            final Provides p = pair.getA();
            final JbiProvidesConfig config = pair.getB();
            assert config != null;
            addToProvides(p, config);
        }

        // ensure there is no bug in the implementation
        assert validate(this.provides);
    }

    private void addToProvides(final Provides p, final JbiProvidesConfig config) throws PEtALSCDKException {
        // can't be null
        final QName interfaceName = config.getProviderInterfaceName();
        Map<QName, Map<String, Provides>> byServices = provides.get(interfaceName);
        if (byServices == null) {
            byServices = new HashMap<>();
            provides.put(interfaceName, byServices);
        }

        // can be null
        final QName serviceName = config.getProviderServiceName();
        // null key is accepted in HashMap, it means any service name here!
        Map<String, Provides> byEndpoints = byServices.get(serviceName);
        if (byEndpoints == null) {
            byEndpoints = new HashMap<>();
            byServices.put(serviceName, byEndpoints);
        }

        // can be null
        final String endpointName = config.getProviderEndpointName();
        // null key is accepted in HashMap, it means any endpoint name here!
        if (byEndpoints.containsKey(endpointName)) {
            // TODO can be detect that earlier maybe in JbiGatewayJBIHelper
            throw new PEtALSCDKException(
                    String.format("Ambiguous provider configuration: duplicate matching service for %s/%s/%s",
                            interfaceName, serviceName, endpointName));
        } else {
            byEndpoints.put(endpointName, p);
        }
    }

    private static boolean validate(final Map<QName, Map<QName, Map<String, Provides>>> provides) {

        // there can't be any interface
        if (provides.containsKey(null)) {
            return false;
        }

        for (final Map<QName, Map<String, Provides>> byServices : provides.values()) {
            final Map<String, Provides> anyServices = byServices.get(null);
            if (anyServices != null) {
                // normally for the any service key, there should only be at most the null key
                if (anyServices.size() > 1) {
                    return false;
                }
                if (anyServices.size() == 1 && !anyServices.containsKey(null)) {
                    return false;
                }
            }
        }

        return true;
    }

    public @Nullable Provides getProvides(final ServiceKey key) {
        final Map<QName, Map<String, Provides>> byServices = this.provides.get(key.interfaceName);
        if (byServices != null) {
            final Map<String, Provides> byEndpoints = byServices.get(key.service);
            if (byEndpoints == null) {
                // if it doesn't match a specific provides, then maybe we have a generic one!
                // Note that there can't be any other key possible than null endpoint for a null service
                final Map<String, Provides> anyServices = byServices.get(null);
                if (anyServices != null) {
                    return anyServices.get(null);
                }
            } else {
                // note: key.service can't be null!
                return byEndpoints.get(key.endpointName);
            }
        }
        return null;
    }
}
