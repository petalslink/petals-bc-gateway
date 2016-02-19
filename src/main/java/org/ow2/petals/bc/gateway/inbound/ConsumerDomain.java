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
package org.ow2.petals.bc.gateway.inbound;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.jbi.JBIException;
import javax.jbi.component.ComponentContext;
import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.bc.gateway.messages.TransportedToConsumerDomainAddedConsumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.w3c.dom.Document;

import io.netty.channel.ChannelHandlerContext;

/**
 * There is one instance of this class per consumer domain in an SU configuration (jbi.xml).
 * 
 * It is responsible of notifying the channels (to consumer partner) of existing Consumes propagated to them.
 * 
 * The main idea is that a given consumer partner can contact us (a provider partner) with multiple connections (for
 * example in case of HA) and each of these needs to know what are the consumes propagated to them.
 * 
 * TODO how do we close this consumer domain?
 * 
 * @author vnoel
 *
 */
public class ConsumerDomain {

    /**
     * The keys of the {@link Consumes} propagated to this consumer domain.
     */
    private final Map<ServiceKey, Consumes> services = new HashMap<>();

    /**
     * The channels from this consumer domain (there can be more than one in case of HA or stuffs like that for example)
     * 
     * TODO store a name or something to identify it and log stuffs
     */
    @SuppressWarnings("null")
    private final Set<ChannelHandlerContext> channels = Collections
            .newSetFromMap(new ConcurrentHashMap<ChannelHandlerContext, Boolean>());

    public final JbiConsumerDomain jcd;

    private final ComponentContext cc;

    // TODO add a logger
    public ConsumerDomain(final ComponentContext cc, final JbiConsumerDomain jcd, final Collection<Consumes> consumes) {
        this.cc = cc;
        this.jcd = jcd;
        for (final Consumes c : consumes) {
            assert c != null;
            services.put(new ServiceKey(c), c);
        }
    }

    public void registerChannel(final ChannelHandlerContext ctx) {
        channels.add(ctx);
        for (final Entry<ServiceKey, Consumes> entry : services.entrySet()) {
            notifyService(ctx, entry.getKey(), entry.getValue());
        }
        ctx.flush();
    }

    /**
     * TODO reexecute if desc was missing before...?
     */
    private void notifyService(final ChannelHandlerContext ctx, final ServiceKey service, final Consumes consumes) {
        // TODO cache the description
        final Document desc = getDescription(consumes);
        ctx.write(new TransportedToConsumerDomainAddedConsumes(service, desc));
    }

    /**
     * This will return the first {@link Document} that is non-null on a {@link ServiceEndpoint} that matches the
     * {@link Consumes}.
     * 
     * TODO maybe factor that into CDK?
     */
    private @Nullable Document getDescription(final Consumes consumes) {
        final String endpointName = consumes.getEndpointName();
        final QName serviceName = consumes.getServiceName();
        final QName interfaceName = consumes.getInterfaceName();
        final ServiceEndpoint[] endpoints;
        if (endpointName != null && serviceName != null) {
            final ServiceEndpoint endpoint = cc.getEndpoint(serviceName, endpointName);
            if (endpoint != null && (interfaceName == null || matches(endpoint, interfaceName))) {
                endpoints = new ServiceEndpoint[] { endpoint };
            } else {
                // TODO log
                endpoints = new ServiceEndpoint[0];
            }
        } else if (serviceName != null) {
            final ServiceEndpoint[] preMatch = cc.getEndpointsForService(serviceName);
            if (interfaceName != null) {
                final List<ServiceEndpoint> matched = new ArrayList<>();
                for (final ServiceEndpoint endpoint : preMatch) {
                    assert endpoint != null;
                    if (matches(endpoint, interfaceName)) {
                        matched.add(endpoint);
                    }
                }
                endpoints = matched.toArray(new ServiceEndpoint[matched.size()]);
            } else {
                endpoints = preMatch;
            }
        } else {
            endpoints = cc.getEndpoints(interfaceName);
        }
        for (final ServiceEndpoint endpoint : endpoints) {
            try {
                Document desc = cc.getEndpointDescriptor(endpoint);
                if (desc != null) {
                    return desc;
                }
            } catch (final JBIException e) {
                // TODO log but should not happen normally!
            }
        }
        return null;
    }

    private boolean matches(final ServiceEndpoint endpoint, final QName interfaceName) {
        for (final QName itf : endpoint.getInterfaces()) {
            if (interfaceName.equals(itf)) {
                return true;
            }
        }
        return false;
    }

    public void deregisterChannel(final ChannelHandlerContext ctx) {
        channels.remove(ctx);
    }

    public void exceptionReceived(final ChannelHandlerContext ctx, final Exception msg) {
        // TODO just print it: receiving an exception here means that there is nothing to do, it is just
        // information for us.
    }
}
