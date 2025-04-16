/**
 * Copyright (c) 2015-2025 Linagora
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

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.component.framework.junit.RequestMessage;
import org.ow2.petals.component.framework.junit.StatusMessage;
import org.ow2.petals.component.framework.junit.helpers.MessageChecks;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;

@RunWith(Parameterized.class)
public class BcGatewayMEPTest extends AbstractComponentTest {

    @SuppressWarnings("null")
    @Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                { MEPPatternConstants.IN_OUT.value(), ServiceProviderImplementation.outMessage(OUT),
                        MessageChecks.hasOut().andThen(MessageChecks.hasXmlContent(OUT)) },
                { MEPPatternConstants.IN_OUT.value(), ServiceProviderImplementation.errorMessage(ERROR),
                        MessageChecks.hasError() },
                { MEPPatternConstants.IN_OUT.value(), ServiceProviderImplementation.faultMessage(FAULT),
                        MessageChecks.hasFault().andThen(MessageChecks.hasXmlContent(FAULT)) },
                { MEPPatternConstants.IN_OPTIONAL_OUT.value(), ServiceProviderImplementation.outMessage(OUT),
                        MessageChecks.hasOut().andThen(MessageChecks.hasXmlContent(OUT)) },
                { MEPPatternConstants.IN_OPTIONAL_OUT.value(),
                        ServiceProviderImplementation.statusMessage(ExchangeStatus.DONE), MessageChecks.onlyDone() },
                { MEPPatternConstants.IN_OPTIONAL_OUT.value(), ServiceProviderImplementation.errorMessage(ERROR),
                        MessageChecks.hasError() },
                { MEPPatternConstants.IN_OPTIONAL_OUT.value(), ServiceProviderImplementation.faultMessage(FAULT),
                        MessageChecks.hasFault().andThen(MessageChecks.hasXmlContent(FAULT)) },
                { MEPPatternConstants.IN_ONLY.value(), ServiceProviderImplementation.statusMessage(ExchangeStatus.DONE),
                        MessageChecks.onlyDone() },
                { MEPPatternConstants.ROBUST_IN_ONLY.value(),
                        ServiceProviderImplementation.statusMessage(ExchangeStatus.DONE), MessageChecks.onlyDone() },
                { MEPPatternConstants.ROBUST_IN_ONLY.value(), ServiceProviderImplementation.errorMessage(ERROR),
                        MessageChecks.hasError() },
                { MEPPatternConstants.ROBUST_IN_ONLY.value(), ServiceProviderImplementation.faultMessage(FAULT),
                        MessageChecks.hasFault().andThen(MessageChecks.hasXmlContent(FAULT)) } });
    }

    @Parameter
    @Nullable
    public URI mep = null;

    protected URI mep() {
        assert mep != null;
        return mep;
    }

    @Parameter(1)
    @Nullable
    public ServiceProviderImplementation spi = null;

    protected ServiceProviderImplementation spi() {
        assert spi != null;
        return spi;
    }

    @Parameter(2)
    @Nullable
    public MessageChecks checks = null;

    protected MessageChecks checks() {
        assert checks != null;
        return checks;
    }

    @Test
    public void test() throws Exception {

        final ServiceEndpoint endpoint = deployTwoDomains();

        final RequestMessage request = helloRequest(endpoint, mep());
        
        final ServiceProviderImplementation impl = spi().with(MessageChecks.hasXmlContent(IN));
        if (impl.statusExpected()) {
            COMPONENT.sendAndCheckResponseAndSendStatus(request, impl, checks(),
                    ExchangeStatus.DONE);
        } else {
            final StatusMessage response = COMPONENT.sendAndGetStatus(request, impl);
            checks().checks(response);
        }

    }
}
