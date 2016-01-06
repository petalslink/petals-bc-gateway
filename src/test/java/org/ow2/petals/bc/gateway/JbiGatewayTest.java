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

import org.junit.Test;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;

public class JbiGatewayTest extends AbstractComponentTest {

    @Test
    public void startAndStop() throws Exception {

        assertTrue(COMPONENT_UNDER_TEST.isInstalled());
        assertTrue(COMPONENT_UNDER_TEST.isStarted());

        assertFalse(available(TEST_TRANSPORT_PORT));
        assertFalse(available(JbiGatewayJBIHelper.DEFAULT_PORT));

        COMPONENT_UNDER_TEST.deployService(SU_NAME, createHelloConsumes());

        assertTrue(COMPONENT_UNDER_TEST.isServiceDeployed(SU_NAME));

    }
}
