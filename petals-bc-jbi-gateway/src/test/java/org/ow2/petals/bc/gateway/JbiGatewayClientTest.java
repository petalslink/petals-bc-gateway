/**
 * Copyright (c) 2016 Linagora
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

import org.junit.Test;

public class JbiGatewayClientTest extends AbstractComponentTest {

    @Test
    public void testCantConnect() throws Exception {
        final int port = 1234;

        // there is no listener on this one
        assertAvailable(port);

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME, createHelloProvider(TEST_AUTH_NAME, port));

        assertLogContains("Can't connect to provider domain " + TEST_PROVIDER_DOMAIN);
    }

    @Test
    public void testCantAuthNoCD() throws Exception {
        final String id = "default";
        final int port = 1234;
        
        // there is no listener on this one
        assertAvailable(port);

        addTransportListener(id, port);

        assertNotAvailable(port);

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME, createHelloProvider(TEST_AUTH_NAME, port));

        assertLogContains("unknown auth-name '" + TEST_AUTH_NAME + "'");

        removeTransportListener(id);
    }

    @Test
    public void testCantAuthWrongCD() throws Exception {
        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME, createHelloConsumes(true, true));

        final String authName = "INCORRECT";
        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME, createHelloProvider(authName, TEST_TRANSPORT_PORT));

        assertLogContains("unknown auth-name '" + authName + "'");
        // TODO we should also test that the connection is closed!
    }
}
