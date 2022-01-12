/**
 * Copyright (c) 2016-2022 Linagora
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
package org.ow2.petals.bc.gateway.utils;

import javax.xml.namespace.QName;

import org.ow2.petals.bc.gateway.jbidescriptor.generated.ObjectFactory;

public interface BcGatewayJbiConstants {

    public static final ObjectFactory FACTORY = new ObjectFactory();

    @SuppressWarnings("null")
    public static final QName EL_TRANSPORT_LISTENER = FACTORY.createTransportListener(null).getName();

    @SuppressWarnings("null")
    public static final String GATEWAY_NS_URI = EL_TRANSPORT_LISTENER.getNamespaceURI();

    @SuppressWarnings("null")
    public static final QName EL_CONSUMER_DOMAIN = FACTORY.createConsumerDomain(null).getName();

    @SuppressWarnings("null")
    public static final QName EL_PROVIDER_DOMAIN = FACTORY.createProviderDomain(null).getName();

    @SuppressWarnings("null")
    public static final QName EL_PROVIDER = FACTORY.createProvider(null).getName();

    @SuppressWarnings("null")
    public static final QName EL_CONSUMER = FACTORY.createConsumer(null).getName();

    public static final QName EL_CONSUMER_DOMAINS_MAX_POOL_SIZE = new QName(GATEWAY_NS_URI,
            "consumer-domains-max-pool-size");

    public static final int DEFAULT_CONSUMER_DOMAINS_MAX_POOL_SIZE = 6;

    public static final QName EL_PROVIDER_DOMAINS_MAX_POOL_SIZE = new QName(GATEWAY_NS_URI,
            "provider-domains-max-pool-size");

    public static final int DEFAULT_PROVIDER_DOMAINS_MAX_POOL_SIZE = 6;

}
