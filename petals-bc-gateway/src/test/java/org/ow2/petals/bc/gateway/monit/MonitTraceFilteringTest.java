/**
 * Copyright (c) 2020-2026 Linagora
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
package org.ow2.petals.bc.gateway.monit;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.namespace.QName;

import org.awaitility.Awaitility;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.InvalidMessage;
import org.ow2.petals.ObjectFactory;
import org.ow2.petals.PrintHello;
import org.ow2.petals.SayHello;
import org.ow2.petals.SayHelloResponse;
import org.ow2.petals.bc.gateway.AbstractEnvironmentTest;
import org.ow2.petals.bc.gateway.BcGatewayJbiTestConstants;
import org.ow2.petals.bc.gateway.junit.extensions.EnsurePortsAreOKExtension;
import org.ow2.petals.bc.gateway.junit.extensions.api.EnsurePortsAreOK;
import org.ow2.petals.bc.gateway.utils.BcGatewayJbiConstants;
import org.ow2.petals.commons.log.FlowLogData;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.commons.log.PetalsExecutionContext;
import org.ow2.petals.component.framework.api.util.MEPUtil;
import org.ow2.petals.component.framework.jbidescriptor.generated.MEPType;
import org.ow2.petals.component.framework.junit.Component;
import org.ow2.petals.component.framework.junit.Message;
import org.ow2.petals.component.framework.junit.RequestMessage;
import org.ow2.petals.component.framework.junit.ResponseMessage;
import org.ow2.petals.component.framework.junit.StatusMessage;
import org.ow2.petals.component.framework.junit.extensions.ComponentConfigurationExtension;
import org.ow2.petals.component.framework.junit.extensions.ComponentUnderTestExtension;
import org.ow2.petals.component.framework.junit.extensions.api.ComponentUnderTest;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;
import org.ow2.petals.component.framework.junit.impl.ComponentConfiguration;
import org.ow2.petals.component.framework.junit.impl.ConsumesServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.PCServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.ProvidesServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.message.RequestToProviderMessage;
import org.ow2.petals.component.framework.junit.impl.message.StatusToProviderMessage;
import org.ow2.petals.component.framework.junit.monitoring.business.filtering.AbstractMonitTraceFilteringTestForServiceProvider;
import org.ow2.petals.component.framework.junit.monitoring.business.filtering.ServiceProviderReturningFault;
import org.ow2.petals.component.framework.junit.monitoring.business.filtering.ServiceProviderReturningOut;
import org.ow2.petals.component.framework.junit.monitoring.business.filtering.ServiceProviderReturningStatus;
import org.ow2.petals.component.framework.junit.monitoring.business.filtering.exception.ServiceProviderCfgCreationError;
import org.ow2.petals.component.framework.test.Assert;
import org.ow2.petals.junit.extensions.log.handler.InMemoryLogHandlerExtension;
import org.supercsv.cellprocessor.constraint.IsIncludedIn;
import org.supercsv.cellprocessor.constraint.StrNotNullOrEmpty;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.comment.CommentStartsWith;
import org.supercsv.io.CsvBeanReader;
import org.supercsv.io.ICsvBeanReader;
import org.supercsv.prefs.CsvPreference;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import jakarta.xml.bind.Marshaller;

/**
 * <p>
 * Unit tests about MONIT trace filtering.
 * </p>
 * 
 * @author Christophe DENEUX - Linagora
 */
public class MonitTraceFilteringTest extends AbstractMonitTraceFilteringTestForServiceProvider {

    protected static class BasicComponentConfiguration extends ComponentConfiguration {

        public BasicComponentConfiguration(final String configurationName) {
            super(configurationName);
        }

        @Override
        protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
            assert jbiDocument != null;

            final Element compo = getComponentElement(jbiDocument);

