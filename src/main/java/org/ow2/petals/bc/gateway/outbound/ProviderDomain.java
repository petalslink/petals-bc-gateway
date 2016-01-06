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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jbi.JBIException;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.ow2.petals.bc.gateway.JbiGatewayComponent;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiProviderDomain;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.util.ServiceProviderEndpointKey;

/**
 * There is one instance of this class per opened connection to a consumer partner.
 *
 * TODO there is a small inconsistency in this architecture because it assumes that provides can be registered AFTER we
 * get new provider services, but it is not true since a {@link ProviderDomain} is associated to only one SU. We should
 * get them through the constructor instead thus!
 * 
 */
public class ProviderDomain {

    public final JbiGatewayComponent component;

    public final JbiProviderDomain jpd;

    private final Map<ServiceProviderEndpointKey, ServiceKey> mapping = new ConcurrentHashMap<>();

    public ProviderDomain(final JbiGatewayComponent component, final JbiProviderDomain jpd) {
        this.component = component;
        this.jpd = jpd;
    }

    public void registerProvides(final Provides provides) {
        final ServiceProviderEndpointKey key = new ServiceProviderEndpointKey(provides);
        // TODO convert string to qname??!
        final ServiceKey service = JbiGatewayJBIHelper.getDeclaredServiceKey(provides);
        mapping.put(key, service);
    }

    public void deregisterProvides(final Provides provides) {
        final ServiceProviderEndpointKey key = new ServiceProviderEndpointKey(provides);
        mapping.remove(key);
    }

    public void addedProviderService(final ServiceKey service) {
        try {
            // TODO we absolutely need to provide the wsdl too so that the context can get it!
            final ServiceEndpoint endpoint = component.getContext().activateEndpoint(service.service,
                    service.endpointName);
            mapping.put(new ServiceProviderEndpointKey(endpoint), service);
        } catch (final JBIException e) {
            // TODO send exception over the channel
        }
    }

    public void removedProviderService(final ServiceKey service) {
        // TODO remove an endpoint
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
