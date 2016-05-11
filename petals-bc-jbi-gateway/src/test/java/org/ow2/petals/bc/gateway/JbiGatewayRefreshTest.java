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

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.logging.Level;

import javax.xml.namespace.QName;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.ow2.easywsdl.wsdl.api.WSDLException;
import org.ow2.petals.component.framework.junit.Component;
import org.ow2.petals.component.framework.junit.impl.ComponentConfiguration;
import org.ow2.petals.component.framework.junit.impl.mock.MockEndpointDirectory;
import org.ow2.petals.component.framework.junit.impl.mock.MockServiceEndpoint;
import org.ow2.petals.component.framework.junit.rule.ComponentUnderTest;
import org.ow2.petals.component.framework.util.WSDLUtilImpl;
import org.ow2.petals.jbi.servicedesc.endpoint.Location;
import org.ow2.petals.junit.rules.log.handler.InMemoryLogHandler;

import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;

/**
 * TODO also test that there is no warning in the log w.r.t. descriptions!
 * 
 * Maybe we could use assertLogContains to remove the logs we expected, and then at the end test that there is no
 * warning nor severe?
 * 
 * TODO and test with specify endpoint!
 * 
 * @author vnoel
 *
 */
public class JbiGatewayRefreshTest extends AbstractComponentTest {

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
    protected static final Component COMPONENT_UNDER_TEST2 = new ComponentUnderTest(new ComponentConfiguration("JG2"))
            // we need faster checks for our tests, 2000 is too long!
            .setParameter(new QName(CDK_NAMESPACE_URI, "time-beetween-async-cleaner-runs"), "100")
            .addLogHandler(IN_MEMORY_LOG_HANDLER2.getHandler());

    @ClassRule
    public static final TestRule chain2 = RuleChain.outerRule(IN_MEMORY_LOG_HANDLER2).around(COMPONENT_UNDER_TEST2);

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

    @Test
    public void testRefreshExplicitWithoutDesc1() throws Exception {
        testRefreshExplicit(true, false);
    }

    @Test
    public void testRefreshExplicitWithoutDesc2() throws Exception {
        testRefreshExplicit(false, false);
    }

    @Test
    public void testRefreshExplicitWitDesc1() throws Exception {
        testRefreshExplicit(true, true);
    }

    @Test
    public void testRefreshExplicitWitDesc2() throws Exception {
        testRefreshExplicit(false, true);
    }

    public void testRefreshExplicit(final boolean specifyService, final boolean withDesc) throws Exception {

        final MockEndpointDirectory ed = COMPONENT_UNDER_TEST.getEndpointDirectory();

        // disable propagation polling
        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME,
                createConsumes(TEST_INTERFACE, specifyService ? TEST_SERVICE : null, null, null, null, null, 0L));

        COMPONENT_UNDER_TEST2.deployService(SU_PROVIDER_NAME, createProvider());

        assertLogContains(IN_MEMORY_LOG_HANDLER2, "AuthAccept", Level.FINE, 1);

        assertTrue(ed.resolveEndpoints(TEST_INTERFACE).isEmpty());

        ed.activateEndpoint(withDesc ? SERVICE_ENDPOINT_WITH_DESC : SERVICE_ENDPOINT);

        getComponent().refreshPropagations();

        Awaitility.await().atMost(Duration.FIVE_SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return hasInterface(TEST_INTERFACE, 1) && hasService(TEST_SERVICE, 1);
            }
        });

        if (!specifyService) {
            ed.activateEndpoint(withDesc ? SERVICE_ENDPOINT_WITH_DESC2 : SERVICE_ENDPOINT2);

            getComponent().refreshPropagations();

            Awaitility.await().atMost(Duration.FIVE_SECONDS).until(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return hasInterface(TEST_INTERFACE, 2) && hasService(TEST_SERVICE, 1)
                            && hasService(TEST_SERVICE2, 1);
                }
            });
        }
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

    @Test
    public void testRefreshPollingWithDesc1() throws Exception {
        testRefreshPolling(true, true);
    }

    @Test
    public void testRefreshPollingWithDesc2() throws Exception {
        testRefreshPolling(false, true);
    }

    @Test
    public void testRefreshPollingWithoutDesc1() throws Exception {
        testRefreshPolling(true, false);
    }

    @Test
    public void testRefreshPollingWithoutDesc2() throws Exception {
        testRefreshPolling(false, false);
    }

    public void testRefreshPolling(final boolean specifyService, final boolean withDesc)
            throws Exception {

        final MockEndpointDirectory ed = COMPONENT_UNDER_TEST.getEndpointDirectory();

        // disable propagation polling
        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME,
                createConsumes(TEST_INTERFACE, specifyService ? TEST_SERVICE : null, null, null, null, null, 1000L));

        COMPONENT_UNDER_TEST2.deployService(SU_PROVIDER_NAME, createProvider());

        assertLogContains(IN_MEMORY_LOG_HANDLER2, "AuthAccept", Level.FINE, 1);

        assertTrue(ed.resolveEndpoints(TEST_INTERFACE).isEmpty());

        // let's wait for two of them
        assertLogContains("Propagation refresh polling (next in", Level.FINE, 2);

        ed.activateEndpoint(withDesc ? SERVICE_ENDPOINT_WITH_DESC : SERVICE_ENDPOINT);

        Awaitility.await().atMost(Duration.FIVE_SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return hasInterface(TEST_INTERFACE, 1) && hasService(TEST_SERVICE, 1);
            }
        });

        // let's wait for some more polling (careful, they are all counted from zero!)
        assertLogContains("Propagation refresh polling (next in", Level.FINE, 6);

        // and check that there was only one change detected from the beginning!
        assertLogContains("Changes in propagations detected: refreshed", Level.INFO, 1);

        if (!specifyService) {
            ed.activateEndpoint(withDesc ? SERVICE_ENDPOINT_WITH_DESC2 : SERVICE_ENDPOINT2);

            Awaitility.await().atMost(Duration.FIVE_SECONDS).until(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return hasInterface(TEST_INTERFACE, 2) && hasService(TEST_SERVICE, 1)
                            && hasService(TEST_SERVICE2, 1);
                }
            });

            // let's wait for some more polling (careful, they are all counted from zero!)
            assertLogContains("Propagation refresh polling (next in", Level.FINE, 10);

            // and check that there was only 2 change detected from the beginning!
            assertLogContains("Changes in propagations detected: refreshed", Level.INFO, 2);
        }
    }
}
