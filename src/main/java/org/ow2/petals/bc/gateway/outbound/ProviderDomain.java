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
package org.ow2.petals.bc.gateway.outbound;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jbi.JBIException;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.util.ServiceProviderEndpointKey;

/**
 * There is one instance of this class per opened connection to a consumer partner.
 *
 */
public class ProviderDomain {

    public final JbiGatewayComponent component;

    public final JbiProviderDomain jpd;

    private final Map<ServiceProviderEndpointKey, ServiceKey> mapping = new ConcurrentHashMap<>();

    private final Map<ServiceProviderEndpointKey, ServiceEndpoint> mapping2 = new ConcurrentHashMap<>();

    public ProviderDomain(final JbiGatewayComponent component, final JbiProviderDomain jpd,
            final Collection<Pair<Provides, JbiProvidesConfig>> provides) {
        this.component = component;
        this.jpd = jpd;
        for (final Pair<Provides, JbiProvidesConfig> e : provides) {
            mapping.put(new ServiceProviderEndpointKey(e.getA()), new ServiceKey(e.getB()));
        }
    }

    /**
     * This corresponds to consumes being declared in the provider domain that we mirror on this side
     */
    public void addedProviderService(final ServiceKey service) {
        try {
            // TODO we absolutely need to provide the wsdl too so that the context can get it!
            // TODO we need to activate that ONLY on init!
            final ServiceEndpoint endpoint = component.getContext().activateEndpoint(service.service,
                    service.endpointName);
            final ServiceProviderEndpointKey key = new ServiceProviderEndpointKey(endpoint);
            mapping.put(key, service);
            mapping2.put(key, endpoint);
        } catch (final JBIException e) {
            // TODO send exception over the channel
        }
    }

    public void removedProviderService(final ServiceKey service) {
        try {
            final ServiceProviderEndpointKey key = new ServiceProviderEndpointKey(service.service,
                    service.endpointName);
            mapping.remove(key);
            final ServiceEndpoint endpoint = mapping2.remove(key);
            component.getContext().deactivateEndpoint(endpoint);
        } catch (final JBIException e) {
            // TODO log exception
        }
    }

    public boolean handle(final ServiceProviderEndpointKey key) {
        return mapping.containsKey(key);
    }

    public void send(final ServiceProviderEndpointKey key, final Exchange exchange) {
        // depending on the key, find the corresponding ServiceKey and send the message
        final ServiceKey service = mapping.get(key);
        if (service != null) {
            final MessageExchange mex = exchange.getMessageExchange();
            assert mex != null;
            final TransportedNewMessage m = new TransportedNewMessage(service, mex);
            // TODO send the exchange over the channel...
        } else {
            // TODO throw exception
        }
    }

}
