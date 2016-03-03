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

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.junit.Test;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.component.framework.junit.helpers.MessageChecks;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;
import org.ow2.petals.component.framework.junit.impl.message.RequestToProviderMessage;

public class JbiGatewayTestConsumes extends AbstractComponentTest {

    @Test
    public void testConsumesWithInterfaceServiceEndpoint() throws Exception {
        twoDomains(true, true);
    }

    @Test
    public void testConsumesWithInterfaceService() throws Exception {
        twoDomains(true, false);
    }

    @Test
    public void testConsumesWithInterface() throws Exception {
        twoDomains(false, false);
    }

    public void twoDomains(final boolean specifyService, final boolean specifyEndpoint) throws Exception {

        final ServiceEndpoint endpoint = deployTwoDomains(specifyService, specifyEndpoint);

        COMPONENT.sendAndCheckResponseAndSendStatus(
                new RequestToProviderMessage(endpoint.getEndpointName(), endpoint.getServiceName(), null,
                        HELLO_OPERATION, MEPPatternConstants.IN_OUT.value(), IN),
                ServiceProviderImplementation.outMessage(OUT),
                MessageChecks.hasOut().andThen(MessageChecks.hasXmlContent(OUT)), ExchangeStatus.DONE);
    }
}
