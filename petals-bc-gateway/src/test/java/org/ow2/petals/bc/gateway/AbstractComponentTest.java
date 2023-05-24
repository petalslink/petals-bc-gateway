/**
 * Copyright (c) 2015-2023 Linagora
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

import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
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
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
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
import org.ow2.petals.component.framework.junit.impl.message.RequestToProviderMessage;
import org.ow2.petals.component.framework.junit.rule.ComponentUnderTest;
import org.ow2.petals.junit.rules.log.handler.InMemoryLogHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ebmwebsourcing.easycommons.lang.reflect.ReflectionHelper;

public abstract class AbstractComponentTest extends AbstractEnvironmentTest implements BcGatewayJbiTestConstants {

    protected static final ComponentConfiguration CONFIGURATION = new ComponentConfiguration("G") {
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
            .addLogHandler(AbstractComponentTestUtils.IN_MEMORY_LOG_HANDLER.getHandler())
            .registerExternalServiceProvider(EXTERNAL_HELLO_ENDPOINT, HELLO_SERVICE, HELLO_INTERFACE);

    /**
     * We use a class rule (i.e. static) so that the component lives during all the tests, this enables to test also
     * that successive deploy and undeploy do not create problems.
     */
    @ClassRule
    public static final TestRule chain = RuleChain.outerRule(new EnsurePortsAreOK())
            .around(AbstractComponentTestUtils.IN_MEMORY_LOG_HANDLER)
            .around(COMPONENT_UNDER_TEST);

    protected static final SimpleComponent COMPONENT = new SimpleComponent(COMPONENT_UNDER_TEST);

    /**
     * All log traces must be cleared before starting a unit test (because the log handler is static and lives during
     * the whole suite of tests)
     */
    @Before
    public void clearLogTraces() {
        AbstractComponentTestUtils.IN_MEMORY_LOG_HANDLER.clear();
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
        undeployServices(COMPONENT_UNDER_TEST, AbstractComponentTestUtils.IN_MEMORY_LOG_HANDLER);
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

    protected ServiceEndpoint deployTwoDomains(final boolean specifyService, final boolean specifyEndpoint,
            final @Nullable String clientCertificate, final @Nullable String clientKey,
            final @Nullable String clientRemoteCertificate, final @Nullable String serverCertificate,
            final @Nullable String serverKey, final @Nullable String serverRemoteCertificate,
            final @Nullable Integer retryMax, final @Nullable Long retryDelay, final @Nullable Long pollingDelay)
            throws Exception {
        return deployTwoDomains(COMPONENT_UNDER_TEST,
                createHelloConsumes(specifyService, specifyEndpoint, serverCertificate, serverKey,
                        serverRemoteCertificate, pollingDelay),
                createProvider(TEST_AUTH_NAME, TEST_TRANSPORT_PORT, clientCertificate, null, clientKey, null,
                        clientRemoteCertificate, null, retryMax, retryDelay));
    }

    protected RequestMessage helloRequest(final ServiceEndpoint endpoint, final URI pattern) {
        return new RequestToProviderMessage(COMPONENT_UNDER_TEST, endpoint.getEndpointName(),
                endpoint.getServiceName(), null, HELLO_OPERATION, pattern, IN);
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
        assertLogContains(AbstractComponentTestUtils.IN_MEMORY_LOG_HANDLER, log, level, howMany, exactly, exception);
    }

    protected static void assertLogContains(final InMemoryLogHandler handler, final String log, final Level level,
            final int howMany, final boolean exactly) {
        assertLogContains(handler, log, level, howMany, exactly, null);
    }

    protected static void assertLogContains(final InMemoryLogHandler handler, final String log, final Level level,
            final int howMany, final boolean exactly, final @Nullable Class<?> exception) {
        await().atMost(Duration.ofSeconds(5)).until(new Callable<Boolean>() {
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
