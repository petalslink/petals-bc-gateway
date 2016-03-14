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

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.junit.Component;
import org.ow2.petals.component.framework.junit.RequestMessage;
import org.ow2.petals.component.framework.junit.helpers.SimpleComponent;
import org.ow2.petals.component.framework.junit.impl.ComponentConfiguration;
import org.ow2.petals.component.framework.junit.impl.ConsumesServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.ServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.message.RequestToProviderMessage;
import org.ow2.petals.component.framework.junit.impl.mock.MockComponentContext;
import org.ow2.petals.component.framework.junit.rule.ComponentUnderTest;
import org.ow2.petals.junit.rules.log.handler.InMemoryLogHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ebmwebsourcing.easycommons.lang.reflect.ReflectionHelper;
import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;

public class AbstractComponentTest extends AbstractTest implements JbiGatewayTestConstants {

    protected static final String FAULT = "<c/>";

    protected static final String OUT = "<b/>";

    protected static final String IN = "<a/>";

    protected static final Exception ERROR = new Exception();
    static {
        // we don't really care about the stacktrace
        ERROR.setStackTrace(new StackTraceElement[0]);
    }

    protected static final String SU_CONSUMER_NAME = "suc";

    protected static final String SU_PROVIDER_NAME = "sup";

    protected static final String HELLO_NS = "http://petals.ow2.org";

    protected static final QName HELLO_INTERFACE = new QName(HELLO_NS, "HelloInterface");

    protected static final QName HELLO_SERVICE = new QName(HELLO_NS, "HelloService");

    protected static final QName HELLO_OPERATION = new QName(HELLO_NS, "sayHello");

    protected static final String EXTERNAL_HELLO_ENDPOINT = "externalHelloEndpoint";

    protected static final String HELLO_ENDPOINT_NAME = "helloEndpoint";

    protected static final int TEST_TRANSPORT_PORT = 7501;

    protected static final String TEST_TRANSPORT_NAME = "test-transport";

    protected static final String TEST_TRANSPORT2_NAME = "test-transport-default-port";

    protected static final String TEST_CONSUMER_DOMAIN = "test-consumer-domain";

    protected static final String TEST_AUTH_NAME = "test-auth-name";

    protected static final String TEST_PROVIDER_DOMAIN = "test-provider-domain";

    protected static final long DEFAULT_TIMEOUT_FOR_COMPONENT_SEND = 2000;

    protected static final InMemoryLogHandler IN_MEMORY_LOG_HANDLER = new InMemoryLogHandler();

