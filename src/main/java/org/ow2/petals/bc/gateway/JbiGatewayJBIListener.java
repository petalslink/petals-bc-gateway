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
package org.ow2.petals.bc.gateway;

import javax.jbi.messaging.MessagingException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JbiGatewayJBISender.JbiGatewaySenderAsyncContext;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.listener.AbstractJBIListener;
import org.ow2.petals.component.framework.process.async.AsyncContext;
import org.ow2.petals.component.framework.util.ServiceProviderEndpointKey;

/**
 * There will be one instance of this class per thread of the component. The class is declared in the jbi.xml.
 * 
 * It takes care of the exchanges for the endpoints dynamically propagated by each provider partner, but also of the
 * answers for the {@link Consumes} (which are sent using the {@link JbiGatewayJBISender}.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayJBIListener extends AbstractJBIListener {

    @Override
    public boolean onJBIMessage(final @Nullable Exchange exchange) {
        assert exchange != null;
        // TODO handle messages to be sent over the wire to a provider domain

        if (exchange.isActiveStatus() && exchange.isProviderRole()) {
            // most of the messages arriving are not for a provides but for one of our dynamically created endpoints
            final ServiceProviderEndpointKey key = new ServiceProviderEndpointKey(exchange.getEndpoint());
            final ProviderDomain pd = getComponent().getProviderDomain(key);
            if (pd != null) {
                // TODO find back the ServiceKey that was received by the consumer partner..
                pd.send(key, exchange);
            } else {
                // TODO this should not happen... it is not for us!
            }
        } else {
            // TODO this should not happen
        }

        return false;
    }

    @Override
    public boolean onAsyncJBIMessage(final @Nullable Exchange asyncExchange,
            final @Nullable AsyncContext asyncContext) {
        assert asyncContext != null;
        assert asyncExchange != null;
        if (asyncContext instanceof JbiGatewaySenderAsyncContext) {
            JbiGatewaySenderAsyncContext context = (JbiGatewaySenderAsyncContext) asyncContext;
            try {
                context.sender.handleAnswer(asyncExchange, context);
            } catch (final MessagingException e) {
                asyncExchange.setError(e);
                return true;
            }
        } else {
            // TODO
        }

        return false;
    }

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }

}
