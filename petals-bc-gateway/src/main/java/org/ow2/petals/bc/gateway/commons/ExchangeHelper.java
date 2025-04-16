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
package org.ow2.petals.bc.gateway.commons;

import java.util.HashSet;
import java.util.Set;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.messaging.MessagingException;
import javax.jbi.messaging.NormalizedMessage;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JBISender;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.bc.gateway.commons.messages.TransportedMessage;
import org.ow2.petals.component.framework.api.Message;
import org.ow2.petals.component.framework.api.message.Exchange;

public class ExchangeHelper {

    private ExchangeHelper() {
        // utility class
    }

    public static TransportedMessage updateTransportedExchange(final @Nullable TransportedMessage m,
            final ServiceKey service, final MessageExchange exchange) {

        final TransportedMessage msg;
        if (ExchangeStatus.ACTIVE.equals(exchange.getStatus())) {
            if (m == null) {
                msg = TransportedMessage.newMessage(service, exchange);
            } else {
                // we will be expecting an answer
                msg = TransportedMessage.middleMessage(m, exchange);
            }
        } else {
            assert m != null;
            msg = TransportedMessage.lastMessage(m, exchange);
        }

        return msg;
    }

    public static Exchange updateStoredExchange(final @Nullable Exchange exchange, final TransportedMessage m,
            final JBISender sender, final Boolean isFlowTracingActivationPropagated,
            final Boolean isFlowTracingActivated) throws MessagingException {
        final Exchange result;
        if (m.step == 1) {
            assert exchange == null;
            assert isFlowTracingActivationPropagated != null;
            assert isFlowTracingActivated != null;

            // this is a Consumes I propagated on the other side
            // TODO should I rely on information sent by the other side or should I keep a map somewhere for security
            // reasons?
            final ServiceKey service = m.service;

            result = sender.createExchange(service.interfaceName, service.service,
                    service.endpointName,
                    m.exchange.getPattern());

            setProperties(m.exchange, result.getMessageExchange());

            result.setOperation(m.exchange.getOperation());
            result.setInMessage(m.exchange.getMessage(Exchange.IN_MESSAGE_NAME));

        } else if (!m.last) {
            assert exchange != null;

            updateProperties(m.exchange, exchange.getMessageExchange());

            final NormalizedMessage out = m.exchange.getMessage(Exchange.OUT_MESSAGE_NAME);
            if (out != null && !exchange.isOutMessage()) {
                exchange.setOutMessage(out);
            } else if (m.exchange.getFault() != null && exchange.getFault() == null) {
                exchange.setFault(m.exchange.getFault());
            }

            result = exchange;
        } else {
            assert exchange != null;
            assert m.exchange.getStatus() != ExchangeStatus.ACTIVE;

            updateProperties(m.exchange, exchange.getMessageExchange());

            if (m.exchange.getStatus() == ExchangeStatus.ERROR) {
                // let's set it by hand too if the error is null
                exchange.setErrorStatus();
                exchange.setError(m.exchange.getError());
            } else if (m.exchange.getStatus() == ExchangeStatus.DONE) {
                exchange.setDoneStatus();
            }

            result = exchange;
        }

        if (isFlowTracingActivationPropagated.booleanValue()) {
            result.setProperty(Message.FLOW_TRACING_ACTIVATION_MSGEX_PROP, isFlowTracingActivated);
        } else {
            result.setProperty(Message.FLOW_TRACING_ACTIVATION_MSGEX_PROP, null);
        }
        return result;
    }

    private static void updateProperties(final @Nullable MessageExchange from, final @Nullable MessageExchange to) {
        assert from != null;
        assert to != null;

        // we remove all the properties of the exchange to update
        @SuppressWarnings("unchecked")
        final Set<String> oldProps = new HashSet<>(to.getPropertyNames());
        for (final String oldProp : oldProps) {
            assert oldProp != null;
            if (ignoreProperty(oldProp)) {
                // let's skip this one, we don't want to remove it!
                continue;
            }
            to.setProperty(oldProp, null);
        }

        // and put all of those from the source exchange
        setProperties(from, to);
    }

    private static void setProperties(final @Nullable MessageExchange from, final @Nullable MessageExchange to) {
        assert from != null;
        assert to != null;

        // we put all the properties from the source exchange
        @SuppressWarnings("unchecked")
        final Set<String> props = from.getPropertyNames();
        for (final String prop : props) {
            assert prop != null;
            if (ignoreProperty(prop)) {
                // let's skip this one, we don't want to propagate it!
                continue;
            }
            to.setProperty(prop, from.getProperty(prop));
        }
    }

    /**
     * This must be ignored because it is meant to be tied to a given send and the Gateway will create new exchanges.
     * 
     * See PETALSDISTRIB-282 for some details on this question.
     */
    private static boolean ignoreProperty(final String prop) {
        return prop.equals("javax.jbi.messaging.sendSync");
    }
}