    private static final ComponentConfiguration CONFIGURATION = new ComponentConfiguration("JG") {
        @Override
        protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
            assert jbiDocument != null;

            final Element compo = getComponentElement(jbiDocument);

            final Element transport2 = addElement(jbiDocument, compo, EL_TRANSPORT_LISTENER);
            transport2.setAttribute(ATTR_TRANSPORT_LISTENER_ID, TEST_TRANSPORT2_NAME);
            // the element is needed even if without value!
            addElement(jbiDocument, transport2, EL_TRANSPORT_LISTENER_PORT);
        }
    };

    protected static final Component COMPONENT_UNDER_TEST = new ComponentUnderTest(CONFIGURATION)
            // we need faster checks for our tests, 2000 is too long!
            .setParameter(new QName(CDK_NAMESPACE_URI, "time-beetween-async-cleaner-runs"), "100")
            .addLogHandler(IN_MEMORY_LOG_HANDLER.getHandler())
            .registerExternalServiceProvider(EXTERNAL_HELLO_ENDPOINT, HELLO_SERVICE, HELLO_INTERFACE);

    private static class EnsurePortsAreOK extends ExternalResource {
        @Override
        protected void before() throws Throwable {
            // used by TEST_TRANSPORT_NAME
            assertTrue(available(TEST_TRANSPORT_PORT));
            // used by TEST_TRANSPORT2_NAME
            assertTrue(available(DEFAULT_PORT));
        }

        @Override
        protected void after() {
            // used by TEST_TRANSPORT_NAME
            assertTrue(available(TEST_TRANSPORT_PORT));
            // used by TEST_TRANSPORT2_NAME
            assertTrue(available(DEFAULT_PORT));
        }
    }

    /**
     * We use a class rule (i.e. static) so that the component lives during all the tests, this enables to test also
     * that successive deploy and undeploy do not create problems.
     */
    @ClassRule
    public static final TestRule chain = RuleChain.outerRule(new EnsurePortsAreOK()).around(IN_MEMORY_LOG_HANDLER)
            .around(COMPONENT_UNDER_TEST);

    protected static final SimpleComponent COMPONENT = new SimpleComponent(COMPONENT_UNDER_TEST);

    /**
     * All log traces must be cleared before starting a unit test (because the log handler is static and lives during
     * the whole suite of tests)
     */
    @Before
    public void clearLogTraces() {
        IN_MEMORY_LOG_HANDLER.clear();
        // we want to clear them inbetween tests
        COMPONENT_UNDER_TEST.clearRequestsFromConsumer();
        COMPONENT_UNDER_TEST.clearResponsesFromProvider();
        // note: incoming messages queue can't be cleared because it is the job of the tested component to well handle
        // any situation
        // JUnit is susceptible to reuse threads apparently
        PetalsExecutionContext.clear();
    }

    /**
     * We undeploy services after each test (because the component is static and lives during the whole suite of tests)
     */
    @After
    public void after() {

        final JbiGatewayComponent comp = (JbiGatewayComponent) COMPONENT_UNDER_TEST.getComponentObject();

        for (final ProviderDomain pd : comp.getServiceUnitManager().getProviderDomains()) {
            @SuppressWarnings("unchecked")
            final Map<String, Exchange> exchangesInProgress = (Map<String, Exchange>) ReflectionHelper
                    .getFieldValue(AbstractDomain.class, pd, "exchangesInProgress", false);
            assertTrue(String.format("Exchange in progress is not empty for %s: %s", pd.getName(), exchangesInProgress),
                    exchangesInProgress.isEmpty());
        }

        for (final ConsumerDomain pd : comp.getServiceUnitManager().getConsumerDomains()) {
            @SuppressWarnings("unchecked")
            final Map<String, Exchange> exchangesInProgress = (Map<String, Exchange>) ReflectionHelper
                    .getFieldValue(AbstractDomain.class, pd, "exchangesInProgress", false);
            assertTrue(String.format("Exchange in progress is not empty for %s: %s", pd.getName(), exchangesInProgress),
                    exchangesInProgress.isEmpty());
        }

        COMPONENT_UNDER_TEST.undeployAllServices();

        // asserts are ALWAYS a bug!
        final Formatter formatter = new SimpleFormatter();
        for (final LogRecord r : IN_MEMORY_LOG_HANDLER.getAllRecords()) {
            assertFalse("Got a log with an assertion: " + formatter.format(r),
                    r.getThrown() instanceof AssertionError || r.getMessage().contains("AssertionError"));
        }
    }

    protected static ServiceConfiguration createHelloProvider() {
        return createHelloProvider(TEST_AUTH_NAME);
    }

    protected static ServiceConfiguration createHelloProvider(final String authName) {
        final ServiceConfiguration provides = new ServiceConfiguration() {
            @Override
            protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
                assert jbiDocument != null;

                final Element services = getOrCreateServicesElement(jbiDocument);

                final Element pDomain = addElement(jbiDocument, services, EL_PROVIDER_DOMAIN);
                pDomain.setAttribute(ATTR_SERVICES_PROVIDER_DOMAIN_ID, TEST_PROVIDER_DOMAIN);
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_IP).setTextContent("localhost");
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_PORT)
                        .setTextContent("" + TEST_TRANSPORT_PORT);
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_AUTH_NAME).setTextContent(authName);
            }
        };

        return provides;
    }

    protected static ConsumesServiceConfiguration createHelloConsumes(final boolean specifyService,
            final boolean specifyEndpoint) {

        // can't have endpoint specified without service
        assert !specifyEndpoint || specifyService;

        final ConsumesServiceConfiguration consumes = new ConsumesServiceConfiguration(HELLO_INTERFACE,
                specifyService ? HELLO_SERVICE : null, specifyEndpoint ? EXTERNAL_HELLO_ENDPOINT : null) {

            @Override
            protected void extraServiceConfiguration(final @Nullable Document jbiDocument,
                    final @Nullable Element service) {
                assert jbiDocument != null;
                assert service != null;

                final Element mapping = addElement(jbiDocument, service, EL_CONSUMER);
                mapping.setAttribute(ATTR_CONSUMES_CONSUMER_DOMAIN, TEST_CONSUMER_DOMAIN);
            }

            @Override
            protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
                assert jbiDocument != null;

                final Element services = getOrCreateServicesElement(jbiDocument);

                final Element transport = addElement(jbiDocument, services, EL_TRANSPORT_LISTENER);
                transport.setAttribute(ATTR_TRANSPORT_LISTENER_ID, TEST_TRANSPORT_NAME);
                addElement(jbiDocument, transport, EL_TRANSPORT_LISTENER_PORT, "" + TEST_TRANSPORT_PORT);

                final Element cDomain = addElement(jbiDocument, services, EL_CONSUMER_DOMAIN);
                cDomain.setAttribute(ATTR_SERVICES_CONSUMER_DOMAIN_ID, TEST_CONSUMER_DOMAIN);
                cDomain.setAttribute(ATTR_SERVICES_CONSUMER_DOMAIN_TRANSPORT, TEST_TRANSPORT_NAME);
                addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME, TEST_AUTH_NAME);
            }
        };

        // let's use a smaller timeout time by default
        consumes.setTimeout(DEFAULT_TIMEOUT_FOR_COMPONENT_SEND);

        return consumes;
    }

    protected static boolean available(int port) {
        try (Socket ignored = new Socket("localhost", port)) {
            return false;
        } catch (IOException ignored) {
            return true;
        }
    }

    protected ServiceEndpoint deployTwoDomains() throws Exception {
        return deployTwoDomains(true, true);
    }


    /**
     * TODO it would be relevant to check for all domain deployed that everything has been cleaned as desired
     */
    protected ServiceEndpoint deployTwoDomains(final boolean specifyService, final boolean specifyEndpoint)
            throws Exception {
        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME, createHelloConsumes(specifyService, specifyEndpoint));

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME, createHelloProvider());

        Awaitility.await().atMost(Duration.TWO_SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return getNotExternalEndpoint(specifyService) != null;
            }
        });

        final ServiceEndpoint endpoint = getNotExternalEndpoint(specifyService);
        assert endpoint != null;
        return endpoint;
    }

    protected RequestMessage helloRequest(final ServiceEndpoint endpoint, final URI pattern) {
        return new RequestToProviderMessage(endpoint.getEndpointName(),
                endpoint.getServiceName(), null, HELLO_OPERATION, pattern, IN);
    }

    protected static @Nullable ServiceEndpoint getNotExternalEndpoint(final boolean specifyService) {

        final QName service;
        if (specifyService) {
            service = HELLO_SERVICE;
        } else {
            service = new QName(HELLO_INTERFACE.getNamespaceURI(), HELLO_INTERFACE.getLocalPart() + "GeneratedService");
        }

        for (final ServiceEndpoint endpoint : MockComponentContext.resolveEndpointsForService(service)) {
            if (!endpoint.getEndpointName().equals(EXTERNAL_HELLO_ENDPOINT)) {
                return endpoint;
            }
        }

        return null;
    }
}