            final Element transport = addElement(jbiDocument, compo, BcGatewayJbiConstants.EL_TRANSPORT_LISTENER);
            transport.setAttribute(BcGatewayJbiTestConstants.ATTR_TRANSPORT_LISTENER_ID,
                    AbstractEnvironmentTest.TEST_TRANSPORT_NAME);
            addElement(jbiDocument, transport, BcGatewayJbiTestConstants.EL_TRANSPORT_LISTENER_PORT,
                    "" + BcGatewayJbiTestConstants.DEFAULT_PORT);
        }
    }

    private static Logger LOG = Logger.getLogger(MonitTraceFilteringTest.class.getName());

    @Order(0)
    @EnsurePortsAreOKExtension(ports = { BcGatewayJbiTestConstants.DEFAULT_PORT, AbstractEnvironmentTest.DEFAULT_PORT })
    private EnsurePortsAreOK ENSURE_PORTS_ARE_OK;

    @Order(2)
    @ComponentUnderTestExtension(
            inMemoryLogHandler = @InMemoryLogHandlerExtension, componentConfiguration = @ComponentConfigurationExtension(
                    name = "ConsumerDomainComponent"
            )
    )
    private ComponentUnderTest cutConsumerDomain;

    @Order(1)
    @ComponentUnderTestExtension(
            inMemoryLogHandler = @InMemoryLogHandlerExtension, componentConfiguration = @ComponentConfigurationExtension(
                    name = "ProviderDomainComponent", implementation = BasicComponentConfiguration.class
            )
    )
    private ComponentUnderTest cutProviderDomain;

    @BeforeEach
    private void completesComponentUnderTestConfiguration() throws Exception {
        this.cutProviderDomain.registerExternalServiceProvider(AbstractEnvironmentTest.EXTERNAL_HELLO_ENDPOINT,
                AbstractEnvironmentTest.HELLO_SERVICE, AbstractEnvironmentTest.HELLO_INTERFACE);
    }

    private ProvidesServiceConfiguration proxyServiceEndpoint;

    /**
     * <p>
     * Check the MONIT trace filtering.
     * </p>
     * <p>
     * Note: According to the Petals CDK JUnit, the flow tracing configuration of the <b>service provider consumed</b>
     * depends on the received exchange. If it contains the property
     * {@value org.ow2.petals.component.framework.api.Message#FLOW_TRACING_ACTIVATION_MSGEX_PROP}, its value is used,
     * otherwise the flow tracing is enabled.
     * </p>
     */
    @Test
    public void monitTracesFiltering() throws Exception {

        final InputStream isMonitTraceFilteringRules = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("org/ow2/petals/bc/gateway/monit/monitTraceFilteringRules.csv");
        assertNotNull(isMonitTraceFilteringRules);
        final List<MonitTraceFilteringRule> monitTraceFilteringRules = this
                .readMonitTraceFileteringRules(isMonitTraceFilteringRules);
        int ruleCpt = 0;
        for (final MonitTraceFilteringRule rule : monitTraceFilteringRules) {
            this.executeRule(rule, ++ruleCpt);
        }
    }

    /**
     * Read the given MONIT flow tracing rule file.
     * 
     * @param isMonitTraceFilteringRules
     *            Input stream containing MONIT flow tracing rules.
     * @return All MONIT flow tracing rules defined
     * @throws IOException
     *             An error occurs reading the file
     */
    private List<MonitTraceFilteringRule> readMonitTraceFileteringRules(final InputStream isMonitTraceFilteringRules)
            throws IOException {

        final CellProcessor[] processors = new CellProcessor[] {
                // consumer domain - component configuration - flow tracing activation
                new StrNotNullOrEmpty(
                        new IsIncludedIn(new String[] { Boolean.TRUE.toString(), Boolean.FALSE.toString(), "-" })),
                // consumer domain - component configuration - flow tracing propagation
                new StrNotNullOrEmpty(
                        new IsIncludedIn(new String[] { Boolean.TRUE.toString(), Boolean.FALSE.toString(), "-" })),

                // provider domain - component configuration - flow tracing activation
                new StrNotNullOrEmpty(
                        new IsIncludedIn(new String[] { Boolean.TRUE.toString(), Boolean.FALSE.toString(), "-" })),
                // provider domain - component configuration - flow tracing propagation
                new StrNotNullOrEmpty(
                        new IsIncludedIn(new String[] { Boolean.TRUE.toString(), Boolean.FALSE.toString(), "-" })),

                // service consumer configuration - flow tracing activation
                new StrNotNullOrEmpty(
                        new IsIncludedIn(new String[] { Boolean.TRUE.toString(), Boolean.FALSE.toString(), "-" })),
                // service consumer configuration - flow tracing propagation
                new StrNotNullOrEmpty(
                        new IsIncludedIn(new String[] { Boolean.TRUE.toString(), Boolean.FALSE.toString(), "-" })),

                // message exchange configuration - flow tracing activation
                new StrNotNullOrEmpty(
                        new IsIncludedIn(new String[] { Boolean.TRUE.toString(), Boolean.FALSE.toString(), "-" })),

                // expected results - MONIT traces logged for service provider of the consumer domain
                new StrNotNullOrEmpty(new IsIncludedIn(new String[] { "Yes", "No" })),
                // expected results - MONIT traces logged for service consumer of the provider domain
                new StrNotNullOrEmpty(new IsIncludedIn(new String[] { "Yes", "No" })),
                // expected results - MONIT traces logged for service provider consumed
                new StrNotNullOrEmpty(new IsIncludedIn(new String[] { "Yes", "No" })),
                // expected results - Flow tracing activation state in exchange invoking the service provider consumed
                new StrNotNullOrEmpty(new IsIncludedIn(new String[] { "Enabled", "Disabled", "Empty" })) };

        final List<MonitTraceFilteringRule> monitTraceFilteringRules = new ArrayList<>();
        try (final ICsvBeanReader beanReader = new CsvBeanReader(new InputStreamReader(isMonitTraceFilteringRules),
                new CsvPreference.Builder('"', ',', "\r\n").skipComments(new CommentStartsWith("#")).build())) {

            // the header elements are used to map the values to the bean (names must match)
            final String[] header = beanReader.getHeader(true);

            monitTraceFilteringRules.clear();
            MonitTraceFilteringRule rule;
            while ((rule = beanReader.read(MonitTraceFilteringRule.class, header, processors)) != null) {
                monitTraceFilteringRules.add(rule);
            }
        }
        return monitTraceFilteringRules;
    }

    /**
     * Execute a flow tracing filtering rule
     * 
     * @param rule
     *            MONIT flow tracing rule.
     * @param ruleIdx
     *            MONIT flow tracing rule index.
     */
    private void executeRule(final MonitTraceFilteringRule rule, final int ruleIdx) throws Exception {
        final Optional<Boolean> compConsDomEnableFlowTracing = parseAsOptional(rule.compConsDomEnableFlowTracing);
        final Optional<Boolean> compConsDomEnableFlowTracingPropagation = parseAsOptional(
                rule.compConsDomEnableFlowTracingPropagation);
        final Optional<Boolean> compProvDomEnableFlowTracing = parseAsOptional(rule.compProvDomEnableFlowTracing);
        final Optional<Boolean> compProvDomEnableFlowTracingPropagation = parseAsOptional(
                rule.compProvDomEnableFlowTracingPropagation);
        final Optional<Boolean> consEnableFlowTracing = parseAsOptional(rule.consEnableFlowTracing);
        final Optional<Boolean> consEnableFlowTracingPropagation = parseAsOptional(
                rule.consEnableFlowTracingPropagation);

        configureComponent(this.cutConsumerDomain, compConsDomEnableFlowTracing,
                compConsDomEnableFlowTracingPropagation);
        configureComponent(this.cutProviderDomain, compProvDomEnableFlowTracing,
                compProvDomEnableFlowTracingPropagation);

        for (final MEPPatternConstants mep : this.getMepsSupported()) {
            final MEPType mepType = MEPUtil.convert(mep);
            assert mepType != null;

            final String ruleIdPrefix = "Rule #" + ruleIdx + ", Mep: " + mepType.value() + ": ";
            LOG.info(ruleIdPrefix + "Deploying environment to execute rule ...");
            final PCServiceConfiguration providerServiceCfg = this.deployAndStartSUs(ruleIdPrefix, mepType,
                    consEnableFlowTracing, consEnableFlowTracingPropagation);

            // We send the request to the service of the component under test, wait the response or status, and
            // eventually return status
            switch (mep) {
                case IN_OUT:
                    this.executeRuleAsInOut(rule, ruleIdPrefix);
                    break;
                case IN_ONLY:
                    this.executeRuleAsInOnly(rule, ruleIdPrefix);
                    break;
                case ROBUST_IN_ONLY:
                    this.executeRuleAsRobustInOnly(rule, ruleIdPrefix);
                    break;
                default:
                    fail(ruleIdPrefix + "Unsupported MEP: " + mep.toString());
                    break;
            }

            LOG.info(ruleIdPrefix + "Undeploying environment to execute rule ...");
            this.stopAndUndeploySUs();
        }
    }

    /**
     * Configure a component
     * 
     * @param cut
     *            The component under test to configure
     * @param enableFlowTracing
     *            Flow tracing activation state to configure
     * @param enableFlowTracingPropagation
     *            Flow tracing activation propagation to configure
     */
    private static void configureComponent(final Component cut, final Optional<Boolean> enableFlowTracing,
            final Optional<Boolean> enableFlowTracingPropagation) throws Exception {
        if (enableFlowTracing.isPresent()) {
            cut.setRuntimeParameter("activateFlowTracing", Boolean.toString(enableFlowTracing.get()));
        } else {
            // Flow tracing activation is set with its default value
            cut.setRuntimeParameter("activateFlowTracing", Boolean.TRUE.toString());
        }
        if (enableFlowTracingPropagation.isPresent()) {
            cut.setRuntimeParameter("propagateFlowTracingActivation",
                    Boolean.toString(enableFlowTracingPropagation.get()));
        } else {
            // Flow tracing propagation is set with its default value
            cut.setRuntimeParameter("propagateFlowTracingActivation", Boolean.TRUE.toString());
        }
    }

    /**
     * Deploy and start required service units on components.
     * 
     * @param ruleIdx
     *            MONIT flow tracing rule index.
     * @param mepType
     *            Current MEP of the current test rule.
     * @param consEnableFlowTracing
     *            Flow tracing activation state to set on the service consumer of the provider domain
     * @param consEnableFlowTracingPropagation
     *            Propagation of the flow tracing activation to set on the service consumer of the provider domain
     * @return The service provider to test running on the component under test.
     */
    private ProvidesServiceConfiguration deployAndStartSUs(final String ruleIdPrefix, final MEPType mepType,
            final Optional<Boolean> consEnableFlowTracing, final Optional<Boolean> consEnableFlowTracingPropagation)
            throws Exception {

        final ConsumesServiceConfiguration consumerServiceCfg = AbstractEnvironmentTest.createHelloConsumes(true, true,
                Long.valueOf(30000));
        if (consEnableFlowTracing.isPresent()) {
            consumerServiceCfg.setParameter(
                    new QName("http://petals.ow2.org/components/extensions/version-5", "activate-flow-tracing"),
                    Boolean.toString(consEnableFlowTracing.get()));
        }
        if (consEnableFlowTracingPropagation.isPresent()) {
            consumerServiceCfg.setParameter(
                    new QName("http://petals.ow2.org/components/extensions/version-5",
                            "propagate-flow-tracing-activation"),
                    Boolean.toString(consEnableFlowTracingPropagation.get()));
        }
        this.cutProviderDomain.deployService(AbstractEnvironmentTest.SU_CONSUMER_NAME, consumerServiceCfg);

        this.cutConsumerDomain.deployService(AbstractEnvironmentTest.SU_PROVIDER_NAME, AbstractEnvironmentTest
                .createProvider(AbstractEnvironmentTest.TEST_AUTH_NAME, BcGatewayJbiTestConstants.DEFAULT_PORT));

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return AbstractEnvironmentTest.getPropagatedServiceEndpoint(cutConsumerDomain) != null;
            }
        });

        final ServiceEndpoint notExtSvcEdp = AbstractEnvironmentTest
                .getPropagatedServiceEndpoint(this.cutConsumerDomain);
        this.proxyServiceEndpoint = new ProvidesServiceConfiguration(notExtSvcEdp.getInterfaces()[0],
                notExtSvcEdp.getServiceName(), notExtSvcEdp.getEndpointName());

        return this.proxyServiceEndpoint;
    }

    /**
     * <p>
     * Execute the rule with a MEP 'InOut'.
     * </p>
     * <p>
     * Three invocations are done:
     * </p>
     * <ul>
     * <li>a nominal response is returned by the service provider consumed,</li>
     * <li>a fault is returned by the service provider consumed,</li>
     * <li>an error is returned by the service provider consumed.</li>
     * </ul>
     * 
     * @param rule
     *            MONIT flow tracing rule.
     * @param ruleIdPrefix
     *            MONIT flow tracing rule index prefix to use in assertion messages.
     */
    private void executeRuleAsInOut(final MonitTraceFilteringRule rule, final String ruleIdPrefix) throws Exception {

        this.executeServiceInvocationWithResponse(MEPPatternConstants.IN_OUT, rule, ruleIdPrefix);
        this.executeServiceInvocationWithFault(MEPPatternConstants.IN_OUT, rule, ruleIdPrefix);
        // TODO: Enable the following execution
        /*
         * this.executeServiceInvocationWithStatus(MEPPatternConstants.IN_OUT, rule, ruleIdPrefix, providerServiceCfg,
         * ExchangeStatus.ERROR);
         */
    }

    /**
     * <p>
     * Execute the rule with a MEP 'InOnly'.
     * </p>
     * <p>
     * Two invocations are done:
     * </p>
     * <ul>
     * <li>the status 'DONE' is returned by the service provider consumed,</li>
     * <li>the status 'ERROR' is returned by the service provider consumed.</li>
     * </ul>
     * 
     * @param rule
     *            MONIT flow tracing rule.
     * @param ruleIdPrefix
     *            MONIT flow tracing rule index prefix to use in assertion messages.
     */
    private void executeRuleAsInOnly(final MonitTraceFilteringRule rule, final String ruleIdPrefix) throws Exception {

        this.executeServiceInvocationWithStatus(MEPPatternConstants.IN_ONLY, rule, ruleIdPrefix, ExchangeStatus.DONE);
        /*
         * this.executeServiceInvocationWithStatus(MEPPatternConstants.IN_ONLY, rule, ruleIdPrefix, providerServiceCfg,
         * ExchangeStatus.ERROR);
         */
    }

    /**
     * <p>
     * Execute the rule with a MEP 'RobustInOnly'.
     * </p>
     * <p>
     * Two invocations are done:
     * </p>
     * <ul>
     * <li>a fault is returned by the service provider consumed,</li>
     * <li>the status 'DONE' is returned by the service provider consumed,</li>
     * <li>the status 'ERROR' is returned by the service provider consumed.</li>
     * </ul>
     * 
     * @param rule
     *            MONIT flow tracing rule.
     * @param ruleIdPrefix
     *            MONIT flow tracing rule index prefix to use in assertion messages.
     */
    private void executeRuleAsRobustInOnly(final MonitTraceFilteringRule rule, final String ruleIdPrefix)
            throws Exception {
        /*
         * this.executeServiceInvocationWithStatus(MEPPatternConstants.ROBUST_IN_ONLY, rule, ruleIdPrefix,
         * providerServiceCfg, ExchangeStatus.DONE);
         * this.executeServiceInvocationWithStatus(MEPPatternConstants.ROBUST_IN_ONLY, rule, ruleIdPrefix,
         * providerServiceCfg, ExchangeStatus.ERROR);
         */
        this.executeServiceInvocationWithFault(MEPPatternConstants.ROBUST_IN_ONLY, rule, ruleIdPrefix);
    }

    /**
     * Stop and undeploy service units deployed and started with
     * {@link #deployAndStartSUs(String, MEPType, Optional, Optional)}.
     */
    private void stopAndUndeploySUs() {
        this.cutConsumerDomain.undeployService(AbstractEnvironmentTest.SU_PROVIDER_NAME);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return AbstractEnvironmentTest.getPropagatedServiceEndpoint(cutConsumerDomain) == null;
            }
        });
        this.proxyServiceEndpoint = null;

        this.cutProviderDomain.undeployService(AbstractEnvironmentTest.SU_CONSUMER_NAME);
    }

    @Override
    protected void clearLogTraces() {
        this.cutConsumerDomain.getInMemoryLogHandler().clear();
        this.cutProviderDomain.getInMemoryLogHandler().clear();
    }

    @Override
    protected List<LogRecord> getMonitTraces() {
        final Comparator<LogRecord> comparator = (logRecord1, logRecord2) -> {
            if (logRecord2.getSequenceNumber() < logRecord1.getSequenceNumber()) {
                return 1;
            } else if (logRecord2.getSequenceNumber() == logRecord1.getSequenceNumber()) {
                // Should not occurs
                return 0;
            } else {
                return -1;
            }
        };
        final Set<LogRecord> logRecords = new TreeSet<>(comparator);
        logRecords.addAll(this.cutConsumerDomain.getInMemoryLogHandler().getAllRecords(Level.MONIT));
        logRecords.addAll(this.cutProviderDomain.getInMemoryLogHandler().getAllRecords(Level.MONIT));
        return new ArrayList<>(logRecords);
    }

    /**
     * <p>
     * Execute a service invocation for the given request. A nominal response is returned by the service provider
     * consumed.
     * </p>
     * 
     * @param mep
     *            MEP to use for the exchange.
     * @param rule
     *            MONIT flow tracing rule.
     * @param ruleIdPrefix
     *            Rule index prefix to use in assertion messages.
     */
    private void executeServiceInvocationWithResponse(final MEPPatternConstants mep, final MonitTraceFilteringRule rule,
            final String ruleIdPrefix) throws Exception {

        final String newRuleIdPrefix = ruleIdPrefix + "Response returned: ";

        LOG.info(newRuleIdPrefix + "Executing rule ...");
        this.clearLogTraces();

        final Optional<Boolean> expectedFlowTracingActivationState = parseExpectedResultAsOptional(
                rule.expectedFlowTracingActivationState);
        final boolean isConsDomProvMonitTraceLogged = parseExpectedResultAsBool(rule.isConsDomProvMonitTraceLogged);
        final boolean isProvDomConsMonitTraceLogged = parseExpectedResultAsBool(rule.isProvDomConsMonitTraceLogged);
        final boolean isProvMonitTraceExpected = parseExpectedResultAsBool(rule.isProvMonitTraceLogged);

        this.onExchangeExecutionStart(newRuleIdPrefix, ExpectedResponseType.NOMINAL_RESPONSE);

        final RequestToProviderMessage incomingRequest = this.createRequestMessage(mep, rule);
        this.executeExchangeReturningResponse(incomingRequest,
                new ServiceProviderReturningOut(this.createResponsePayloadToProvider(mep, false), this.getMarshaller(),
                        expectedFlowTracingActivationState, newRuleIdPrefix),
                newRuleIdPrefix);

        // Check MONIT traces
        final List<LogRecord> monitLogs = this.getMonitTraces();
        this.assertMonitTraces(newRuleIdPrefix, monitLogs, isConsDomProvMonitTraceLogged, isProvDomConsMonitTraceLogged,
                isProvMonitTraceExpected, false, mep);

        LOG.info(newRuleIdPrefix + "Rule executed.");
    }

    /**
     * <p>
     * Execute a service invocation for the given request. A fault is returned by the service provider consumed.
     * </p>
     * 
     * @param mep
     *            MEP to use for the exchange.
     * @param rule
     *            MONIT flow tracing rule.
     * @param ruleIdPrefix
     *            MONIT flow tracing rule index prefix to use in assertion messages.
     */
    private void executeServiceInvocationWithFault(final MEPPatternConstants mep, final MonitTraceFilteringRule rule,
            final String ruleIdPrefix) throws Exception {

        final String newRuleIdPrefix = ruleIdPrefix + "Fault returned: ";

        LOG.info(newRuleIdPrefix + "Executing rule ...");
        this.clearLogTraces();

        final Optional<Boolean> expectedFlowTracingActivationState = parseExpectedResultAsOptional(
                rule.expectedFlowTracingActivationState);
        final boolean isConsDomProvMonitTraceLogged = parseExpectedResultAsBool(rule.isConsDomProvMonitTraceLogged);
        final boolean isProvDomConsMonitTraceLogged = parseExpectedResultAsBool(rule.isProvDomConsMonitTraceLogged);
        final boolean isProvMonitTraceExpected = parseExpectedResultAsBool(rule.isProvMonitTraceLogged);

        this.onExchangeExecutionStart(newRuleIdPrefix, ExpectedResponseType.FAULT);

        final RequestToProviderMessage incomingRequest = this.createRequestMessage(mep, rule);
        this.executeExchangeReturningFault(incomingRequest,
                new ServiceProviderReturningFault(this.createResponsePayloadToProvider(mep, true), this.getMarshaller(),
                        expectedFlowTracingActivationState, newRuleIdPrefix),
                newRuleIdPrefix);

        // Check MONIT traces
        final List<LogRecord> monitLogs = this.getMonitTraces();
        this.assertMonitTraces(newRuleIdPrefix, monitLogs, isConsDomProvMonitTraceLogged, isProvDomConsMonitTraceLogged,
                isProvMonitTraceExpected, true, mep);

        LOG.info(newRuleIdPrefix + "Rule executed.");
    }

    /**
     * <p>
     * Execute a service invocation for the given request. The given status is returned by the service provider
     * consumed.
     * </p>
     * 
     * @param mep
     *            MEP to use for the exchange with service provider consumed.
     * @param rule
     *            MONIT flow tracing rule.
     * @param ruleIdPrefix
     *            MONIT flow tracing rule index prefix to use in assertion messages.
     * @param statusToReturn
     *            The status to return by the service provider consumed.
     */
    private void executeServiceInvocationWithStatus(final MEPPatternConstants mep, final MonitTraceFilteringRule rule,
            final String ruleIdPrefix, final ExchangeStatus statusToReturn) throws Exception {

        final String newRuleIdPrefix = ruleIdPrefix + statusToReturn.toString() + " returned: ";

        LOG.info(newRuleIdPrefix + "Executing rule ...");
        this.clearLogTraces();

        final Optional<Boolean> expectedFlowTracingActivationState = parseExpectedResultAsOptional(
                rule.expectedFlowTracingActivationState);
        final boolean isConsDomProvMonitTraceLogged = parseExpectedResultAsBool(rule.isConsDomProvMonitTraceLogged);
        final boolean isProvDomConsMonitTraceLogged = parseExpectedResultAsBool(rule.isProvDomConsMonitTraceLogged);
        final boolean isProvMonitTraceExpected = parseExpectedResultAsBool(rule.isProvMonitTraceLogged);

        final ExpectedResponseType expectedResponseType = (statusToReturn == ExchangeStatus.DONE
                ? ExpectedResponseType.DONE_STATUS
                : ExpectedResponseType.ERROR_STATUS);

        this.onExchangeExecutionStart(newRuleIdPrefix, expectedResponseType);

        final RequestToProviderMessage incomingRequest = this.createRequestMessage(mep, rule);
        this.executeExchangeReturningStatus(incomingRequest,
                new ServiceProviderReturningStatus(statusToReturn, expectedFlowTracingActivationState, newRuleIdPrefix),
                newRuleIdPrefix);

        // Check MONIT traces
        final List<LogRecord> monitLogs = this.getMonitTraces();
        this.assertMonitTraces(newRuleIdPrefix, monitLogs, isConsDomProvMonitTraceLogged, isProvDomConsMonitTraceLogged,
                isProvMonitTraceExpected, statusToReturn == ExchangeStatus.ERROR, mep);

        LOG.info(newRuleIdPrefix + "Rule executed.");
    }

    /**
     * Execute the exchange with a response returned by the service provider consumed.
     * 
     * @param incomingRequest
     *            The request to send to the service provider running on the component under test.
     * @param mock
     *            A default service provider consumed implementation returning an OUT response.
     * @param ruleIdPrefix
     *            MONIT flow tracing rule index prefix to use in assertion messages.
     */
    private void executeExchangeReturningResponse(final RequestToProviderMessage incomingRequest,
            final ServiceProviderImplementation mock, final String ruleIdPrefix) throws Exception {

        PetalsExecutionContext.clear();

        // Send request from service provider on consumer domain
        this.cutConsumerDomain.pushRequestToProvider(incomingRequest);

        // Receive the request as external service provider
        final RequestMessage requestFromConsumer = this.cutProviderDomain.pollRequestFromConsumer();
        Assert.assertNotNull(requestFromConsumer);

        // Return response to the service provider on consumer domain
        final Message responseToConsumer = mock.provides(requestFromConsumer);
        if (responseToConsumer instanceof ResponseMessage) {
            this.cutProviderDomain.pushResponseToConsumer((ResponseMessage) responseToConsumer, false);
        } else if (responseToConsumer instanceof StatusMessage) {
            if (mock.statusExpected()) {
                Assert.fail(ruleIdPrefix
                        + "A response is expected but the external service implementation returned a status message");
            }
            this.cutProviderDomain.pushStatusToConsumer((StatusMessage) responseToConsumer, false);
        } else {
            Assert.fail(String.format(ruleIdPrefix + "Unexpected message type '%s' for: %s",
                    responseToConsumer.getClass(), responseToConsumer));
        }

        // Wait, as service provider on consumer domain, the response of the external service provider
        final ResponseMessage response = this.cutConsumerDomain.pollResponseFromProvider();

        // Return status to the external service provider
        PetalsExecutionContext.clear();
        this.cutConsumerDomain.pushStatusToProvider(new StatusToProviderMessage(response, ExchangeStatus.DONE), false);
        final StatusMessage status = this.cutProviderDomain.pollStatusFromConsumer();
        Assert.assertNotNull(status);
        mock.handleStatus(status);
    }

    /**
     * Execute the exchange with a status returned by the service provider consumes.
     * 
     * @param incomingRequest
     *            The request to send to the service provider running on the component under test.
     * @param mock
     *            A default service provider consumed implementation returning a fault.
     * @param ruleIdPrefix
     *            MONIT flow tracing rule index prefix to use in assertion messages.
     */
    private void executeExchangeReturningFault(final RequestToProviderMessage incomingRequest,
            final ServiceProviderImplementation mock, final String ruleIdPrefix) throws Exception {

        PetalsExecutionContext.clear();

        // Send request from service provider on consumer domain
        this.cutConsumerDomain.pushRequestToProvider(incomingRequest);

        // Receive the request as external service provider
        final RequestMessage requestFromConsumer = this.cutProviderDomain.pollRequestFromConsumer();
        Assert.assertNotNull(requestFromConsumer);

        // Return response to the service provider on consumer domain
        final Message responseToConsumer = mock.provides(requestFromConsumer);
        if (responseToConsumer instanceof ResponseMessage) {
            this.cutProviderDomain.pushResponseToConsumer((ResponseMessage) responseToConsumer, false);
        } else if (responseToConsumer instanceof StatusMessage) {
            if (mock.statusExpected()) {
                Assert.fail(ruleIdPrefix
                        + "A response is expected but the external service implementation returned a status message");
            }
            this.cutProviderDomain.pushStatusToConsumer((StatusMessage) responseToConsumer, false);
        } else {
            Assert.fail(String.format(ruleIdPrefix + "Unexpected message type '%s' for: %s",
                    responseToConsumer.getClass(), responseToConsumer));
        }

        // Wait, as service provider on consumer domain, the response of the external service provider
        final ResponseMessage response = this.cutConsumerDomain.pollResponseFromProvider();

        // Return status to the external service provider
        PetalsExecutionContext.clear();
        this.cutConsumerDomain.pushStatusToProvider(new StatusToProviderMessage(response, ExchangeStatus.DONE), false);
        final StatusMessage status = this.cutProviderDomain.pollStatusFromConsumer();
        Assert.assertNotNull(status);
        mock.handleStatus(status);

    }

    /**
     * Execute the exchange with a status returned by the service provider consumes.
     * 
     * @param incomingRequest
     *            The request to send to the service provider running on the component under test.
     * @param mock
     *            A default service provider consumed implementation returning the expected status.
     * @param ruleIdPrefix
     *            MONIT flow tracing rule index prefix to use in assertion messages.
     */
    protected void executeExchangeReturningStatus(final RequestToProviderMessage incomingRequest,
            final ServiceProviderImplementation mock, final String ruleIdPrefix) throws Exception {

        PetalsExecutionContext.clear();

        // Send request from service provider on consumer domain
        this.cutConsumerDomain.pushRequestToProvider(incomingRequest);

        // Receive the request as external service provider
        final RequestMessage requestFromConsumer = this.cutProviderDomain.pollRequestFromConsumer();
        Assert.assertNotNull(requestFromConsumer);

        // Return response to the service provider on consumer domain
        final Message responseToConsumer = mock.provides(requestFromConsumer);
        if (responseToConsumer instanceof ResponseMessage) {
            this.cutProviderDomain.pushResponseToConsumer((ResponseMessage) responseToConsumer, false);
        } else if (responseToConsumer instanceof StatusMessage) {
            if (mock.statusExpected()) {
                Assert.fail(ruleIdPrefix
                        + "A response is expected but the external service implementation returned a status message");
            }
            this.cutProviderDomain.pushStatusToConsumer((StatusMessage) responseToConsumer, false);
        } else {
            Assert.fail(String.format(ruleIdPrefix + "Unexpected message type '%s' for: %s",
                    responseToConsumer.getClass(), responseToConsumer));
        }

        // Wait, as service provider on consumer domain, the response of the external service provider
        this.cutConsumerDomain.pollStatusFromProvider();
    }

    /**
     * Create a request message according to the given MEP.
     * 
     * @param mep
     *            Current MEP of the current test rule.
     * @param rule
     *            MONIT flow tracing rule.
     */
    private RequestToProviderMessage createRequestMessage(final MEPPatternConstants mep,
            final MonitTraceFilteringRule rule) {
        final Optional<Boolean> msgEnableFlowTracing = parseAsOptional(rule.msgEnableFlowTracing);
        final Properties requestProps = new Properties();
        if (msgEnableFlowTracing.isPresent()) {
            requestProps.put(org.ow2.petals.component.framework.api.Message.FLOW_TRACING_ACTIVATION_MSGEX_PROP,
                    msgEnableFlowTracing.get());
        }
        return this.createRequestToProviderMessage(mep, requestProps);
    }

    @Override
    protected ProvidesServiceConfiguration createServiceProvider(final int ruleIdx)
            throws ServiceProviderCfgCreationError {

        // Not used because it is automatically deploy by endpoint propagation though gateway
        return null;
    }

    @Override
    protected QName getInvokedServiceProviderOperation(final MEPPatternConstants mep) {
        if (mep == MEPPatternConstants.IN_OUT) {
            return AbstractEnvironmentTest.HELLO_OPERATION;
        } else {
            return AbstractEnvironmentTest.PRINT_OPERATION;
        }
    }

    @Override
    protected Marshaller getMarshaller() {
        return AbstractEnvironmentTest.MARSHALLER;
    }

    @Override
    protected MEPPatternConstants[] getMepsSupported() {
        return new MEPPatternConstants[] { MEPPatternConstants.IN_ONLY, MEPPatternConstants.IN_OUT,
                MEPPatternConstants.ROBUST_IN_ONLY };
    }

    private RequestToProviderMessage createRequestToProviderMessage(final MEPPatternConstants mep,
            final Properties requestProps) {
        return new RequestToProviderMessage(this.cutConsumerDomain, this.proxyServiceEndpoint.getEndpointName(),
                this.proxyServiceEndpoint.getServiceName(), this.proxyServiceEndpoint.getInterfaceName(),
                this.getInvokedServiceProviderOperation(mep), mep.value(), this.createRequestPayloadToProvider(mep),
                this.getMarshaller(), requestProps);

    }

    /**
     * <p>
     * Create the payload to put in the request to send to the service provider.
     * </p>
     * 
     * @param mep
     *            The current MEP under test.
     */

    private Object createRequestPayloadToProvider(final MEPPatternConstants mep) {
        if (mep == MEPPatternConstants.IN_OUT) {
            final SayHello sayHello = new SayHello();
            sayHello.setArg0("hallo");
            return new ObjectFactory().createSayHello(sayHello);
        } else {
            final PrintHello printHello = new PrintHello();
            printHello.setArg0("hallo");
            return new ObjectFactory().createPrintHello(printHello);
        }
    }

    /**
     * <p>
     * Create the payload to put in the response to return to the service provider from the service provider consumed in
     * case of a nominal response to return.
     * </p>
     * 
     * @param mep
     *            The current MEP under test.
     * @param useAsFault
     *            The payload created will be used as fault ({@code true}) or as nominal response ({@code false)}.
     */
    private Object createResponsePayloadToProvider(final MEPPatternConstants mep, final boolean useAsFault) {
        if (mep == MEPPatternConstants.IN_OUT) {
            if (useAsFault) {
                final InvalidMessage fault = new InvalidMessage();
                fault.setReturn("invalid-value");
                return new ObjectFactory().createInvalidMessage(fault);
            } else {
                final SayHelloResponse response = new SayHelloResponse();
                response.setReturn("hallo");
                return new ObjectFactory().createSayHelloResponse(response);
            }
        } else {
            // mep == RobustInOnly && useAsFault == true
            final InvalidMessage fault = new InvalidMessage();
            fault.setReturn("invalid-value");
            return new ObjectFactory().createInvalidMessage(fault);
        }
    }

    private void assertMonitTraces(final String ruleIdPrefix, final List<LogRecord> monitLogs,
            final boolean isConsDomProvMonitTraceLogged, final boolean isProvDomConsMonitTraceLogged,
            final boolean isProvMonitTraceExpected, final boolean isFailureExpected, final MEPPatternConstants mep) {

        final int expectedMonitLogSize = (isConsDomProvMonitTraceLogged ? 4 : 0)
                + (isProvDomConsMonitTraceLogged ? 2 : 0) + (isProvMonitTraceExpected ? 2 : 0);

        assertEquals(expectedMonitLogSize, monitLogs.size(), ruleIdPrefix);
        if (isConsDomProvMonitTraceLogged) {
            final FlowLogData consumerDomainProviderBeginFlowLogData = assertMonitProviderBeginLog(ruleIdPrefix,
                    AbstractEnvironmentTest.HELLO_INTERFACE, AbstractEnvironmentTest.HELLO_SERVICE,
                    this.proxyServiceEndpoint.getEndpointName(), this.getInvokedServiceProviderOperation(mep),
                    monitLogs.get(0));
            final FlowLogData consumerDomainProviderExtBeginFlowLogData = assertMonitProviderExtBeginLog(ruleIdPrefix,
                    consumerDomainProviderBeginFlowLogData, monitLogs.get(1));
            if (isProvDomConsMonitTraceLogged) {
                final FlowLogData consumerDomainConsumerExtBeginFlowLogData = assertMonitConsumerExtBeginLog(
                        ruleIdPrefix, consumerDomainProviderExtBeginFlowLogData, monitLogs.get(2));
                if (isProvMonitTraceExpected) {
                    final FlowLogData providedBeginFlowLogData = assertMonitProviderBeginLog(ruleIdPrefix,
                            consumerDomainConsumerExtBeginFlowLogData, AbstractEnvironmentTest.HELLO_INTERFACE,
                            AbstractEnvironmentTest.HELLO_SERVICE, AbstractEnvironmentTest.EXTERNAL_HELLO_ENDPOINT,
                            this.getInvokedServiceProviderOperation(mep), monitLogs.get(3));
                    if (!isFailureExpected) {
                        assertMonitProviderEndLog(ruleIdPrefix, providedBeginFlowLogData, monitLogs.get(4));
                        assertMonitConsumerExtEndLog(ruleIdPrefix, consumerDomainConsumerExtBeginFlowLogData,
                                monitLogs.get(5));
                        assertMonitProviderExtEndLog(ruleIdPrefix, consumerDomainProviderExtBeginFlowLogData,
                                monitLogs.get(6));
                        assertMonitProviderEndLog(ruleIdPrefix, consumerDomainProviderBeginFlowLogData,
                                monitLogs.get(7));
                    } else {
                        assertMonitProviderFailureLog(ruleIdPrefix, providedBeginFlowLogData, monitLogs.get(4));
                        assertMonitConsumerExtFailureLog(ruleIdPrefix, consumerDomainConsumerExtBeginFlowLogData,
                                monitLogs.get(5));
                        assertMonitProviderExtFailureLog(ruleIdPrefix, consumerDomainProviderExtBeginFlowLogData,
                                monitLogs.get(6));
                        assertMonitProviderFailureLog(ruleIdPrefix, consumerDomainProviderBeginFlowLogData,
                                monitLogs.get(7));
                    }
                }
            } else if (isProvMonitTraceExpected) {
                final FlowLogData providedBeginFlowLogData = assertMonitProviderBeginLogNotInFlow(ruleIdPrefix,
                        AbstractEnvironmentTest.HELLO_INTERFACE, AbstractEnvironmentTest.HELLO_SERVICE,
                        AbstractEnvironmentTest.EXTERNAL_HELLO_ENDPOINT, this.getInvokedServiceProviderOperation(mep),
                        consumerDomainProviderExtBeginFlowLogData, monitLogs.get(2));
                if (!isFailureExpected) {
                    assertMonitProviderEndLog(ruleIdPrefix, providedBeginFlowLogData, monitLogs.get(3));
                    assertMonitProviderExtEndLog(ruleIdPrefix, consumerDomainProviderExtBeginFlowLogData,
                            monitLogs.get(4));
                    assertMonitProviderEndLog(ruleIdPrefix, consumerDomainProviderBeginFlowLogData, monitLogs.get(5));
                } else {
                    assertMonitProviderFailureLog(ruleIdPrefix, providedBeginFlowLogData, monitLogs.get(3));
                    assertMonitProviderExtFailureLog(ruleIdPrefix, consumerDomainProviderExtBeginFlowLogData,
                            monitLogs.get(4));
                    assertMonitProviderFailureLog(ruleIdPrefix, consumerDomainProviderBeginFlowLogData,
                            monitLogs.get(5));
                }
            } else {
                if (!isFailureExpected) {
                    assertMonitProviderExtEndLog(ruleIdPrefix, consumerDomainProviderExtBeginFlowLogData,
                            monitLogs.get(2));
                    assertMonitProviderEndLog(ruleIdPrefix, consumerDomainProviderBeginFlowLogData, monitLogs.get(3));
                } else {
                    assertMonitProviderExtFailureLog(ruleIdPrefix, consumerDomainProviderExtBeginFlowLogData,
                            monitLogs.get(2));
                    assertMonitProviderFailureLog(ruleIdPrefix, consumerDomainProviderBeginFlowLogData,
                            monitLogs.get(3));
                }
            }
        } else if (isProvDomConsMonitTraceLogged) {
            final FlowLogData consumerDomainConsumerExtBeginFlowLogData = assertMonitConsumerExtBeginLog(ruleIdPrefix,
                    monitLogs.get(0));
            if (isProvMonitTraceExpected) {
                final FlowLogData providedBeginFlowLogData = assertMonitProviderBeginLog(ruleIdPrefix,
                        consumerDomainConsumerExtBeginFlowLogData, AbstractEnvironmentTest.HELLO_INTERFACE,
                        AbstractEnvironmentTest.HELLO_SERVICE, AbstractEnvironmentTest.EXTERNAL_HELLO_ENDPOINT,
                        this.getInvokedServiceProviderOperation(mep), monitLogs.get(1));
                if (!isFailureExpected) {
                    assertMonitProviderEndLog(ruleIdPrefix, providedBeginFlowLogData, monitLogs.get(2));
                } else {
                    assertMonitProviderFailureLog(ruleIdPrefix, providedBeginFlowLogData, monitLogs.get(2));
                }
            }
            if (!isFailureExpected) {
                assertMonitConsumerExtEndLog(ruleIdPrefix, consumerDomainConsumerExtBeginFlowLogData, monitLogs.get(3));
            } else {
                assertMonitConsumerExtFailureLog(ruleIdPrefix, consumerDomainConsumerExtBeginFlowLogData,
                        monitLogs.get(3));
            }
        } else if (isProvMonitTraceExpected) {
            final FlowLogData providedBeginFlowLogData = assertMonitProviderBeginLog(ruleIdPrefix,
                    AbstractEnvironmentTest.HELLO_INTERFACE, AbstractEnvironmentTest.HELLO_SERVICE,
                    AbstractEnvironmentTest.EXTERNAL_HELLO_ENDPOINT, this.getInvokedServiceProviderOperation(mep),
                    monitLogs.get(0));
            if (!isFailureExpected) {
                assertMonitProviderEndLog(ruleIdPrefix, providedBeginFlowLogData, monitLogs.get(1));
            } else {
                assertMonitProviderFailureLog(ruleIdPrefix, providedBeginFlowLogData, monitLogs.get(1));
            }
        } else {
            assertEquals(0, monitLogs.size(), ruleIdPrefix);
        }
    }

}
