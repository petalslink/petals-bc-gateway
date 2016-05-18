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

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.awaitility.Awaitility.to;
import static com.jayway.awaitility.Duration.TWO_SECONDS;
import static org.hamcrest.Matchers.equalTo;

import javax.jbi.servicedesc.ServiceEndpoint;

import org.junit.Test;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.component.framework.junit.ResponseMessage;
import org.ow2.petals.component.framework.junit.helpers.MessageChecks;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;

public class BcGatewayLifecyclesTest extends AbstractComponentTest {

    @Test
    public void startAndStop() throws Exception {

        assertTrue(COMPONENT_UNDER_TEST.isInstalled());
        assertTrue(COMPONENT_UNDER_TEST.isStarted());

        // used by TEST_TRANSPORT_NAME
        assertNotAvailable(TEST_TRANSPORT_PORT);
        // used by TEST_TRANSPORT2_NAME
        assertNotAvailable(DEFAULT_PORT);

        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME, createHelloConsumes(true, true));

        assertTrue(COMPONENT_UNDER_TEST.isServiceDeployed(SU_CONSUMER_NAME));
    }

    @Test
    public void testNotNewExchangeAfterShutdownConsumer() throws Exception {
        testNotNewExchangeAfterShutdown(true, false);
    }

    @Test
    public void testNotNewExchangeAfterShutdownProvider() throws Exception {
        testNotNewExchangeAfterShutdown(false, true);

    }

    @Test
    public void testNotNewExchangeAfterShutdownBoth() throws Exception {
        testNotNewExchangeAfterShutdown(true, true);
    }

    public void testNotNewExchangeAfterShutdown(final boolean stopConsumer, final boolean stopProvider)
            throws Exception {

        final ServiceEndpoint endpoint = deployTwoDomains();

        final ServiceProviderImplementation provider = ServiceProviderImplementation.outMessage(OUT);

        COMPONENT_UNDER_TEST.pushRequestToProvider(helloRequest(endpoint, MEPPatternConstants.IN_OUT.value()));
        
        // let's wait for the request to be on the service provider side
        await().atMost(TWO_SECONDS).untilCall(to(COMPONENT_UNDER_TEST).getRequestsFromConsumerCount(),
                equalTo(1));

        if (stopProvider) {
            COMPONENT_UNDER_TEST.stopService(SU_PROVIDER_NAME);
            COMPONENT_UNDER_TEST.shutdownService(SU_PROVIDER_NAME);
        }
        if (stopConsumer) {
            COMPONENT_UNDER_TEST.stopService(SU_CONSUMER_NAME);
            COMPONENT_UNDER_TEST.shutdownService(SU_CONSUMER_NAME);
        }

        COMPONENT.receiveResponseAsExternalProvider(provider, false);

        final ResponseMessage response = COMPONENT_UNDER_TEST
                .pollResponseFromProvider(DEFAULT_TIMEOUT_FOR_COMPONENT_SEND);

        MessageChecks.hasOut().andThen(MessageChecks.hasXmlContent(OUT)).checks(response);

        COMPONENT.sendDoneStatus(response, provider);
    }
}
