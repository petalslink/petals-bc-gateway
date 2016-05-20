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
 * along with this program/library; If not, see http://www.gnu.org/licenses/
 * for the GNU Lesser General Public License version 2.1.
 */
package org.ow2.petals.bc.gateway;

import javax.jbi.messaging.MessagingException;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.BcGatewayJBISender.BcGatewaySenderAsyncContext;
import org.ow2.petals.bc.gateway.outbound.ProviderService;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.listener.AbstractJBIListener;
import org.ow2.petals.component.framework.process.async.AsyncContext;
import org.ow2.petals.component.framework.util.ServiceEndpointKey;

/**
 * There will be one instance of this class per thread of the component. The class is declared in the jbi.xml.
 * 
 * It takes care of the exchanges for the endpoints dynamically propagated by each provider partner, but also of the
 * answers for the {@link Consumes} (which are sent using the {@link JBISender}.
 * 
 * @author vnoel
 *
 */
public class BcGatewayJBIListener extends AbstractJBIListener {

    @Override
    public boolean onJBIMessage(final @Nullable Exchange exchange) {
        assert exchange != null;

        // the messages arriving are:
        // - either for a provides or for one of our dynamically created endpoints
        // - new exchanges (answers go to onAsyncJBIMessage)
        if (exchange.isActiveStatus() && exchange.isProviderRole()) {
            final ServiceEndpointKey key = new ServiceEndpointKey(exchange.getEndpoint());
            final ProviderService ps = getComponent().matches(key);
            if (ps != null) {
                ps.sendToChannel(exchange);
            } else {
                exchange.setError(new MessagingException("Endpoint '" + key + "' unknown on this component!"));
                return true;
            }
        } else {
            exchange.setError(new MessagingException(
                    "Impossible situation: this component only use sendAsync for handling answers!"));
            return true;
        }

        return false;
    }

    @Override
    public boolean onAsyncJBIMessage(final @Nullable Exchange asyncExchange,
            final @Nullable AsyncContext asyncContext) {
        assert asyncContext != null;
        assert asyncExchange != null;
        if (asyncContext instanceof BcGatewaySenderAsyncContext) {
            final BcGatewaySenderAsyncContext context = (BcGatewaySenderAsyncContext) asyncContext;
            try {
                context.handleAnswer(asyncExchange);
            } catch (final MessagingException e) {
                asyncExchange.setError(e);
                return true;
            }
            return false;
        } else {
            asyncExchange.setError(new MessagingException(
                    "Impossible situation: unknown async context of type " + asyncContext.getClass()));
            return true;
        }
    }

    @Override
    public void onExpiredAsyncJBIMessage(final @Nullable Exchange originalExchange,
            final @Nullable AsyncContext asyncContext) {
        assert asyncContext != null;
        assert originalExchange != null;

        if (asyncContext instanceof BcGatewaySenderAsyncContext) {
            final BcGatewaySenderAsyncContext context = (BcGatewaySenderAsyncContext) asyncContext;
            context.handleTimeout();
        } else {
            getLogger().severe("Impossible situation: unknown async context of type " + asyncContext.getClass()
                    + " for exchange " + originalExchange.getExchangeId());
        }
    }

    @Override
    public BcGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (BcGatewayComponent) component;
    }

}
