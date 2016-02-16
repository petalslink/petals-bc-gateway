/**
 * Copyright (c) 2015-2016 Linagora
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
import java.util.Collection;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumesConfig;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.ObjectFactory;
import org.ow2.petals.bc.gateway.messages.ServiceKey;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Component;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.jbidescriptor.generated.Services;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Helper class to manipulate the jbi.xml according to the schema in the resources directory.
 * 
 * TODO one day we should actually exploit in an automatised way this schema in the CDK directly.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayJBIHelper implements JbiGatewayConstants {

    private static @Nullable Unmarshaller unmarshaller;

    private static @Nullable PEtALSCDKException exception = null;

    static {
        try {
            final JAXBContext jaxbContext = JAXBContext.newInstance(ObjectFactory.class.getPackage().getName());
            final Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();

            final SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            final Schema schemas = factory.newSchema(new StreamSource[] {
                    new StreamSource(JbiGatewayJBIHelper.class.getResourceAsStream("/CDKextensions.xsd")),
                    new StreamSource(JbiGatewayJBIHelper.class.getResourceAsStream("/CDKjbi.xsd")),
                    new StreamSource(JbiGatewayJBIHelper.class.getResourceAsStream("/JbiGatewayExtensions.xsd")) });
            unmarshaller.setSchema(schemas);

            JbiGatewayJBIHelper.unmarshaller = unmarshaller;
        } catch (final JAXBException | SAXException ex) {
            exception = new PEtALSCDKException(ex);
        }
    }

    private JbiGatewayJBIHelper() {
    }

    private static @Nullable <T> T asObject(final Node e, final Class<T> clazz) throws PEtALSCDKException {
        final Object o = unmarshal(e);
        if (o instanceof JAXBElement<?> && clazz.isInstance(((JAXBElement<?>) o).getValue())) {
            @SuppressWarnings("unchecked")
            final T value = (T) ((JAXBElement<?>) o).getValue();
            return value;
        } else if (clazz.isInstance(o)) {
            @SuppressWarnings("unchecked")
            final T o2 = (T) o;
            return o2;
        } else {
            return null;
        }
    }

    private static @Nullable Object asObject(final Node e) throws PEtALSCDKException {
        return asObject(e, Object.class);
    }

    private static @Nullable JAXBElement<?> asJAXBElement(final Node e) throws PEtALSCDKException {
        final Object o = unmarshal(e);
        if (o instanceof JAXBElement<?>) {
            return (JAXBElement<?>) o;
        } else {
            return null;
        }
    }

    private static @Nullable Object unmarshal(final Node e) throws PEtALSCDKException {
        final Unmarshaller unmarshallerl = unmarshaller;
        if (unmarshallerl != null) {
            try {
                final Object o = unmarshallerl.unmarshal(e);
                assert o != null;
                return o;
            } catch (final JAXBException ex) {
                throw new PEtALSCDKException(ex);
            }
        } else {
            final PEtALSCDKException exceptionl = exception;
            if (exceptionl != null) {
                throw exceptionl;
            } else {
                throw new PEtALSCDKException("Impossible case");
            }
        }
    }

    public static boolean isRestrictedToComponentListeners(final @Nullable Component component) throws PEtALSCDKException {
        assert component != null;
        for (final Element e : component.getAny()) {
            assert e!= null;
            final JAXBElement<?> el = asJAXBElement(e);
            if (el != null && EL_RESTRICT_TO_COMPONENT_LISTENERS.equals(el.getName())
                    && el.getValue() instanceof Boolean) {
                return (Boolean) el.getValue();
            }
        }
        return false;
    }

    public static Collection<JbiTransportListener> getListeners(final @Nullable Component component)
            throws PEtALSCDKException {
        assert component != null;
        final List<JbiTransportListener> res = new ArrayList<>();
        for (final Element e : component.getAny()) {
            assert e != null;
            final Object o = asObject(e);
            // TODO test QName too?
            if (o instanceof JbiTransportListener) {
                res.add((JbiTransportListener) o);
            }
        }
        return res;
    }

    public static Collection<JbiConsumerDomain> getConsumerDomains(final @Nullable Services services)
            throws PEtALSCDKException {
        assert services != null;
        final List<JbiConsumerDomain> res = new ArrayList<>();
        for (final Element e : services.getAnyOrAny()) {
            assert e != null;
            final Object o = asObject(e);
            // TODO test QName too?
            if (o instanceof JbiConsumerDomain) {
                res.add((JbiConsumerDomain) o);
            }
        }
        return res;
    }

    public static Collection<JbiProviderDomain> getProviderDomains(final @Nullable Services services)
            throws PEtALSCDKException {
        assert services != null;
        final List<JbiProviderDomain> res = new ArrayList<>();
        for (final Element e : services.getAnyOrAny()) {
            assert e != null;
            final Object o = asObject(e);
            // TODO test QName too?
            if (o instanceof JbiProviderDomain) {
                res.add((JbiProviderDomain) o);
            }
        }
        return res;
    }

    public static String getProviderDomain(final @Nullable Provides provides) throws PEtALSCDKException {
        assert provides != null;
        String res = null;
        for (final Element e : provides.getAny()) {
            assert e != null;
            final Object o = asObject(e);
            // TODO test QName too?
            if (o instanceof JbiProvidesConfig) {
                if (res != null) {
                    throw new PEtALSCDKException(String.format("Duplicate provider mapping in Provides for '%s/%s/%s'",
                            provides.getInterfaceName(), provides.getServiceName(), provides.getEndpointName()));
                }
                res = ((JbiProvidesConfig) o).getDomain();
            }
        }
        if (res == null) {
            throw new PEtALSCDKException(String.format("Missing provider mapping in Provides for '%s/%s/%s'",
                    provides.getInterfaceName(), provides.getServiceName(), provides.getEndpointName()));
        }
        return res;
    }

    public static Collection<String> getConsumerDomain(final @Nullable Consumes consumes) throws PEtALSCDKException {
        assert consumes != null;
        final List<String> res = new ArrayList<>();
        for (final Element e : consumes.getAny()) {
            assert e != null;
            final Object o = asObject(e);
            // TODO test QName too?
            if (o instanceof JbiConsumesConfig) {
                res.add(((JbiConsumesConfig) o).getDomain());
            }
        }
        if (res.isEmpty()) {
            throw new PEtALSCDKException(String.format("Missing consumer mapping in Provides for '%s/%s/%s'",
                    consumes.getInterfaceName(), consumes.getServiceName(), consumes.getEndpointName()));
        }
        return res;
    }

    public static ServiceKey getDeclaredServiceKey(final Provides provides) throws PEtALSCDKException {
        ServiceKey res = null;
        for (final Element e : provides.getAny()) {
            assert e != null;
            final Object o = asObject(e);
            // TODO test QName too?
            if (o instanceof JbiProvidesConfig) {
                if (res != null) {
                    throw new PEtALSCDKException(String.format("Duplicate provider mapping in Provides for '%s/%s/%s'",
                            provides.getInterfaceName(), provides.getServiceName(), provides.getEndpointName()));
                }
                final JbiProvidesConfig jpm = ((JbiProvidesConfig) o);
                res = new ServiceKey(jpm.getProviderEndpointName(), jpm.getProviderServiceName(),
                        jpm.getProviderInterfaceName());
            }
        }
        if (res == null) {
            throw new PEtALSCDKException(String.format("Missing provider mapping in Provides for '%s/%s/%s'",
                    provides.getInterfaceName(), provides.getServiceName(), provides.getEndpointName()));
        }
        return res;
    }
}
