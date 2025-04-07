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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.stream.Stream;

import javax.xml.namespace.QName;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.ow2.easywsdl.wsdl.api.WSDLException;
import org.ow2.petals.component.framework.junit.extensions.ComponentConfigurationExtension;
import org.ow2.petals.component.framework.junit.extensions.ComponentUnderTestExtension;
import org.ow2.petals.component.framework.junit.extensions.api.ComponentUnderTest;
import org.ow2.petals.component.framework.junit.impl.mock.MockEndpointDirectory;
import org.ow2.petals.component.framework.junit.impl.mock.MockServiceEndpoint;
import org.ow2.petals.component.framework.util.WSDLUtilImpl;
import org.ow2.petals.jbi.servicedesc.endpoint.Location;
import org.ow2.petals.junit.extensions.log.handler.InMemoryLogHandlerExtension;

/**
 * TODO also test that there is no warning in the log w.r.t. descriptions! Maybe we could use assertLogContains to
 * remove the logs we expected, and then at the end test that there is no warning nor severe?
 * 
 * @author vnoel
 */
public class BcGatewayRefreshTest extends AbstractComponentTest {

    private static final QName TEST_INTERFACE = new QName(HELLO_NS, "TestInterface");

    private static final QName TEST_SERVICE = new QName(HELLO_NS, "TestService");

    private static final QName TEST_SERVICE2 = new QName(HELLO_NS, "TestService2");

    private static final String TEST_ENDPOINT_NAME = "testEndpoint";

    private static final String TEST_ENDPOINT_NAME2 = "testEndpoint2";

    private static final MockServiceEndpoint SERVICE_ENDPOINT = new MockServiceEndpoint(TEST_ENDPOINT_NAME,
            TEST_SERVICE, TEST_INTERFACE);

    private static final MockServiceEndpoint SERVICE_ENDPOINT2 = new MockServiceEndpoint(TEST_ENDPOINT_NAME2,
            TEST_SERVICE2, TEST_INTERFACE);

    private static final MockServiceEndpoint SERVICE_ENDPOINT_WITH_DESC;

    private static final MockServiceEndpoint SERVICE_ENDPOINT_WITH_DESC2;

    static {
        try {
            SERVICE_ENDPOINT_WITH_DESC = new MockServiceEndpoint(TEST_ENDPOINT_NAME, TEST_SERVICE,
                    new QName[] { TEST_INTERFACE }, new Location("test", "test"),
                    WSDLUtilImpl.convertDescriptionToDocument(WSDLUtilImpl.createLightWSDL20Description(TEST_INTERFACE,
                            TEST_SERVICE, TEST_ENDPOINT_NAME)));

            SERVICE_ENDPOINT_WITH_DESC2 = new MockServiceEndpoint(TEST_ENDPOINT_NAME2, TEST_SERVICE2,
                    new QName[] { TEST_INTERFACE }, new Location("test", "test"),
                    WSDLUtilImpl.convertDescriptionToDocument(WSDLUtilImpl.createLightWSDL20Description(TEST_INTERFACE,
                            TEST_SERVICE2, TEST_ENDPOINT_NAME2)));

        } catch (WSDLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * There is no transport listener for this one
     */
    @ComponentUnderTestExtension(
            inMemoryLogHandler = @InMemoryLogHandlerExtension, explicitPostInitialization = true, componentConfiguration = @ComponentConfigurationExtension(
                    name = "G2"
            )
    )
    protected static ComponentUnderTest COMPONENT_UNDER_TEST2;

    @BeforeAll
    private static void completesComponentUnderTest2() throws Exception {

        COMPONENT_UNDER_TEST2
                // we need faster checks for our tests, 2000 is too long!
                .setParameter(new QName(CDK_NAMESPACE_URI, "time-beetween-async-cleaner-runs"), "100")
                .postInitComponentUnderTest();
    }

    static class Params implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) {
            return Stream.of(
                    Arguments.of(false, false, false, 0L),
                    Arguments.of(false, false, false, 1000L),
                    Arguments.of(true, false, false, 0L),
                    Arguments.of(true, false, false, 1000L),
                    Arguments.of(true, true, false, 0L),
                    Arguments.of(true, true, false, 1000L),
                    Arguments.of(false, false, true, 0L),
                    Arguments.of(false, false, true, 1000L),
                    Arguments.of(true, false, true, 0L),
                    Arguments.of(true, false, true, 1000L),
                    Arguments.of(true, true, true, 0L),
                    Arguments.of(true, true, true, 1000L));
        }
    }

