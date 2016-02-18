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
import javax.jbi.component.ComponentContext;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.JbiGatewayJBISender;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedNewMessage;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.util.ServiceProviderEndpointKey;

import io.netty.bootstrap.Bootstrap;

/**
 * There is one instance of this class per opened connection to a provider partner.
 *
 */
public class ProviderDomain {

    public final JbiProviderDomain jpd;

    private final ComponentContext cc;
    
    private final TransportConnection connection;

    private final Map<ServiceProviderEndpointKey, ServiceKey> mapping = new ConcurrentHashMap<>();

    private final Map<ServiceProviderEndpointKey, ServiceEndpoint> mapping2 = new ConcurrentHashMap<>();

    public ProviderDomain(final ComponentContext cc, final JbiProviderDomain jpd,
            final Collection<Pair<Provides, JbiProvidesConfig>> provides, final JBISender sender,
            final Bootstrap partialBootstrap) {
        this.cc = cc;
        this.jpd = jpd;
        for (final Pair<Provides, JbiProvidesConfig> e : provides) {
            mapping.put(new ServiceProviderEndpointKey(e.getA()), new ServiceKey(e.getB()));
        }
        this.connection = new TransportConnection(sender, this, partialBootstrap);
    }

    /**
     * This corresponds to consumes being declared in the provider domain that we mirror on this side
     */
    public void addedProviderService(final ServiceKey service) {
        try {
            // TODO we absolutely need to provide the wsdl too so that the context can get it!
            // TODO we need to activate that ONLY on init!
            final ServiceEndpoint endpoint = cc.activateEndpoint(service.service, service.endpointName);
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
            cc.deactivateEndpoint(endpoint);
        } catch (final JBIException e) {
            // TODO log exception
        }
    }

    public boolean handle(final ServiceProviderEndpointKey key) {
        return mapping.containsKey(key);
    }

    /**
     * This is used to send to the channel for (1st step) exchanges arriving on JBI
     * 
     * 3rd is taken care of by {@link JbiGatewayJBISender} direcly!
     */
    public void send(final ServiceProviderEndpointKey key, final Exchange exchange) {
        // depending on the key, find the corresponding ServiceKey and send the message
        final ServiceKey service = mapping.get(key);
        if (service != null) {
            final MessageExchange mex = exchange.getMessageExchange();
            assert mex != null;
            final TransportedNewMessage m = new TransportedNewMessage(service, mex);
            this.connection.send(m);
        } else {
            // TODO throw exception (but this should normally not happen...)
        }
    }

    public void disconnect() {
        this.connection.disconnect();
    }

    public void connect() throws InterruptedException {
        this.connection.connect();
    }
}
