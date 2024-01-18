/**
 * Copyright (c) 2015-2024 Linagora
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
package org.ow2.petals.bc.gateway.commons.messages;

import javax.jbi.messaging.MessageExchange;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.component.framework.api.Message;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;

/**
 * {@link TransportedMessage}s contains:
 * 
 * The {@link ServiceKey} of the concerned {@link Consumes} from the provider partner (sent to a consumer partner in
 * {@link ConsumerDomain}).
 * 
 * The {@link MessageExchange} of the consumer partner that serves as a container for the exchanged changes. Once sent
 * it is not meant to be reused to be sent in the NMR because its class won't be in the correct classpath.
 * 
 * @author vnoel
 *
 */
public class TransportedMessage extends TransportedForExchange {

    private static final long serialVersionUID = 7102614527427146536L;

    /**
     * this identify the consumes that is targeted by the consumer partner
     */
    public final ServiceKey service;

    /**
     * the step of the exchange (starts with 1)
     */
    public final int step;

    /**
     * marker to denote that this message is the last one
     */
    public final boolean last;

    /**
     * This contains the exchange that one side received via the NMR and that the other side must use to fill its own
     * version of the exchange that he stored.
     */
    public final TransportedMessageExchange exchange;

    /**
     * Flow tracing activation state defined at JBI message level when invoking the service provider on consumer domain.
     */
    public final Boolean initialExternalFlowTracingActivation;

    public Boolean externalFlowTracingActivation = null;

    /**
     * The attributes of the flow step handled by the gateway as consumer partner and used as a correlated flow by the
     * provider partner.
     * 
     * Note: this can contain the same information as {@link TransportedForExchange#senderExtStep} but it is needed so
     * that the PD can always know the ProvideExtStep (because the PD stores the ProvideStep, while the CD stores the
     * ConsumeExtStep directly).
     * 
     * It is set the first time by the PD and then propagated to each message.
     */
    @Nullable
    public FlowAttributes provideExtStep;

    private TransportedMessage(final ServiceKey service, final String exchangeId, final MessageExchange exchange,
            final int step, final boolean last, final Boolean initialExternalFlowTracingActivation) {
        super(exchangeId);
        assert step > 0;
        this.service = service;
        this.step = step;
        this.last = last;
        this.exchange = new TransportedMessageExchange(exchange);
        this.initialExternalFlowTracingActivation = initialExternalFlowTracingActivation;
    }

    protected TransportedMessage(final ServiceKey service, final String exchangeId, final MessageExchange exchange,
            final int step, final boolean last) {
        this(service, exchangeId, exchange, step, last,
                (Boolean) exchange.getProperty(Message.FLOW_TRACING_ACTIVATION_MSGEX_PROP));
    }

    protected TransportedMessage(final TransportedMessage m, final boolean last, final MessageExchange exchange) {
        this(m.service, m.exchangeId, exchange, m.step + 1, last, m.initialExternalFlowTracingActivation);
        assert !m.last;
        assert m.step > 0;
        this.provideExtStep = m.provideExtStep;
    }

    public static TransportedMessage newMessage(final ServiceKey service, final MessageExchange exchange) {
        return new TransportedMessage(service, exchange.getExchangeId(), exchange, 1, false);
    }

    public static TransportedMessage middleMessage(final TransportedMessage m, final MessageExchange exchange) {
        final TransportedMessage tm = new TransportedMessage(m, false, exchange);
        tm.externalFlowTracingActivation = m.externalFlowTracingActivation;
        return tm;
    }

    public static TransportedMessage lastMessage(final TransportedMessage m, final MessageExchange exchange) {
        final TransportedMessage tm = new TransportedMessage(m, true, exchange);
        tm.externalFlowTracingActivation = m.externalFlowTracingActivation;
        return tm;
    }
}
