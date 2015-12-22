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

import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Component;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.jbidescriptor.generated.Services;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Helper class to manipulate the jbi.xml according to the schema in the resources directory.
 * 
 * TODO one day we should actually exploit in an automatised way this schema in the CDK directly.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayJBIHelper {

    public static final String JG_NS_URI = "http://petals.ow2.org/components/petals-bc-jbi-gateway/version-1.0";

    public static final QName EL_TRANSPORT_LISTENER = new QName(JG_NS_URI, "transport-listener");

    public static final String ATTR_TRANSPORT_LISTENER_ID = "id";

    public static final QName EL_TRANSPORT_LISTENER_PORT = new QName(JG_NS_URI, "port");

    public static final QName EL_SERVICES_PROVIDER_DOMAIN = new QName(JG_NS_URI, "provider-domain");

    public static final String ATTR_SERVICES_PROVIDER_DOMAIN_ID = "id";

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_IP = new QName(JG_NS_URI, "ip");

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_PORT = new QName(JG_NS_URI, "port");

    public static final QName EL_SERVICES_PROVIDER_DOMAIN_AUTH_NAME = new QName(JG_NS_URI, "auth-name");

    public static final QName EL_SERVICES_CONSUMER_DOMAIN = new QName(JG_NS_URI, "consumer-domain");

    public static final String ATTR_SERVICES_CONSUMER_DOMAIN_ID = "id";

    public static final String ATTR_SERVICES_CONSUMER_DOMAIN_TRANSPORT = "transport";

    public static final QName EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME = new QName(JG_NS_URI, "auth-name");

    public static final String EL_PROVIDES_PROVIDER_DOMAIN = "provider-domain";

    public static final QName EL_PROVIDES_INTERFACE_NAME = new QName(JG_NS_URI, "provider-interface-name");

    public static final QName EL_PROVIDES_SERVICE_NAME = new QName(JG_NS_URI, "provider-service-name");

    public static final QName EL_PROVIDES_ENDPOINT_NAME = new QName(JG_NS_URI, "provider-endpoint-name");

    public static final QName EL_CONSUMES_CONSUMER_DOMAIN = new QName(JG_NS_URI, "consumer-domain");

    public static final int DEFAULT_PORT = 7500;

    private JbiGatewayJBIHelper() {
    }

    public static List<JbiTransportListener> getListeners(final @Nullable Component component)
            throws PEtALSCDKException {
        assert component != null;
        final List<JbiTransportListener> res = new ArrayList<>();
        for (final Element e : component.getAny()) {
            assert e != null;
            if (hasQName(e, EL_TRANSPORT_LISTENER)) {
                assert e != null;
                final String container = EL_TRANSPORT_LISTENER.getLocalPart();
                assert container != null;
                final String id = getAttribute(e, ATTR_TRANSPORT_LISTENER_ID, container);
                final int port = getElementAsInt(e, EL_TRANSPORT_LISTENER_PORT, container, DEFAULT_PORT);
                res.add(new JbiTransportListener(id, port));
            }
        }
        return res;
    }

    public static List<JbiConsumerDomain> getConsumerDomains(final @Nullable Services services) throws PEtALSCDKException {
        assert services != null;
        final List<JbiConsumerDomain> res = new ArrayList<>();
        for (final Element e : services.getAnyOrAny()) {
            assert e != null;
            if (hasQName(e, EL_SERVICES_CONSUMER_DOMAIN)) {
                final String container = EL_SERVICES_CONSUMER_DOMAIN.getLocalPart();
                assert container != null;
                final String id = getAttribute(e, ATTR_SERVICES_CONSUMER_DOMAIN_ID, container);
                final String transport = getAttribute(e, ATTR_SERVICES_CONSUMER_DOMAIN_TRANSPORT, container);
                final String authName = getElement(e, EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME, container, null);

                res.add(new JbiConsumerDomain(id, transport, authName));
            }
        }
        return res;
    }

    public static List<JbiProviderDomain> getProviderDomains(final @Nullable Services services)
            throws PEtALSCDKException {
        assert services != null;
        final List<JbiProviderDomain> res = new ArrayList<>();
        for (final Element e : services.getAnyOrAny()) {
            assert e != null;
            if (hasQName(e, EL_SERVICES_PROVIDER_DOMAIN)) {
                final String container = EL_SERVICES_PROVIDER_DOMAIN.getLocalPart();
                assert container != null;
                final String id = getAttribute(e, ATTR_SERVICES_PROVIDER_DOMAIN_ID, container);
                final String ip = getElement(e, EL_SERVICES_PROVIDER_DOMAIN_IP, container, null);
                final int port = getElementAsInt(e, EL_SERVICES_PROVIDER_DOMAIN_PORT, container, DEFAULT_PORT);
                final String authName = getElement(e, EL_SERVICES_PROVIDER_DOMAIN_AUTH_NAME, container, null);

                res.add(new JbiProviderDomain(id, ip, port, authName));
            }
        }
        return res;
    }

    public static ServiceKey getDeclaredServiceKey(final Provides provides) {
        String endpointName = null;
        QName serviceName = null;
        QName interfaceName = null;
        for (final Element e : provides.getAny()) {
            assert e != null;
            if (hasQName(e, EL_PROVIDES_INTERFACE_NAME)) {
                if (interfaceName != null) {
                    // TODO error
                }
                // TODO
            } else if (hasQName(e, EL_PROVIDES_SERVICE_NAME)) {
                if (serviceName != null) {
                    // TODO error
                }
                // TODO
            } else if (hasQName(e, EL_PROVIDES_ENDPOINT_NAME)) {
                if (endpointName != null) {
                    // TODO error
                }
                // TODO
            }
        }
        return new ServiceKey(endpointName, serviceName, interfaceName);
    }

    private static String getAttribute(final Element e, final String name, final String container)
            throws PEtALSCDKException {
        final String res = e.getAttribute(name);
        assert res != null;
        if (StringUtils.isEmpty(res)) {
            throw new PEtALSCDKException(String.format("Attribute %s missing in element %s", name, container));
        }
        return res;
    }

    private static String getElement(final Element e, final QName name, final String container,
            final @Nullable String defaultValue) throws PEtALSCDKException {
        final NodeList es = e.getElementsByTagNameNS(name.getNamespaceURI(), name.getLocalPart());
        if (es.getLength() < 1) {
            if (defaultValue == null) {
                throw new PEtALSCDKException(String.format("Element %s missing in element %s", name, container));
            } else {
                return defaultValue;
            }
        } else if (es.getLength() > 1) {
            throw new PEtALSCDKException(
                    String.format("Only one element %s is allowed in element %s", name, container));
        }
        final String res = es.item(0).getTextContent();
        assert res != null;
        if (StringUtils.isEmpty(res)) {
            throw new PEtALSCDKException(
                    String.format("Content missing for element %s in element %s", name, container));
        }
        return res;
    }

    private static int getElementAsInt(final Element e, final QName name, final String container,
            final int defaultValue) throws PEtALSCDKException {
        final String string = getElement(e, name, container, "" + defaultValue);
        final int res;
        try {
            res = Integer.parseInt(string);
        } catch (final NumberFormatException e1) {
            throw new PEtALSCDKException(
                    String.format("Invalid value '%s' for element %s of element %s", string, name, container));
        }
        return res;
    }

    private static boolean hasQName(final Node e, final QName name) {
        return new QName(e.getNamespaceURI(), e.getLocalName()).equals(name);
    }

    /**
     * Defined in component jbi.xml of provider partners
     */
    public static class JbiTransportListener {

        public final String id;

        public final int port;

        public JbiTransportListener(final String id, final int port) {
            this.id = id;
            this.port = port;
        }
        
        @Override
        public String toString() {
            return id + "[" + port + "]";
        }
    }

    /**
     * Defined in SU jbi.xml of consumer partners
     */
    public static class JbiProviderDomain {

        public final String id;

        public final String ip;

        public final int port;

        public final String authName;

        public JbiProviderDomain(final String id, final String ip, final int port, final String authName) {
            this.id = id;
            this.ip = ip;
            this.port = port;
            this.authName = authName;
        }

    }

    /**
     * Defined in SU jbi.xml of provider partners
     */
    public static class JbiConsumerDomain {

        public final String id;

        // TODO why not have multiple possible transports???!
        public final String transport;

        public final String authName;

        public JbiConsumerDomain(String id, String transport, String authName) {
            this.id = id;
            this.transport = transport;
            this.authName = authName;
        }
    }
}
