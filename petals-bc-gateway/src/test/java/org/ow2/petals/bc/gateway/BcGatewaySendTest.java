/**
 * Copyright (c) 2016-2023 Linagora
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

import javax.jbi.servicedesc.ServiceEndpoint;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.Test;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.bc.gateway.commons.AbstractDomain;
import org.ow2.petals.component.framework.junit.Message;
import org.ow2.petals.component.framework.junit.StatusMessage;
import org.ow2.petals.component.framework.junit.helpers.MessageChecks;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;

public class BcGatewaySendTest extends AbstractComponentTest {

    @Test
    public void testTimeout() throws Exception {
        final ServiceEndpoint endpoint = deployTwoDomains();

        final ServiceProviderImplementation provider = ServiceProviderImplementation.errorMessage(ERROR)
                .with(new MessageChecks() {
                    @Override
                    public void checks(final @Nullable Message message) throws Exception {
                        Thread.sleep(DEFAULT_TIMEOUT_FOR_COMPONENT_SEND + 1000);
                    }
                });

        final StatusMessage response = COMPONENT
                .sendAndGetStatus(helloRequest(endpoint, MEPPatternConstants.IN_OUT.value()), provider);

        assertEquals(AbstractDomain.TIMEOUT_EXCEPTION.getMessage(), response.getError().getMessage());
    }
}
