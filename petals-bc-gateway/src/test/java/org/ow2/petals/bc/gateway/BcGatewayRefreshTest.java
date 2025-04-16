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

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.logging.Level;

import javax.xml.namespace.QName;

import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.ow2.easywsdl.wsdl.api.WSDLException;
import org.ow2.petals.component.framework.junit.Component;
import org.ow2.petals.component.framework.junit.impl.ComponentConfiguration;
import org.ow2.petals.component.framework.junit.impl.mock.MockEndpointDirectory;
import org.ow2.petals.component.framework.junit.impl.mock.MockServiceEndpoint;
import org.ow2.petals.component.framework.junit.rule.ComponentUnderTest;
import org.ow2.petals.component.framework.util.WSDLUtilImpl;
import org.ow2.petals.jbi.servicedesc.endpoint.Location;
import org.ow2.petals.junit.rules.log.handler.InMemoryLogHandler;

/**
 * TODO also test that there is no warning in the log w.r.t. descriptions!
 * 
 * Maybe we could use assertLogContains to remove the logs we expected, and then at the end test that there is no
 * warning nor severe?
 * 
 * @author vnoel
 *
 */
@RunWith(Parameterized.class)
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

    protected static final InMemoryLogHandler IN_MEMORY_LOG_HANDLER2 = new InMemoryLogHandler();

    /**
     * There is no transport listener for this one
     */
    protected static final Component COMPONENT_UNDER_TEST2 = new ComponentUnderTest(new ComponentConfiguration("G2"))
            // we need faster checks for our tests, 2000 is too long!
            .setParameter(new QName(CDK_NAMESPACE_URI, "time-beetween-async-cleaner-runs"), "100")
            .addLogHandler(IN_MEMORY_LOG_HANDLER2.getHandler());

    @ClassRule
    public static final TestRule chain2 = RuleChain.outerRule(IN_MEMORY_LOG_HANDLER2).around(COMPONENT_UNDER_TEST2);

    @SuppressWarnings("null")
    @Parameters(name = "{index}: {0},{1},{2},{3}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                { false, false, false, 0L },
                { false, false, false, 1000L },
                { true, false, false, 0L },
                { true, false, false, 1000L },
                { true, true, false, 0L },
                { true, true, false, 1000L },
                { false, false, true, 0L },
                { false, false, true, 1000L },
                { true, false, true, 0L },
                { true, false, true, 1000L },
                { true, true, true, 0L },
                { true, true, true, 1000L },
        });
    };

    @Parameter
    public boolean specifyService = false;
    
    @Parameter(1)
    public boolean specifyEndpoint = false;
    
    @Parameter(2)
    public boolean withDesc = false;

    @Parameter(3)
    public long polling = 0L;

    /**
     * All log traces must be cleared before starting a unit test (because the log handler is static and lives during
     * the whole suite of tests)
     */
    @Before
    public void clearLogTraces2() {
        IN_MEMORY_LOG_HANDLER2.clear();
        // we want to clear them inbetween tests
        COMPONENT_UNDER_TEST2.clearRequestsFromConsumer();
        COMPONENT_UNDER_TEST2.clearResponsesFromProvider();
    }

    @After
    public void ensureNoExchangeInProgress2() {
        ensureNoExchangeInProgress(COMPONENT_UNDER_TEST2);
    }

    @After
    public void undeployServices2() {
        COMPONENT_UNDER_TEST.getEndpointDirectory().deactivateEndpoint(SERVICE_ENDPOINT);
        COMPONENT_UNDER_TEST.getEndpointDirectory().deactivateEndpoint(SERVICE_ENDPOINT2);
        COMPONENT_UNDER_TEST.getEndpointDirectory().deactivateEndpoint(SERVICE_ENDPOINT_WITH_DESC);
        COMPONENT_UNDER_TEST.getEndpointDirectory().deactivateEndpoint(SERVICE_ENDPOINT_WITH_DESC2);

        undeployServices(COMPONENT_UNDER_TEST2, IN_MEMORY_LOG_HANDLER2);
    }

    @Before
    public void setup() throws Exception {
        final MockEndpointDirectory ed = COMPONENT_UNDER_TEST.getEndpointDirectory();

        // disable propagation polling
        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME,
                createConsumes(TEST_INTERFACE, specifyService ? TEST_SERVICE : null,
                        specifyEndpoint ? TEST_ENDPOINT_NAME : null, null, null, null, polling));

        COMPONENT_UNDER_TEST2.deployService(SU_PROVIDER_NAME, createProvider());

        assertLogContains(IN_MEMORY_LOG_HANDLER2, "AuthAccept", Level.FINE, 1, false);

        assertTrue(ed.resolveEndpoints(TEST_INTERFACE).isEmpty());
        checkEndpoints(0, 0, 0);
    }

    @Test
    public void testRefreshAddRemove() throws Exception {

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

    @Test
    public void testRefreshUpdateDesc() throws Exception {

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
