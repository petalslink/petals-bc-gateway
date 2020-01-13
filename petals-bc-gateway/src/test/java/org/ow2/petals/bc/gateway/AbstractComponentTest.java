/**
 * Copyright (c) 2015-2020 Linagora
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

import static com.jayway.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.basisapi.exception.PetalsException;
import org.ow2.petals.bc.gateway.commons.AbstractDomain;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.message.Exchange;
import org.ow2.petals.component.framework.junit.Component;
import org.ow2.petals.component.framework.junit.RequestMessage;
import org.ow2.petals.component.framework.junit.helpers.MessageChecks;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;
import org.ow2.petals.component.framework.junit.helpers.SimpleComponent;
import org.ow2.petals.component.framework.junit.impl.ComponentConfiguration;
import org.ow2.petals.component.framework.junit.impl.ConsumesServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.ServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.message.RequestToProviderMessage;
import org.ow2.petals.component.framework.junit.rule.ComponentUnderTest;
import org.ow2.petals.junit.rules.log.handler.InMemoryLogHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ebmwebsourcing.easycommons.lang.reflect.ReflectionHelper;
import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;

public class AbstractComponentTest extends AbstractTest implements BcGatewayJbiTestConstants {

    protected static final String FAULT = "<c/>";

    protected static final String OUT = "<b/>";

    protected static final String IN = "<a/>";

    protected static final Exception ERROR = new Exception("exchange arriving too late");
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

    private static final ComponentConfiguration CONFIGURATION = new ComponentConfiguration("G") {
        @Override
        protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
            assert jbiDocument != null;

            final Element compo = getComponentElement(jbiDocument);

            final Element transport = addElement(jbiDocument, compo, EL_TRANSPORT_LISTENER);
            transport.setAttribute(ATTR_TRANSPORT_LISTENER_ID, TEST_TRANSPORT_NAME);
            addElement(jbiDocument, transport, EL_TRANSPORT_LISTENER_PORT, "" + TEST_TRANSPORT_PORT);

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
            assertAvailable(TEST_TRANSPORT_PORT);
            // used by TEST_TRANSPORT2_NAME
            assertAvailable(DEFAULT_PORT);
        }

        @Override
        protected void after() {
            // used by TEST_TRANSPORT_NAME
            assertAvailable(TEST_TRANSPORT_PORT);
            // used by TEST_TRANSPORT2_NAME
            assertAvailable(DEFAULT_PORT);
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
     * TODO also test the bootstrap one...
     */
    @Before
    public void verifyMBeanOperations() {
        // let's get a copy
        final Collection<String> ops = new HashSet<>(getComponent().getMBeanOperationsNames());
        // this is written by hand on purpose to ensure we exactly know which are the registered operations
        assertTrue(ops.remove("refreshPropagations"));
        assertTrue(ops.remove("addTransportListener"));
        assertTrue(ops.remove("setTransportListenerPort"));
        assertTrue(ops.remove("removeTransportListener"));
        assertTrue(ops.remove("getTransportListeners"));
        assertTrue(ops.remove("reconnectDomains"));
        assertTrue(ops.remove(AbstractComponent.METHOD_RELOAD_PLACEHOLDERS));
        assertTrue(ops.isEmpty());
    }

    private final List<String> manuallyAddedListeners = new ArrayList<>();

    protected void addTransportListener(final String id, final int port) throws PetalsException {
        final BcGatewayComponent comp = getComponent();
        comp.addTransportListener(id, port);
        manuallyAddedListeners.add(id);
    }

    protected boolean removeTransportListener(final String id) throws PetalsException {
        final BcGatewayComponent comp = getComponent();
        manuallyAddedListeners.remove(id);
        return comp.removeTransportListener(id);
    }

    @SuppressWarnings("null")
    protected BcGatewayComponent getComponent() {
        return (BcGatewayComponent) COMPONENT_UNDER_TEST.getComponentObject();
    }

    @After
    public void ensureNoExchangeInProgress() {
        ensureNoExchangeInProgress(COMPONENT_UNDER_TEST);
    }

    protected void ensureNoExchangeInProgress(final Component componentUnderTest) {
        final BcGatewayComponent comp = (BcGatewayComponent) componentUnderTest.getComponentObject();
        for (final ProviderDomain pd : comp.getServiceUnitManager().getProviderDomains()) {
            @SuppressWarnings("unchecked")
            final Map<String, Exchange> exchangesInProgress = (Map<String, Exchange>) ReflectionHelper
                    .getFieldValue(AbstractDomain.class, pd, "exchangesInProgress", false);
            assertTrue(String.format("Exchange in progress is not empty for %s: %s", pd.getJPD().getId(),
                    exchangesInProgress), exchangesInProgress.isEmpty());
        }

        for (final ConsumerDomain pd : comp.getServiceUnitManager().getConsumerDomains()) {
            @SuppressWarnings("unchecked")
            final Map<String, Exchange> exchangesInProgress = (Map<String, Exchange>) ReflectionHelper
                    .getFieldValue(AbstractDomain.class, pd, "exchangesInProgress", false);
            assertTrue(String.format("Exchange in progress is not empty for %s: %s", pd.getJCD().getId(),
                    exchangesInProgress), exchangesInProgress.isEmpty());
        }
    }

    @After
    public void cleanManuallyAddedListeners() throws Exception {
        final BcGatewayComponent comp = getComponent();
        final List<String> todo = new ArrayList<>(manuallyAddedListeners);
        manuallyAddedListeners.clear();
        for (final String tl : todo) {
            comp.removeTransportListener(tl);
        }
    }

    @After
    public void undeployServices() {
        undeployServices(COMPONENT_UNDER_TEST, IN_MEMORY_LOG_HANDLER);
    }

    /**
     * We undeploy services after each test (because the component is static and lives during the whole suite of tests)
     */
    public void undeployServices(final Component comp, final InMemoryLogHandler handler) {
        comp.undeployAllServices();

        // TODO we should check that there is no reconnections or polling still working!
        // TODO could we check that everything was well gc'd?

        // asserts are ALWAYS a bug!
        final Formatter formatter = new SimpleFormatter();
        for (final LogRecord r : handler.getAllRecords()) {
            assertFalse("Got a log with an assertion: " + formatter.format(r),
                    r.getThrown() instanceof AssertionError || r.getMessage().contains("AssertionError"));
        }
    }

    protected static ServiceConfiguration createProvider() {
        return createProvider(TEST_AUTH_NAME, TEST_TRANSPORT_PORT);
    }

    protected static ServiceConfiguration createProvider(final String authName, final int port) {
        return createProvider(authName, port, null, null, null, null, null);
    }

    protected static ServiceConfiguration createProvider(final String authName, final int port,
            final @Nullable String certificate, final @Nullable String key, final @Nullable String remoteCertificate,
            final @Nullable Integer retryMax, final @Nullable Long retryDelay) {
        final ServiceConfiguration provides = new ServiceConfiguration() {
            @Override
            protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
                assert jbiDocument != null;

                final Element services = getOrCreateServicesElement(jbiDocument);

                final Element pDomain = addElement(jbiDocument, services, EL_PROVIDER_DOMAIN);
                pDomain.setAttribute(ATTR_SERVICES_PROVIDER_DOMAIN_ID, TEST_PROVIDER_DOMAIN);
                if (retryMax != null) {
                    pDomain.setAttribute(ATTR_SERVICES_PROVIDER_DOMAIN_RETRY_MAX, "" + retryMax);
                }
                if (retryDelay != null) {
                    pDomain.setAttribute(ATTR_SERVICES_PROVIDER_DOMAIN_RETRY_DELAY, "" + retryDelay);
                }
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_IP).setTextContent("localhost");
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_PORT).setTextContent("" + port);
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_AUTH_NAME).setTextContent(authName);
                if (certificate != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_CRT)
                            .setTextContent(new File(certificate).getName());
                }
                if (key != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_KEY)
                            .setTextContent(new File(key).getName());
                }
                if (remoteCertificate != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_REMOTE_CRT)
                            .setTextContent(new File(remoteCertificate).getName());
                }
            }
        };

        if (certificate != null) {
            provides.addResource(AbstractComponentTest.class.getResource(certificate));
        }
        if (key != null) {
            provides.addResource(AbstractComponentTest.class.getResource(key));
        }
        if (remoteCertificate != null) {
            provides.addResource(AbstractComponentTest.class.getResource(remoteCertificate));
        }

        return provides;
    }

    protected static ConsumesServiceConfiguration createHelloConsumes(final boolean specifyService,
            final boolean specifyEndpoint) {
        return createHelloConsumes(specifyService, specifyEndpoint, null, null, null, null);
    }

    protected static ConsumesServiceConfiguration createHelloConsumes(final boolean specifyService,
            final boolean specifyEndpoint, final @Nullable String certificate, final @Nullable String key,
            final @Nullable String remoteCertificate, final @Nullable Long pollingDelay) {

        // can't have endpoint specified without service
        assert !specifyEndpoint || specifyService;

        return createConsumes(HELLO_INTERFACE, specifyService ? HELLO_SERVICE : null,
                specifyEndpoint ? EXTERNAL_HELLO_ENDPOINT : null, certificate, key, remoteCertificate, pollingDelay);
    }

    protected static ConsumesServiceConfiguration createConsumes(final QName interfaceName,
            final @Nullable QName service, final @Nullable String endpoint, final @Nullable String certificate,
            final @Nullable String key, final @Nullable String remoteCertificate, final @Nullable Long pollingDelay) {

        final ConsumesServiceConfiguration consumes = new ConsumesServiceConfiguration(interfaceName, service,
                endpoint) {
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

                final Element cDomain = addElement(jbiDocument, services, EL_CONSUMER_DOMAIN);
                cDomain.setAttribute(ATTR_SERVICES_CONSUMER_DOMAIN_ID, TEST_CONSUMER_DOMAIN);
                cDomain.setAttribute(ATTR_SERVICES_CONSUMER_DOMAIN_TRANSPORT, TEST_TRANSPORT_NAME);
                if (pollingDelay != null) {
                    cDomain.setAttribute(ATTR_SERVICES_CONSUMER_DOMAIN_POLLING_DELAY, "" + pollingDelay);
                }

                addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME, TEST_AUTH_NAME);
                if (certificate != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_CRT)
                            .setTextContent(new File(certificate).getName());
                }
                if (key != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_KEY)
                            .setTextContent(new File(key).getName());
                }
                if (remoteCertificate != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_REMOTE_CRT)
                            .setTextContent(new File(remoteCertificate).getName());
                }
            }
        };

        if (certificate != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(certificate));
        }
        if (key != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(key));
        }
        if (remoteCertificate != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(remoteCertificate));
        }

        // let's use a smaller timeout time by default
        consumes.setTimeout(DEFAULT_TIMEOUT_FOR_COMPONENT_SEND);

        return consumes;
    }

    protected static void assertAvailable(final int port) {
        assertAvailable(port, true);
    }

    protected static void assertNotAvailable(final int port) {
        assertAvailable(port, false);
    }

    protected static void assertAvailable(final int port, final boolean is) {
        Awaitility.waitAtMost(Duration.ONE_SECOND).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                try (final Socket ignored = new Socket("localhost", port)) {
                    return false;
                } catch (IOException ignored) {
                    return true;
                }
            }
        }, Matchers.is(is));
    }

    protected void twoDomainsTest(final boolean specifyService, final boolean specifyEndpoint) throws Exception {
        twoDomainsTest(specifyService, specifyEndpoint, null, null, null, null, null, null, null, null, null);
    }

    protected void twoDomainsTest(final boolean specifyService, final boolean specifyEndpoint,
            final @Nullable String clientCertificate, final @Nullable String clientKey,
            final @Nullable String clientRemoteCertificate, final @Nullable String serverCertificate,
            final @Nullable String serverKey, final @Nullable String serverRemoteCertificate,
            final @Nullable Integer retryMax, final @Nullable Long retryDelay, final @Nullable Long pollingDelay)
            throws Exception {

        final ServiceEndpoint endpoint = deployTwoDomains(specifyService, specifyEndpoint, clientCertificate, clientKey,
                clientRemoteCertificate, serverCertificate, serverKey, serverRemoteCertificate, retryMax, retryDelay,
                pollingDelay);

        COMPONENT.sendAndCheckResponseAndSendStatus(helloRequest(endpoint, MEPPatternConstants.IN_OUT.value()),
                ServiceProviderImplementation.outMessage(OUT),
                MessageChecks.hasOut().andThen(MessageChecks.hasXmlContent(OUT)), ExchangeStatus.DONE);
    }

    protected ServiceEndpoint deployTwoDomains() throws Exception {
        return deployTwoDomains(true, true);
    }

    protected ServiceEndpoint deployTwoDomains(final boolean specifyService, final boolean specifyEndpoint)
            throws Exception {
        return deployTwoDomains(true, true, null, null, null, null, null, null, null, null, null);
    }

    /**
     * TODO it would be relevant to check for all domain deployed that everything has been cleaned as desired
     */
    protected ServiceEndpoint deployTwoDomains(final boolean specifyService, final boolean specifyEndpoint,
            final @Nullable String clientCertificate, final @Nullable String clientKey,
            final @Nullable String clientRemoteCertificate, final @Nullable String serverCertificate,
            final @Nullable String serverKey, final @Nullable String serverRemoteCertificate,
            final @Nullable Integer retryMax, final @Nullable Long retryDelay, final @Nullable Long pollingDelay)
            throws Exception {

        COMPONENT_UNDER_TEST.deployService(SU_CONSUMER_NAME, createHelloConsumes(specifyService, specifyEndpoint,
                serverCertificate, serverKey, serverRemoteCertificate, pollingDelay));

        COMPONENT_UNDER_TEST.deployService(SU_PROVIDER_NAME,
                createProvider(TEST_AUTH_NAME, TEST_TRANSPORT_PORT, clientCertificate, clientKey,
                        clientRemoteCertificate, retryMax, retryDelay));

        Awaitility.await().atMost(Duration.TEN_SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return getNotExternalEndpoint() != null;
            }
        });

        final ServiceEndpoint endpoint = getNotExternalEndpoint();
        assert endpoint != null;
        return endpoint;
    }

    protected RequestMessage helloRequest(final ServiceEndpoint endpoint, final URI pattern) {
        return new RequestToProviderMessage(COMPONENT_UNDER_TEST, endpoint.getEndpointName(),
                endpoint.getServiceName(), null, HELLO_OPERATION, pattern, IN);
    }

    protected static @Nullable ServiceEndpoint getNotExternalEndpoint() {
        return getNotEndpoint(HELLO_INTERFACE, HELLO_SERVICE, EXTERNAL_HELLO_ENDPOINT);
    }

    protected static @Nullable ServiceEndpoint getNotEndpoint(final QName interfaceName, final QName serviceName,
            final String notEndpointName) {
        for (final ServiceEndpoint endpoint : COMPONENT_UNDER_TEST.getEndpointDirectory()
                .resolveEndpointsForService(serviceName)) {
            if (!endpoint.getEndpointName().equals(notEndpointName)) {
                return endpoint;
            }
        }

        return null;
    }

    protected static void assertLogContains(final String log, final Level level, final @Nullable Class<?> exception) {
        assertLogContains(log, level, 1, false, exception);
    }

    /**
     * Note: the check is not on the number, only the number of time it is printed before we are happy with the result
     */
    protected static void assertLogContains(final String log, final Level level, final int howMany) {
        assertLogContains(log, level, howMany, false);
    }

    protected static void assertLogContains(final String log, final Level level, final int howMany,
            final boolean exactly) {
        assertLogContains(log, level, howMany, exactly, null);
    }

    protected static void assertLogContains(final String log, final Level level, final int howMany,
            final boolean exactly, final @Nullable Class<?> exception) {
        assertLogContains(IN_MEMORY_LOG_HANDLER, log, level, howMany, exactly, exception);
    }

    protected static void assertLogContains(final InMemoryLogHandler handler, final String log, final Level level,
            final int howMany, final boolean exactly) {
        assertLogContains(handler, log, level, howMany, exactly, null);
    }

    protected static void assertLogContains(final InMemoryLogHandler handler, final String log, final Level level,
            final int howMany, final boolean exactly, final @Nullable Class<?> exception) {
        await().atMost(Duration.FIVE_SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                int count = 0;
                for (final LogRecord lr : handler.getAllRecords(level)) {
                    if (lr.getMessage().contains(log) && (exception == null || exception.isInstance(lr.getThrown()))) {
                        count++;
                        if (!exactly && count >= howMany) {
                            return true;
                        }
                    }
                }

                if (!exactly) {
                    // it should have returned in the loop
                    return false;
                } else {
                    return count == howMany;
                }
            }
        });
    }
}
