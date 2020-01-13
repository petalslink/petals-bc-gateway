/**
 * Copyright (c) 2016-2020 Linagora
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

import java.net.URI;

import javax.jbi.messaging.MessagingException;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.commons.DomainContext;
import org.ow2.petals.component.framework.api.message.Exchange;

/**
 * 
 * @author vnoel
 *
 */
public interface JBISender {

    void sendToNMR(DomainContext ctx, Exchange exchange) throws MessagingException;

    Exchange createExchange(@Nullable QName interfaceName, @Nullable QName serviceName, @Nullable String endpointName,
            URI mep) throws MessagingException;
}
