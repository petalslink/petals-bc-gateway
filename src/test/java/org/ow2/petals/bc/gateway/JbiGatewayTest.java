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

import java.util.concurrent.Callable;

import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.Ignore;
import org.junit.Test;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;
import org.ow2.petals.component.framework.junit.impl.message.RequestToProviderMessage;
import org.ow2.petals.component.framework.junit.impl.mock.MockComponentContext;

import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;

public class JbiGatewayTest extends AbstractComponentTest {

    @Test
    public void startAndStop() throws Exception {

        assertTrue(COMPONENT_UNDER_TEST.isInstalled());
        assertTrue(COMPONENT_UNDER_TEST.isStarted());

        assertFalse(available(TEST_TRANSPORT_PORT));
        assertFalse(available(DEFAULT_PORT));

        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME, createHelloConsumes(true, true));

        assertTrue(COMPONENT_UNDER_TEST.isServiceDeployed(SU_CONSUMER_NAME));

    }

    @Test
    public void twoDomains1() throws Exception {
        twoDomains(true, true);
    }

    @Test
    public void twoDomains2() throws Exception {
        twoDomains(true, false);
    }

    @Test
    @Ignore("This can't work, the MockComponentContext is too limited to handle this case")
    public void twoDomains3() throws Exception {
        twoDomains(false, false);
    }

    public void twoDomains(final boolean specifyService, final boolean specifyEndpoint) throws Exception {
        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME, createHelloConsumes(specifyService, specifyEndpoint));

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME, createHelloProvider());
        
        Awaitility.await().atMost(Duration.ONE_SECOND).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return getNotExternalEndpoint(specifyService) != null;
            }
        });

        final ServiceEndpoint endpoint = getNotExternalEndpoint(specifyService);
        assert endpoint != null;

        COMPONENT.sendAndGetResponse(
                new RequestToProviderMessage(endpoint.getEndpointName(), endpoint.getServiceName(), null,
                        HELLO_OPERATION, MEPPatternConstants.IN_OUT.value(), "<a/>"),
                ServiceProviderImplementation.outMessage("<b/>"));
    }

    private static @Nullable ServiceEndpoint getNotExternalEndpoint(final boolean specifyService) {

        final QName service;
        if (specifyService) {
            service = HELLO_SERVICE;
        } else {
            service = new QName(HELLO_INTERFACE.getNamespaceURI(), HELLO_INTERFACE.getLocalPart() + "GeneratedService");
        }

        for (final ServiceEndpoint endpoint : MockComponentContext.resolveEndpointsForService(service)) {
            if (!endpoint.getEndpointName().equals(EXTERNAL_HELLO_ENDPOINT)) {
                return endpoint;
            }
        }

        return null;
    }
}
