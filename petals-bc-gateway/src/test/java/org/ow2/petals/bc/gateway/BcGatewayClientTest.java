/**
 * Copyright (c) 2016-2025 Linagora
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

import static org.ow2.petals.bc.gateway.junit.extensions.api.EnsurePortsAreOK.assertAvailable;
import static org.ow2.petals.bc.gateway.junit.extensions.api.EnsurePortsAreOK.assertNotAvailable;

import java.net.ConnectException;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

public class BcGatewayClientTest extends AbstractComponentTest {

    @Test
    public void testCantConnect() throws Exception {
        final int port = 1234;

        // there is no listener on this one
        assertAvailable(port);

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME,
                createProvider(TEST_AUTH_NAME, port, null, null, null, null, null, null, 0, 0L));

        assertLogContains("Connection to provider domain " + TEST_PROVIDER_DOMAIN + " failed", Level.SEVERE,
                ConnectException.class);
    }

    @Test
    public void testCantConnectRetry() throws Exception {
        final int port = 1234;

        // there is no listener on this one
        assertAvailable(port);

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME,
                createProvider(TEST_AUTH_NAME, port, null, null, null, null, null, null, 3, 0L));

        // the severe on is the last one!
        assertLogContains("Connection to provider domain " + TEST_PROVIDER_DOMAIN + " failed", Level.SEVERE,
                ConnectException.class);

        COMPONENT_UNDER_TEST.undeployAllServices();

        // ensure it has been done only 3 times by checking after undeploy (the warning one is before a retry)
        assertLogContains("Connection to provider domain " + TEST_PROVIDER_DOMAIN + " failed", Level.WARNING, 3, true,
                ConnectException.class);
    }

    @Test
    public void testCantConnectReconnect() throws Exception {
        final int port = 1234;

        // there is no listener on this one
        assertAvailable(port);

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME,
                createProvider(TEST_AUTH_NAME, port, null, null, null, null, null, null, 0, 0L));

        // let's wait for the first error
        assertLogContains("Connection to provider domain " + TEST_PROVIDER_DOMAIN + " failed", Level.SEVERE,
                ConnectException.class);

        getComponent().reconnectDomains(false);

        // and at least the error from this connect
        assertLogContains("Connection to provider domain " + TEST_PROVIDER_DOMAIN + " failed", Level.SEVERE, 2, false,
                ConnectException.class);

        COMPONENT_UNDER_TEST.undeployAllServices();

        // ensure it has been done only 2 times by checking after undeploy
        assertLogContains("Connecting to " + TEST_PROVIDER_DOMAIN, Level.INFO, 2, true);
        assertLogContains("Connection to provider domain " + TEST_PROVIDER_DOMAIN + " failed", Level.SEVERE, 2, true,
                ConnectException.class);

    }

    @Test
    public void testCantAuthNoCD() throws Exception {
        final String id = "default";
        final int port = 1234;
        
        // there is no listener on this one
        assertAvailable(port);

        addTransportListener(id, port);

        assertNotAvailable(port);

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME, createProvider(TEST_AUTH_NAME, port));

        assertLogContains("unknown auth-name '" + TEST_AUTH_NAME + "'", Level.WARNING, 1);

        removeTransportListener(id);
    }

    @Test
    public void testCantAuthWrongCD() throws Exception {
        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME, createHelloConsumes(true, true));

        final String authName = "INCORRECT";
        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME, createProvider(authName, TEST_TRANSPORT_PORT));

        assertLogContains("unknown auth-name '" + authName + "'", Level.WARNING, 1);
        // TODO we should also test that the connection is closed
    }
}
