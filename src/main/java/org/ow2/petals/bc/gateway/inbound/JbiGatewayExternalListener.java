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
package org.ow2.petals.bc.gateway.inbound;

import org.ow2.petals.bc.gateway.JbiGatewayComponent.TransportListener;
import org.ow2.petals.bc.gateway.JbiGatewaySUManager;
import org.ow2.petals.bc.gateway.outbound.JbiGatewayJBIListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.ConsumerDomain;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.bc.ExternalListenerManager;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.listener.AbstractExternalListener;

/**
 * There is one instance of this class per {@link Consumes} (accessible with {@link #getConsumes()}). The class is
 * declared in the jbi.xml and instantiated by {@link ExternalListenerManager}.
 * 
 * It only takes care of the messages for the {@link Consumes}, the {@link Provides} are handled by
 * {@link JbiGatewayJBIListener}.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayExternalListener extends AbstractExternalListener {

    private JbiGatewaySUManager suManager;

    public JbiGatewayExternalListener(final JbiGatewaySUManager suManager) {
        this.suManager = suManager;
    }

    @Override
    public void start() throws PEtALSCDKException {
        final ConsumerDomainDispatcher dispatcher = getDispatcher();
        final Consumes consumes = getConsumes();
        assert consumes != null;
        dispatcher.register(consumes);
    }

    @Override
    public void stop() throws PEtALSCDKException {
        final ConsumerDomainDispatcher dispatcher = getDispatcher();
        final Consumes consumes = getConsumes();
        assert consumes != null;
        dispatcher.deregister(consumes);
    }

    private ConsumerDomainDispatcher getDispatcher() throws PEtALSCDKException {
        final String consumerDomain = getExtensions()
                .get(JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN.getLocalPart());
        if (consumerDomain == null) {
            throw new PEtALSCDKException(String.format("Missing %s in Consumes for '%s'",
                    JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN, getConsumes().getServiceName()));
        }
        final ConsumerDomain cd = suManager.getConsumerDomain(consumerDomain);
        if (cd == null) {
            throw new PEtALSCDKException(
                    String.format("No consumer domain was defined in the SU for '%s'", consumerDomain));
        }
        final TransportListener tl = suManager.getComponent().getTransportListener(cd.transport);
        // it can't be null, the SUManager should have checked its existence!
        assert tl != null;
        final ConsumerDomainDispatcher dispatcher = tl.getConsumerDomainDispatcher(cd.authName);
        // it can't be null, the SUManager should have created it!
        assert dispatcher != null;
        return dispatcher;
    }

}