    /**
     * All log traces must be cleared before starting a unit test (because the log handler is static and lives during
     * the whole suite of tests)
     */
    @BeforeEach
    public void clearLogTraces2() {
        COMPONENT_UNDER_TEST2.getInMemoryLogHandler().clear();
        // we want to clear them inbetween tests
        COMPONENT_UNDER_TEST2.clearRequestsFromConsumer();
        COMPONENT_UNDER_TEST2.clearResponsesFromProvider();
    }

    @AfterEach
    public void ensureNoExchangeInProgress2() {
        ensureNoExchangeInProgress(COMPONENT_UNDER_TEST2);
    }

    @AfterEach
    public void undeployServices2() {
        COMPONENT_UNDER_TEST.getEndpointDirectory().deactivateEndpoint(SERVICE_ENDPOINT);
        COMPONENT_UNDER_TEST.getEndpointDirectory().deactivateEndpoint(SERVICE_ENDPOINT2);
        COMPONENT_UNDER_TEST.getEndpointDirectory().deactivateEndpoint(SERVICE_ENDPOINT_WITH_DESC);
        COMPONENT_UNDER_TEST.getEndpointDirectory().deactivateEndpoint(SERVICE_ENDPOINT_WITH_DESC2);

        undeployServices(COMPONENT_UNDER_TEST2);
    }

    private void deployServices(final boolean specifyService, final boolean specifyEndpoint, final long polling)
            throws Exception {
        final MockEndpointDirectory ed = COMPONENT_UNDER_TEST.getEndpointDirectory();

        // disable propagation polling
        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME,
                createConsumes(TEST_INTERFACE, specifyService ? TEST_SERVICE : null,
                        specifyEndpoint ? TEST_ENDPOINT_NAME : null, null, null, null, polling));

        COMPONENT_UNDER_TEST2.deployService(SU_PROVIDER_NAME, createProvider());

        assertLogContains(COMPONENT_UNDER_TEST2.getInMemoryLogHandler(), "AuthAccept", Level.FINE, 1, false);

