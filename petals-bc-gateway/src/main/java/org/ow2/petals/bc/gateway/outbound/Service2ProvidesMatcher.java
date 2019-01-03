/**
 * Copyright (c) 2016-2019 Linagora
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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.utils.BcGatewayJbiHelper.Pair;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;

/**
 * 
 * The specificity of this class is to take care of <code>null</code> endpointName.
 * 
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
            addToProvides(pair.getA(), pair.getB());
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

        // can't be null
        final QName serviceName = config.getProviderServiceName();
        Map<String, Provides> byEndpoints = byServices.get(serviceName);
        if (byEndpoints == null) {
            byEndpoints = new HashMap<>();
            byServices.put(serviceName, byEndpoints);
        }

        // can be null
        final String endpointName = config.getProviderEndpointName();
        // null key is accepted in HashMap, it means any endpoint name here!
        final Provides removed = byEndpoints.put(endpointName, p);
        // this should have been verified at deploy
        assert removed == null;
    }

    private static boolean validate(final Map<QName, Map<QName, Map<String, Provides>>> provides) {

        // there can't be any interface
        if (provides.containsKey(null)) {
            return false;
        }

        for (final Map<QName, Map<String, Provides>> byServices : provides.values()) {
            // there can't be any service
            if (byServices.containsKey(null)) {
                return false;
            }
            for (final Map<String, Provides> byEndpoints : byServices.values()) {
                // if there is the null key, then it should be the only one!
                // normally it should have been checked at deploy
                if (byEndpoints.containsKey(null)) {
                    return byEndpoints.size() == 1;
                }
            }
        }

        return true;
    }

    /**
     * Note: we know that there aren't any overlapping {@link ServiceKey} sent by the provider partner!
     */
    public @Nullable Provides getProvides(final ServiceKey key) {
        final Map<QName, Map<String, Provides>> byServices = this.provides.get(key.interfaceName);
        if (byServices != null) {
            final Map<String, Provides> byEndpoints = byServices.get(key.service);
            if (byEndpoints != null) {
                return byEndpoints.get(key.endpointName);
            }
        }
        return null;
    }
}
