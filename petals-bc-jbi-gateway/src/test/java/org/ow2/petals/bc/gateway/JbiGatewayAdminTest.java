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
import org.ow2.petals.basisapi.exception.PetalsException;

public class JbiGatewayAdminTest extends AbstractComponentTest {

    @Test
    public void testCantAddListener() throws Exception {
        assertNotAvailable(TEST_TRANSPORT_PORT);

        thrown.expect(PetalsException.class);
        thrown.expectMessage("A transport listener with id '" + TEST_TRANSPORT_NAME + "' already exists");

        addTransportListener(TEST_TRANSPORT_NAME, 1234);
    }

    @Test
    public void testCantRemoveListener() throws Exception {
        assertNotAvailable(TEST_TRANSPORT_PORT);

        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME, createHelloConsumes(true, true));

        thrown.expect(PetalsException.class);
        thrown.expectMessage("Can't remove a transport listener with SUs using it");

        removeTransportListener(TEST_TRANSPORT_NAME);
    }

    @Test
    public void testNoListenerToRemove() throws Exception {
        assertFalse(removeTransportListener("default"));
    }

    @Test
    public void testAddSetRemoveListener() throws Exception {
        final JbiGatewayComponent comp = (JbiGatewayComponent) COMPONENT_UNDER_TEST.getComponentObject();

        final String id = "default";
        final int port = 1234;
        final int port2 = 1235;

        assertAvailable(port);
        assertAvailable(port2);

        addTransportListener(id, port);

        assertNotAvailable(port);
        assertAvailable(port2);

        comp.setTransportListenerPort(id, port2);

        assertAvailable(port);
        assertNotAvailable(port2);

        assertTrue(removeTransportListener(id));

        assertAvailable(port);
        assertAvailable(port2);
    }

    @Test
    public void testAddInvalidListener() throws Exception {
        final JbiGatewayComponent comp = (JbiGatewayComponent) COMPONENT_UNDER_TEST.getComponentObject();

        final String id = "default";
        final String id2 = "default2";
        final int port = 1234;
        final int port2 = 1235;

        assertAvailable(port);
        assertAvailable(port2);

        addTransportListener(id, port);

        assertNotAvailable(port);
        assertAvailable(port2);

        addTransportListener(id2, port);

        assertNotAvailable(port);
        assertAvailable(port2);
        assertLogContains("Cannot bind transport listener " + id2);

        comp.setTransportListenerPort(id2, port2);

        assertNotAvailable(port);
        assertNotAvailable(port2);

        assertTrue(removeTransportListener(id));

        assertAvailable(port);
        assertNotAvailable(port2);

        assertTrue(removeTransportListener(id2));

        assertAvailable(port);
        assertAvailable(port2);
    }
}
