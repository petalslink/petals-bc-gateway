/**
 * Copyright (c) 2016-2024 Linagora
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

import java.util.List;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.servicedesc.ServiceEndpoint;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.Test;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.commons.log.FlowLogData;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.component.framework.junit.Message;
import org.ow2.petals.component.framework.junit.StatusMessage;
import org.ow2.petals.component.framework.junit.helpers.MessageChecks;
import org.ow2.petals.component.framework.junit.helpers.ServiceProviderImplementation;
import org.ow2.petals.component.framework.listener.AbstractListener;

public class BcGatewaySendTest extends AbstractComponentTest {

    @Test
    public void testTimeout() throws Exception {
        final ServiceEndpoint endpoint = deployTwoDomains();

        final ServiceProviderImplementation provider = ServiceProviderImplementation.errorMessage(ERROR)
                .with(new MessageChecks() {
                    @Override
                    public void checks(final @Nullable Message message) throws Exception {
                        Thread.sleep(DEFAULT_TIMEOUT_FOR_COMPONENT_SEND + 1000);
                    }
                });

        final StatusMessage response = COMPONENT
                .sendAndGetStatus(helloRequest(endpoint, MEPPatternConstants.IN_OUT.value()), provider);

        assertEquals(ExchangeStatus.ERROR, response.getStatus());
        final Pattern msgPattern = Pattern.compile(String.format(
                "A timeout expired \\(%d ms\\) sending a message to a service provider \\(%s\\|%s\\|%s\\|%s\\) in the context of the flow step '[-0-9a-f-]*\\/[-0-9a-f-]*'",
                DEFAULT_TIMEOUT_FOR_COMPONENT_SEND, Pattern.quote(HELLO_INTERFACE.toString()),
                Pattern.quote(HELLO_SERVICE.toString()), EXTERNAL_HELLO_ENDPOINT,
                Pattern.quote(HELLO_OPERATION.toString())));
        assertTrue(msgPattern.matcher(response.getError().getMessage()).matches());

        // Check MONIT traces
        final List<LogRecord> monitLogs = AbstractComponentTestUtils.IN_MEMORY_LOG_HANDLER.getAllRecords(Level.MONIT);
        assertEquals(8, monitLogs.size());
        final FlowLogData consumerDomainProviderBeginFlowLogData = assertMonitProviderBeginLog(HELLO_INTERFACE,
                HELLO_SERVICE, endpoint.getEndpointName(), HELLO_OPERATION, monitLogs.get(0));
        final FlowLogData consumerDomainProviderExtBeginFlowLogData = assertMonitProviderExtBeginLog(
                consumerDomainProviderBeginFlowLogData, monitLogs.get(1));
        final FlowLogData consumerDomainConsumerExtBeginFlowLogData = assertMonitConsumerExtBeginLog(
                consumerDomainProviderExtBeginFlowLogData, monitLogs.get(2));
        final FlowLogData providedBeginFlowLogData = assertMonitProviderBeginLog(
                consumerDomainConsumerExtBeginFlowLogData, HELLO_INTERFACE, HELLO_SERVICE, EXTERNAL_HELLO_ENDPOINT,
                HELLO_OPERATION, monitLogs.get(3));
        assertMonitConsumerExtTimeoutLog(DEFAULT_TIMEOUT_FOR_COMPONENT_SEND, HELLO_INTERFACE, HELLO_SERVICE,
                EXTERNAL_HELLO_ENDPOINT, HELLO_OPERATION, consumerDomainConsumerExtBeginFlowLogData, monitLogs.get(4));
        assertMonitProviderExtFailureLog(consumerDomainProviderExtBeginFlowLogData, monitLogs.get(5));
        assertMonitProviderFailureLog(consumerDomainProviderBeginFlowLogData, monitLogs.get(6));
        assertMonitProviderFailureLog(providedBeginFlowLogData, monitLogs.get(7));

        // Assertion about the timeout warning message
        final List<LogRecord> warnRecords = AbstractComponentTestUtils.IN_MEMORY_LOG_HANDLER
                .getAllRecords(java.util.logging.Level.WARNING);
        assertTrue(warnRecords.size() >= 1);
        assertEquals(
                String.format(AbstractListener.TIMEOUT_WARN_LOG_MSG_PATTERN, DEFAULT_TIMEOUT_FOR_COMPONENT_SEND,
                        HELLO_INTERFACE.toString(), HELLO_SERVICE.toString(), EXTERNAL_HELLO_ENDPOINT,
                        AbstractListener.TIMEOUT_WARN_LOG_MSG_UNDEFINED_REF,
                        consumerDomainConsumerExtBeginFlowLogData.get(FlowLogData.FLOW_INSTANCE_ID_PROPERTY_NAME),
                        consumerDomainConsumerExtBeginFlowLogData.get(FlowLogData.FLOW_STEP_ID_PROPERTY_NAME)),
                warnRecords.get(1).getMessage());
    }
}