        assertTrue(ed.resolveEndpoints(TEST_INTERFACE).isEmpty());
        checkEndpoints(0, 0, 0);
    }

    @ParameterizedTest(name = "{index}: {0},{1},{2},{3}")
    @ArgumentsSource(Params.class)
    public void testRefreshAddRemove(final boolean specifyService, final boolean specifyEndpoint,
            final boolean withDesc, final long polling) throws Exception {

        this.deployServices(specifyService, specifyEndpoint, polling);

        final MockEndpointDirectory ed = COMPONENT_UNDER_TEST.getEndpointDirectory();

        if (polling > 0) {
            assertLogContains("Propagation refresh polling (next in", Level.FINE, 2);
        }

        ed.activateEndpoint(withDesc ? SERVICE_ENDPOINT_WITH_DESC : SERVICE_ENDPOINT);

        if (polling == 0) {
            getComponent().refreshPropagations();
        }

        checkEndpoints(1, 1, 0);

        if (polling > 0) {
            assertLogContains("Propagation refresh polling (next in", Level.FINE, 6);
            // and check that there was only one change detected from the beginning!
            assertLogContains("Changes in propagations detected: refreshed", Level.INFO, 1);
        }

        if (!specifyService) {
            ed.activateEndpoint(withDesc ? SERVICE_ENDPOINT_WITH_DESC2 : SERVICE_ENDPOINT2);

            if (polling == 0) {
                getComponent().refreshPropagations();
            }

            checkEndpoints(2, 1, 1);

            if (polling > 0) {
                assertLogContains("Propagation refresh polling (next in", Level.FINE, 10);
                assertLogContains("Changes in propagations detected: refreshed", Level.INFO, 2);
            }
        }

        ed.deactivateEndpoint(withDesc ? SERVICE_ENDPOINT_WITH_DESC : SERVICE_ENDPOINT);

        if (polling == 0) {
            getComponent().refreshPropagations();
        }

        final int nbService2 = !specifyService ? 1 : 0;
        checkEndpoints(nbService2, 0, nbService2);

        if (polling > 0) {
            assertLogContains("Propagation refresh polling (next in", Level.FINE, !specifyService ? 14 : 10);
            assertLogContains("Changes in propagations detected: refreshed", Level.INFO, !specifyService ? 3 : 2);
        }

        if (!specifyService) {
            ed.deactivateEndpoint(withDesc ? SERVICE_ENDPOINT_WITH_DESC2 : SERVICE_ENDPOINT2);

            if (polling == 0) {
                getComponent().refreshPropagations();
            }

            checkEndpoints(0, 0, 0);

            if (polling > 0) {
                assertLogContains("Propagation refresh polling (next in", Level.FINE, 18);
                assertLogContains("Changes in propagations detected: refreshed", Level.INFO, 4);
            }
        }
    }

    @ParameterizedTest(name = "{index}: {0},{1},{2},{3}")
    @ArgumentsSource(Params.class)
    public void testRefreshUpdateDesc(final boolean specifyService, final boolean specifyEndpoint,
            final boolean withDesc, final long polling) throws Exception {

        this.deployServices(specifyService, specifyEndpoint, polling);

        if (!specifyService && !withDesc && !specifyEndpoint) {
            // no test
            return;
        }

        final MockEndpointDirectory ed = COMPONENT_UNDER_TEST.getEndpointDirectory();

        if (polling > 0) {
            assertLogContains("Propagation refresh polling (next in", Level.FINE, 2);
        }

        ed.activateEndpoint(SERVICE_ENDPOINT);

        if (polling == 0) {
            getComponent().refreshPropagations();
        }

        checkEndpoints(1, 1, 0);

        if (polling > 0) {
            assertLogContains("Propagation refresh polling (next in", Level.FINE, 6);
            // and check that there was only one change detected from the beginning!
            assertLogContains("Changes in propagations detected: refreshed", Level.INFO, 1);
        }

        // the description is present now
        ed.deactivateEndpoint(SERVICE_ENDPOINT);
        ed.activateEndpoint(SERVICE_ENDPOINT_WITH_DESC);

        if (polling == 0) {
            getComponent().refreshPropagations();
        }

        checkEndpoints(1, 1, 0);

        if (polling > 0) {
            assertLogContains("Propagation refresh polling (next in", Level.FINE, 10);
            // and check that there was only one change detected from the beginning!
            assertLogContains("Changes in propagations detected: refreshed", Level.INFO, 2);
        }
    }

    private void checkEndpoints(final int nbInterface, final int nbService, final int nbService1) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return hasInterface(TEST_INTERFACE, nbInterface) && hasService(TEST_SERVICE, nbService)
                        && hasService(TEST_SERVICE2, nbService1);
            }
        });
    }

    private boolean hasInterface(final QName interfaceName, final int howMany) {
        final Collection<MockServiceEndpoint> endpoints = COMPONENT_UNDER_TEST2.getEndpointDirectory()
                .resolveEndpoints(interfaceName);
        return endpoints.size() == howMany;
    }

    private boolean hasService(final QName service, final int howMany) {
        final Collection<MockServiceEndpoint> endpoints = COMPONENT_UNDER_TEST2.getEndpointDirectory()
                .resolveEndpointsForService(service);
        return endpoints.size() == howMany;
    }
}
