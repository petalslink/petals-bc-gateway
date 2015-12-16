/**
 * Copyright (c) 2015 Linagora
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

import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.jbidescriptor.generated.MEPType;
import org.ow2.petals.component.framework.junit.Component;
import org.ow2.petals.component.framework.junit.JbiConstants;
import org.ow2.petals.component.framework.junit.helpers.SimpleComponent;
import org.ow2.petals.component.framework.junit.impl.ComponentConfiguration;
import org.ow2.petals.component.framework.junit.impl.ServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.ServiceConfiguration.ServiceType;
import org.ow2.petals.component.framework.junit.rule.ComponentUnderTest;
import org.ow2.petals.junit.rules.log.handler.InMemoryLogHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class AbstractComponentTest extends AbstractTest {

    protected static final String SU_NAME = "su-name";

    protected static final String HELLO_NS = "http://petals.ow2.org";

    protected static final QName HELLO_INTERFACE = new QName(HELLO_NS, "HelloInterface");

    protected static final QName HELLO_SERVICE = new QName(HELLO_NS, "HelloService");

    protected static final QName HELLO_OPERATION = new QName(HELLO_NS, "sayHello");

    protected static final String EXTERNAL_ENDPOINT_NAME = "externalHelloEndpoint";

    protected static final String TEST_TRANSPORT_PORT = "7501";

    protected static final String TEST_TRANSPORT_NAME = "test-transport";

    protected static final String TEST_CONSUMER_DOMAIN = "test-consumer-domain";

    protected static final String TEST_AUTH_NAME = "test-auth-name";

    protected static final long DEFAULT_TIMEOUT_FOR_COMPONENT_SEND = 2000;

    protected static final InMemoryLogHandler IN_MEMORY_LOG_HANDLER = new InMemoryLogHandler();

    private static final ComponentConfiguration CONFIGURATION = new ComponentConfiguration("JBI-Gateway") {
        public void extraJBIConfiguration(final @Nullable Document jbiDocument) {
            assert jbiDocument != null;

            final Element compo = getComponentElement(jbiDocument);

            final Element transport = addElement(jbiDocument, compo, JbiGatewayJBIHelper.EL_TRANSPORT_LISTENER);
            transport.setAttribute(JbiGatewayJBIHelper.ATTR_TRANSPORT_LISTENER_ID, TEST_TRANSPORT_NAME);

            addOrReplaceElement(jbiDocument, transport, JbiGatewayJBIHelper.EL_TRANSPORT_LISTENER_PORT,
                    TEST_TRANSPORT_PORT);
        }
    };

    protected static final Component COMPONENT_UNDER_TEST = new ComponentUnderTest(CONFIGURATION)
            // we need faster checks for our tests, 2000 is too long!
            .setParameter(new QName(JbiConstants.CDK_NAMESPACE_URI, "time-beetween-async-cleaner-runs"), "100")
            .addLogHandler(IN_MEMORY_LOG_HANDLER.getHandler());

    /**
     * We use a class rule (i.e. static) so that the component lives during all the tests, this enables to test also
     * that successive deploy and undeploy do not create problems.
     */
    @ClassRule
    public static final TestRule chain = RuleChain.outerRule(IN_MEMORY_LOG_HANDLER).around(COMPONENT_UNDER_TEST);

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

        COMPONENT_UNDER_TEST.undeployAllServices();

        // asserts are ALWAYS a bug!
        final Formatter formatter = new SimpleFormatter();
        for (final LogRecord r : IN_MEMORY_LOG_HANDLER.getAllRecords()) {
            assertFalse("Got a log with an assertion: " + formatter.format(r),
                    r.getThrown() instanceof AssertionError || r.getMessage().contains("AssertionError"));
        }
    }

    protected static ServiceConfiguration createHelloConsumes() {
        final ServiceConfiguration consumes = new ServiceConfiguration(HELLO_INTERFACE, HELLO_SERVICE,
                EXTERNAL_ENDPOINT_NAME, ServiceType.CONSUME) {
            @Override
            public void extraJBIConfiguration(final @Nullable Document jbiDocument) {
                assert jbiDocument != null;

                final Element services = getOrCreateServicesElement(jbiDocument);

                final Element cDomain = addOrReplaceElement(jbiDocument, services,
                        JbiGatewayJBIHelper.EL_SERVICES_CONSUMER_DOMAIN);
                cDomain.setAttribute(JbiGatewayJBIHelper.ATTR_SERVICES_CONSUMER_DOMAIN_ID, TEST_CONSUMER_DOMAIN);
                cDomain.setAttribute(JbiGatewayJBIHelper.ATTR_SERVICES_CONSUMER_DOMAIN_TRANSPORT, TEST_TRANSPORT_NAME);
                
                addOrReplaceElement(jbiDocument, cDomain, JbiGatewayJBIHelper.EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME,
                        TEST_AUTH_NAME);
            }
        };

        consumes.setOperation(HELLO_OPERATION.getLocalPart());
        consumes.setMEP(MEPType.IN_OUT);
        // let's use a smaller timeout time by default
        consumes.setTimeout(DEFAULT_TIMEOUT_FOR_COMPONENT_SEND);
        consumes.setParameter(JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN, TEST_CONSUMER_DOMAIN);

        return consumes;
    }
}
