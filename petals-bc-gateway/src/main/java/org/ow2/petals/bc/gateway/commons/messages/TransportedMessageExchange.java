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
 * along with this program/library; If not, see http://www.gnu.org/licenses/
 * for the GNU Lesser General Public License version 2.1.
 */
package org.ow2.petals.bc.gateway.commons.messages;

import java.io.Serializable;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.jbi.messaging.ExchangeStatus;
import javax.jbi.messaging.Fault;
import javax.jbi.messaging.MessageExchange;
import javax.jbi.messaging.MessagingException;
import javax.jbi.messaging.NormalizedMessage;
import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.component.framework.api.message.Exchange;

public class TransportedMessageExchange implements MessageExchange, Serializable {

    private static final long serialVersionUID = 2296933924867966793L;

    private enum Status {
        ACTIVE, DONE, ERROR
    }

    private final String exchangeId;

    private Status status;

    /**
     * TODO is that nullable?
     */
    private final QName operation;

    private final URI pattern;

    private final Map<String, Serializable> properties = new HashMap<>();

    @Nullable
    private Exception error;

    /*
     * Note: {@link Fault} is not serializable as an interface, but we know its implementation is in Petals.
     */
    @SuppressWarnings("squid:S1948")
    @Nullable
    private final Fault fault;

    /*
     * Note: {@link NormalizedMessage} is not serializable as an interface, but we know its implementation is in Petals.
     */
    @SuppressWarnings("squid:S1948")
    @Nullable
    private final NormalizedMessage in;

    /*
     * Note: {@link NormalizedMessage} is not serializable as an interface, but we know its implementation is in Petals.
     */
    @SuppressWarnings("squid:S1948")
    @Nullable
    private final NormalizedMessage out;

    @SuppressWarnings("null")
    public TransportedMessageExchange(final MessageExchange exchange) {
        this.exchangeId = exchange.getExchangeId();
        this.status = toStatus(exchange.getStatus());
        this.operation = exchange.getOperation();
        this.pattern = exchange.getPattern();
        this.error = exchange.getError();
        this.fault = exchange.getFault();
        this.in = exchange.getMessage(Exchange.IN_MESSAGE_NAME);
        this.out = exchange.getMessage(Exchange.OUT_MESSAGE_NAME);

        @SuppressWarnings({ "unchecked", "cast" })
        final Set<String> propertyNames = (Set<String>) exchange.getPropertyNames();
        for (String name : propertyNames) {
            final Object property = exchange.getProperty(name);
            if (property instanceof Serializable) {
                this.properties.put(name, (Serializable) property);
            } else {
                // TODO log?!?!
            }
        }
    }

    private static Status toStatus(final ExchangeStatus status) {
        if (ExchangeStatus.ACTIVE.equals(status)) {
            return Status.ACTIVE;
        } else if (ExchangeStatus.DONE.equals(status)) {
            return Status.DONE;
        } else if (ExchangeStatus.ERROR.equals(status)) {
            return Status.ERROR;
        } else {
            throw new IllegalArgumentException("unknown status");
        }
    }

    @SuppressWarnings("null")
    private static ExchangeStatus toExchangeStatus(final Status status) {
        switch (status) {
            case ACTIVE:
                return ExchangeStatus.ACTIVE;
            case DONE:
                return ExchangeStatus.DONE;
            case ERROR:
                return ExchangeStatus.ERROR;
            default:
                throw new IllegalArgumentException("unknown status");
        }
    }

    @Override
    @Nullable
    public Exception getError() {
        return this.error;
    }

    @Override
    public String getExchangeId() {
        return this.exchangeId;
    }

    @Override
    @Nullable
    public Fault getFault() {
        return this.fault;
    }

    @Override
    @Nullable
    public NormalizedMessage getMessage(@Nullable String name) {
        if (Exchange.FAULT_MESSAGE_NAME.equals(name)) {
            return this.fault;
        } else if (Exchange.IN_MESSAGE_NAME.equals(name)) {
            return this.in;
        } else if (Exchange.OUT_MESSAGE_NAME.equals(name)) {
            return this.out;
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public QName getOperation() {
        return this.operation;
    }

    @Override
    public URI getPattern() {
        return this.pattern;
    }

    @Override
    @Nullable
    public Object getProperty(@Nullable String name) {
        return this.properties.get(name);
    }

    @SuppressWarnings("null")
    @Override
    public Set<String> getPropertyNames() {
        return this.properties.keySet();
    }

    @Override
    public ExchangeStatus getStatus() {
        return toExchangeStatus(this.status);
    }

    @Override
    public void setError(@Nullable Exception error) {
        this.error = error;
        this.status = Status.ERROR;
    }

    @Override
    public Fault createFault() throws MessagingException {
        throw new UnsupportedOperationException();
    }

    @Override
    public NormalizedMessage createMessage() throws MessagingException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServiceEndpoint getEndpoint() {
        throw new UnsupportedOperationException();
    }

    @Override
    public QName getInterfaceName() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Role getRole() {
        throw new UnsupportedOperationException();
    }

    @Override
    public QName getService() {
        throw new UnsupportedOperationException();
    }


    @Override
    public boolean isTransacted() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEndpoint(@Nullable ServiceEndpoint endpoint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setFault(@Nullable Fault fault) throws MessagingException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setInterfaceName(@Nullable QName arg0) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setMessage(@Nullable NormalizedMessage msg, @Nullable String name) throws MessagingException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setOperation(@Nullable QName arg0) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setProperty(@Nullable String name, @Nullable Object obj) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setService(@Nullable QName arg0) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setStatus(@Nullable ExchangeStatus status) throws MessagingException {
        throw new UnsupportedOperationException();
    }

}
