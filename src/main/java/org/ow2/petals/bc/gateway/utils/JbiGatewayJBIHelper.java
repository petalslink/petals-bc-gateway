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
package org.ow2.petals.bc.gateway.utils;

import javax.xml.namespace.QName;

/**
 * Helper class to manipulate the jbi.xml according to the schema in the resources directory.
 * 
 * TODO one day we should actually exploit in an automatised way this schema in the CDK directly.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayJBIHelper {

    private static final String PETALS_JG_JBI_NS_URI = "http://petals.ow2.org/components/petals-bc-jbi-gateway/jbi/version-1.0";

    private static final QName PETALS_JG_JBI_TRANSPORT_LISTENER = new QName(PETALS_JG_JBI_NS_URI, "transport-listener");

    private static final QName PETALS_JG_JBI_SERVICES_PROVIDER_DOMAIN = new QName(PETALS_JG_JBI_NS_URI,
            "provider-domain");

    private static final QName PETALS_JG_JBI_SERVICES_CONSUMER_DOMAIN = new QName(PETALS_JG_JBI_NS_URI,
            "consumer-domain");

    private static final QName PETALS_JG_JBI_PROVIDES_PROVIDER_DOMAIN = new QName(PETALS_JG_JBI_NS_URI,
            "provider-domain");

    private static final QName PETALS_JG_JBI_PROVIDES_INTERFACE_NAME = new QName(PETALS_JG_JBI_NS_URI,
            "provider-interface-name");

    private static final QName PETALS_JG_JBI_PROVIDES_SERVICE_NAME = new QName(PETALS_JG_JBI_NS_URI,
            "provider-service-name");

    private static final QName PETALS_JG_JBI_PROVIDES_ENDPOINT = new QName(PETALS_JG_JBI_NS_URI, "provider-endpoint");

    private static final QName PETALS_JG_JBI_CONSUMES_CONSUMER_DOMAIN = new QName(PETALS_JG_JBI_NS_URI,
            "consumer-domain");

    private JbiGatewayJBIHelper() {
    }


}
