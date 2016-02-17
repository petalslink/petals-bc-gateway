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
import javax.xml.namespace.QName;
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
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Component;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.jbidescriptor.generated.Services;
import org.ow2.petals.component.framework.jbidescriptor.generated.Settableboolean;
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

    private static @Nullable <T> T asObject(final Node e, final @Nullable QName name, final Class<T> clazz)
            throws PEtALSCDKException {
        final Object o = unmarshal(e);
        if (o instanceof JAXBElement<?> && clazz.isInstance(((JAXBElement<?>) o).getValue())
                && (name == null || name.equals(((JAXBElement<?>) o).getName()))) {
            @SuppressWarnings("unchecked")
            final T value = (T) ((JAXBElement<?>) o).getValue();
            return value;
        } else if (name == null && clazz.isInstance(o)) {
            @SuppressWarnings("unchecked")
            final T o2 = (T) o;
            return o2;
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

    private static <T> Collection<T> getAll(final @Nullable List<Element> elements, final QName name,
            final Class<T> clazz)
            throws PEtALSCDKException {
        assert elements != null;
        final List<T> res = new ArrayList<>();
        for (final Element e : elements) {
            assert e != null;
            final T o = asObject(e, name, clazz);
            if (o != null) {
                res.add(o);
            }
        }
        return res;
    }

    public static boolean isRestrictedToComponentListeners(final @Nullable Component component)
            throws PEtALSCDKException {
        assert component != null;
        final Collection<Settableboolean> elements = getAll(component.getAny(), EL_RESTRICT_TO_COMPONENT_LISTENERS,
                Settableboolean.class);
        if (elements.isEmpty()) {
            throw new PEtALSCDKException(
                    "Missing " + EL_RESTRICT_TO_COMPONENT_LISTENERS.getLocalPart() + " in Component");
        }
        if (elements.size() > 1) {
            throw new PEtALSCDKException(
                    "Duplicate " + EL_RESTRICT_TO_COMPONENT_LISTENERS.getLocalPart() + " in Component");
        }
        final Settableboolean element = elements.iterator().next();
        return element.isValue();
    }

    public static Collection<JbiTransportListener> getListeners(final @Nullable Component component)
            throws PEtALSCDKException {
        assert component != null;
        return getAll(component.getAny(), EL_TRANSPORT_LISTENER, JbiTransportListener.class);
    }

    public static Collection<JbiConsumerDomain> getConsumerDomains(final @Nullable Services services)
            throws PEtALSCDKException {
        assert services != null;
        return getAll(services.getAnyOrAny(), EL_CONSUMER_DOMAIN, JbiConsumerDomain.class);
    }

    public static Collection<JbiProviderDomain> getProviderDomains(final @Nullable Services services)
            throws PEtALSCDKException {
        assert services != null;
        return getAll(services.getAnyOrAny(), EL_PROVIDER_DOMAIN, JbiProviderDomain.class);
    }

    public static Collection<JbiTransportListener> getTransportListeners(final @Nullable Services services)
            throws PEtALSCDKException {
        assert services != null;
        return getAll(services.getAnyOrAny(), EL_TRANSPORT_LISTENER, JbiTransportListener.class);
    }

    public static JbiProvidesConfig getProviderConfig(final @Nullable Provides provides) throws PEtALSCDKException {
        assert provides != null;
        final Collection<JbiProvidesConfig> configs = getAll(provides.getAny(), EL_PROVIDER, JbiProvidesConfig.class);
        if (configs.isEmpty()) {
            throw new PEtALSCDKException(String.format("Missing provider in Provides for '%s/%s/%s'",
                    provides.getInterfaceName(), provides.getServiceName(), provides.getEndpointName()));
        }
        if (configs.size() > 1) {
            throw new PEtALSCDKException(String.format("Duplicate provider in Provides for '%s/%s/%s'",
                    provides.getInterfaceName(), provides.getServiceName(), provides.getEndpointName()));
        }
        final JbiProvidesConfig config = configs.iterator().next();
        assert config != null;
        return config;
    }

    public static String getProviderDomain(final @Nullable Provides provides) throws PEtALSCDKException {
        final String domain = getProviderConfig(provides).getDomain();
        assert domain != null;
        return domain;
    }

    public static Collection<String> getConsumerDomain(final @Nullable Consumes consumes) throws PEtALSCDKException {
        assert consumes != null;
        final Collection<JbiConsumesConfig> confs = getAll(consumes.getAny(), EL_CONSUMER, JbiConsumesConfig.class);
        if (confs.isEmpty()) {
            throw new PEtALSCDKException(String.format("Missing consumer in Provides for '%s/%s/%s'",
                    consumes.getInterfaceName(), consumes.getServiceName(), consumes.getEndpointName()));
        }
        final List<String> res = new ArrayList<>();
        for (final JbiConsumesConfig conf : confs) {
            final String domain = conf.getDomain();
            assert domain != null;
            res.add(domain);
        }
        return res;
    }
}
