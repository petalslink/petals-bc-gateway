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
package org.ow2.petals.bc.gateway.outbound;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomainDispatcher.ConsumerDomainAsyncContext;
import org.ow2.petals.bc.gateway.inbound.JbiGatewaySender;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.listener.AbstractJBIListener;
import org.ow2.petals.component.framework.process.async.AsyncContext;

/**
 * There will be one instance of this class per thread of the component. The class is declared in the jbi.xml.
 * 
 * It takes care of the exchanges for the {@link Provides}, but also of the answers for the {@link Consumes} (which are
 * sent using the {@link JbiGatewaySender}.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayJBIListener extends AbstractJBIListener {

    @Override
    public boolean onJBIMessage(final @Nullable Exchange exchange) {
        // TODO handle messages to be sent over the wire to a provider domain
        return false;
    }

    @Override
    public boolean onAsyncJBIMessage(final @Nullable Exchange asyncExchange,
            final @Nullable AsyncContext asyncContext) {
        assert asyncContext != null;
        assert asyncExchange != null;
        if (asyncContext instanceof ConsumerDomainAsyncContext) {
            ConsumerDomainAsyncContext context = (ConsumerDomainAsyncContext) asyncContext;
            context.cd.handleAnswer(asyncExchange, context);
        } else {
            // TODO
        }

        return false;
    }

}
