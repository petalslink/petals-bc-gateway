/**
 * Copyright (c) 2015-2026 Linagora
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
import java.util.stream.Stream;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.component.framework.junit.RequestMessage;
import org.ow2.petals.component.framework.junit.StatusMessage;
import org.ow2.petals.component.framework.junit.helpers.MessageChecks;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;

public class BcGatewayMEPTest extends AbstractComponentTest {

    static class Params implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) {
            return Stream.of(
                    Arguments.of(MEPPatternConstants.IN_OUT.value(), ServiceProviderImplementation.outMessage(OUT),
                            MessageChecks.hasOut().andThen(MessageChecks.hasXmlContent(OUT))),
                    Arguments.of(MEPPatternConstants.IN_OUT.value(), ServiceProviderImplementation.errorMessage(ERROR),
                            MessageChecks.hasError()),
                    Arguments.of(MEPPatternConstants.IN_OUT.value(), ServiceProviderImplementation.faultMessage(FAULT),
                            MessageChecks.hasFault().andThen(MessageChecks.hasXmlContent(FAULT))),
                    Arguments.of(MEPPatternConstants.IN_OPTIONAL_OUT.value(),
                            ServiceProviderImplementation.outMessage(OUT),
                            MessageChecks.hasOut().andThen(MessageChecks.hasXmlContent(OUT))),
                    Arguments.of(MEPPatternConstants.IN_OPTIONAL_OUT.value(),
                            ServiceProviderImplementation.statusMessage(ExchangeStatus.DONE), MessageChecks.onlyDone()),
                    Arguments.of(MEPPatternConstants.IN_OPTIONAL_OUT.value(),
                            ServiceProviderImplementation.errorMessage(ERROR), MessageChecks.hasError()),
                    Arguments.of(MEPPatternConstants.IN_OPTIONAL_OUT.value(),
                            ServiceProviderImplementation.faultMessage(FAULT),
                            MessageChecks.hasFault().andThen(MessageChecks.hasXmlContent(FAULT))),
                    Arguments.of(MEPPatternConstants.IN_ONLY.value(),
                            ServiceProviderImplementation.statusMessage(ExchangeStatus.DONE), MessageChecks.onlyDone()),
                    Arguments.of(MEPPatternConstants.ROBUST_IN_ONLY.value(),
                            ServiceProviderImplementation.statusMessage(ExchangeStatus.DONE), MessageChecks.onlyDone()),
                    Arguments.of(MEPPatternConstants.ROBUST_IN_ONLY.value(),
                            ServiceProviderImplementation.errorMessage(ERROR), MessageChecks.hasError()),
                    Arguments.of(MEPPatternConstants.ROBUST_IN_ONLY.value(),
                            ServiceProviderImplementation.faultMessage(FAULT),
                            MessageChecks.hasFault().andThen(MessageChecks.hasXmlContent(FAULT))));
        }
    }

    private URI mep(final URI mep) {
        assert mep != null;
        return mep;
    }

    private ServiceProviderImplementation spi(final ServiceProviderImplementation spi) {
        assert spi != null;
        return spi;
    }

    private MessageChecks checks(final MessageChecks checks) {
        assert checks != null;
        return checks;
    }

    @ParameterizedTest(name = "{index}: {0},{1},{2}")
    @ArgumentsSource(Params.class)
    public void test(final URI mep, final ServiceProviderImplementation spi, final MessageChecks checks)
            throws Exception {

        final ServiceEndpoint endpoint = deployTwoDomains();

        final RequestMessage request = helloRequest(endpoint, mep(mep));

        final ServiceProviderImplementation impl = spi(spi).with(MessageChecks.hasXmlContent(IN));
        if (impl.statusExpected()) {
            COMPONENT.sendAndCheckResponseAndSendStatus(request, impl, checks(checks), ExchangeStatus.DONE);
        } else {
            final StatusMessage response = COMPONENT.sendAndGetStatus(request, impl);
            checks(checks).checks(response);
        }

    }
}
