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
 * along with this program/library; If not, see <http://www.gnu.org/licenses/>
 * for the GNU Lesser General Public License version 2.1.
 */
package org.ow2.petals.bc.gateway;

import javax.xml.namespace.QName;

import org.ow2.petals.bc.gateway.utils.JbiGatewayConstants;
import org.ow2.petals.component.framework.junit.JbiConstants;

public interface JbiGatewayTestConstants extends JbiGatewayConstants, JbiConstants {

    public static final String ATTR_TRANSPORT_LISTENER_ID = "id";

    public static final QName EL_TRANSPORT_LISTENER_PORT = new QName(JG_NS_URI, "port");

    public static final String ATTR_SERVICES_PROVIDER_DOMAIN_ID = "id";

    public static final String ATTR_SERVICES_PROVIDER_DOMAIN_RETRY_MAX = "retry-max";

    public static final String ATTR_SERVICES_PROVIDER_DOMAIN_RETRY_DELAY = "retry-delay";

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_IP = new QName(JG_NS_URI, "remote-ip");

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_PORT = new QName(JG_NS_URI, "remote-port");

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_AUTH_NAME = new QName(JG_NS_URI, "remote-auth-name");

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_REMOTE_CRT = new QName(JG_NS_URI, "remote-certificate");

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_CRT = new QName(JG_NS_URI, "certificate");

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_KEY = new QName(JG_NS_URI, "key");

    public static final String ATTR_SERVICES_CONSUMER_DOMAIN_ID = "id";

    public static final String ATTR_SERVICES_CONSUMER_DOMAIN_TRANSPORT = "transport";

    public static final String ATTR_SERVICES_CONSUMER_DOMAIN_POLLING_DELAY = "propagation-polling-delay";

    public static final QName EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME = new QName(JG_NS_URI, "auth-name");

    public static final QName EL_SERVICES_CONSUMER_DOMAIN_REMOTE_CRT = new QName(JG_NS_URI, "remote-certificate");

    public static final QName EL_SERVICES_CONSUMER_DOMAIN_CRT = new QName(JG_NS_URI, "certificate");

    public static final QName EL_SERVICES_CONSUMER_DOMAIN_KEY = new QName(JG_NS_URI, "key");

    public static final String ATTR_PROVIDES_PROVIDER_DOMAIN = "domain";

    public static final QName EL_PROVIDES_INTERFACE_NAME = new QName(JG_NS_URI, "provider-interface-name");

    public static final QName EL_PROVIDES_SERVICE_NAME = new QName(JG_NS_URI, "provider-service-name");

    public static final QName EL_PROVIDES_ENDPOINT_NAME = new QName(JG_NS_URI, "provider-endpoint-name");

    public static final String ATTR_CONSUMES_CONSUMER_DOMAIN = "domain";

    public static final int DEFAULT_PORT = 7500;
}
