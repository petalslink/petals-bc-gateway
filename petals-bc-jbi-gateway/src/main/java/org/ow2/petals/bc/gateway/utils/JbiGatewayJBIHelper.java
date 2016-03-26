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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

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
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.component.framework.api.configuration.ConfigurationExtensions;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Component;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.jbidescriptor.generated.Services;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.ebmwebsourcing.easycommons.properties.PropertiesException;
import com.ebmwebsourcing.easycommons.properties.PropertiesHelper;
import com.ebmwebsourcing.easycommons.xml.DocumentBuilders;

/**
 * Helper class to manipulate the jbi.xml according to the schema in the resources directory.
 * 
 * TODO one day we should actually exploit in an automatised way this schema in the CDK directly.
 * 
 * TODO also we should support generic handling of placeholders directly in the jbi descriptor instead of the
 * {@link ConfigurationExtensions} if possible
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

            final SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            final Schema schemas = factory.newSchema(new StreamSource[] {
                    new StreamSource(JbiGatewayJBIHelper.class.getResourceAsStream("/CDKextensions.xsd")),
                    new StreamSource(JbiGatewayJBIHelper.class.getResourceAsStream("/CDKjbi.xsd")),
                    new StreamSource(JbiGatewayJBIHelper.class.getResourceAsStream("/JbiGatewayExtensions.xsd")) });

            final Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            unmarshaller.setSchema(schemas);

            JbiGatewayJBIHelper.unmarshaller = unmarshaller;
        } catch (final JAXBException | SAXException ex) {
            exception = new PEtALSCDKException(ex);
        }
    }

    private JbiGatewayJBIHelper() {
    }

    /**
     * An object will always be created (except if it's <code>null</code>): modifying it won't impact the jbi
     * descriptor!
     */
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

    private synchronized static @Nullable Object unmarshal(final Node e) throws PEtALSCDKException {
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
            final Class<T> clazz) throws PEtALSCDKException {
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

    public static JbiTransportListener addTransportListener(final String id, final int port,
            final @Nullable Component component) throws PEtALSCDKException {
        assert component != null;

        for (final JbiTransportListener jtl : getAll(component.getAny(), EL_TRANSPORT_LISTENER,
                JbiTransportListener.class)) {
            if (jtl.getId().equals(id)) {
                throw new PEtALSCDKException("A transport listener with id '" + id + "' already exists in the jbi.xml");
            }
        }

        final Document doc = DocumentBuilders.newDocument();

        final Element e = doc.createElementNS(EL_TRANSPORT_LISTENER.getNamespaceURI(),
                EL_TRANSPORT_LISTENER.getLocalPart());
        e.setAttribute("id", id);

        final Element eP = doc.createElementNS(EL_TRANSPORT_LISTENER.getNamespaceURI(), "port");
        eP.setTextContent("" + port);
        e.appendChild(eP);

        // let's ensure it is correct
        final JbiTransportListener jtl = asObject(e, EL_TRANSPORT_LISTENER, JbiTransportListener.class);
        assert jtl != null;

        component.getAny().add(e);

        return jtl;
    }

    public static @Nullable JbiTransportListener removeTransportListener(final String id,
            final @Nullable Component component) throws PEtALSCDKException {
        assert component != null;

        final Iterator<Element> iterator = component.getAny().iterator();
        while (iterator.hasNext()) {
            final Element e = iterator.next();
            assert e != null;
            final JbiTransportListener jtl = asObject(e, EL_TRANSPORT_LISTENER, JbiTransportListener.class);
            if (jtl != null && jtl.getId().equals(id)) {
                iterator.remove();
                return jtl;
            }
        }

        return null;
    }

    public static Collection<JbiTransportListener> getTransportListeners(final @Nullable Component component)
            throws PEtALSCDKException {
        assert component != null;
        return getAll(component.getAny(), EL_TRANSPORT_LISTENER, JbiTransportListener.class);
    }

    public static Collection<JbiConsumerDomain> getConsumerDomains(final @Nullable Services services,
            final Properties placeholders, final Logger logger) throws PEtALSCDKException {
        assert services != null;
        final Collection<JbiConsumerDomain> jcds = getAll(services.getAnyOrAny(), EL_CONSUMER_DOMAIN,
                JbiConsumerDomain.class);
        for (final JbiConsumerDomain jcd : jcds) {
            assert jcd != null;
            replace(jcd, placeholders, logger);
        }
        return jcds;
    }

    private static void replace(final JbiConsumerDomain jcd, final Properties placeholders, final Logger logger) {
        try {
            jcd.setAuthName(PropertiesHelper.resolveString(jcd.getAuthName(), placeholders));
        } catch (final PropertiesException e) {
            logger.log(Level.WARNING, "Error while resolving consumer domain ('" + jcd.getId() + "') auth name ('"
                    + jcd.getAuthName() + "') with placeholders", e);
        }
    }

    public static Collection<JbiProviderDomain> getProviderDomains(final @Nullable Services services,
            final Properties placeholders, final Logger logger) throws PEtALSCDKException {
        assert services != null;
        final Collection<JbiProviderDomain> jpds = getAll(services.getAnyOrAny(), EL_PROVIDER_DOMAIN,
                JbiProviderDomain.class);
        for (final JbiProviderDomain jpd : jpds) {
            assert jpd != null;
            replace(jpd, placeholders, logger);
            validate(jpd);
        }
        return jpds;
    }

    private static void validate(final JbiProviderDomain jpd) throws PEtALSCDKException {
        try {
            Integer.parseInt(jpd.getPort());
        } catch (final NumberFormatException e) {
            throw new PEtALSCDKException(
                    "Invalid port (" + jpd.getPort() + ") for provider domain (" + jpd.getId() + ")");
        }
    }

    private static void replace(final JbiProviderDomain jpd, final Properties placeholders, final Logger logger) {
        try {
            jpd.setAuthName(PropertiesHelper.resolveString(jpd.getAuthName(), placeholders));
        } catch (final PropertiesException e) {
            logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') auth name ('"
                    + jpd.getAuthName() + "') with placeholders", e);
        }
        try {
            jpd.setIp(PropertiesHelper.resolveString(jpd.getIp(), placeholders));
        } catch (final PropertiesException e) {
            logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') ip ('"
                    + jpd.getIp() + "') with placeholders", e);
        }

        try {
            jpd.setPort(PropertiesHelper.resolveString(jpd.getPort(), placeholders));
        } catch (final PropertiesException e) {
            logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') port ('"
                    + jpd.getPort() + "') with placeholders", e);
        }
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

    public static Map<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> getProvidesPerDomain(
            final @Nullable Services services, final Properties placeholders, final Logger logger)
            throws PEtALSCDKException {
        assert services != null;

        final Map<String, JbiProviderDomain> jpds = new HashMap<>();
        final Map<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> pd2provides = new HashMap<>();

        for (final JbiProviderDomain jpd : getProviderDomains(services, placeholders, logger)) {
            jpds.put(jpd.getId(), jpd);
            pd2provides.put(jpd, new ArrayList<Pair<Provides, JbiProvidesConfig>>());
        }

        for (final Provides provides : services.getProvides()) {
            assert provides != null;
            final JbiProvidesConfig config = JbiGatewayJBIHelper.getProviderConfig(provides);
            final JbiProviderDomain jpd = jpds.get(config.getDomain());
            if (jpd == null) {
                throw new PEtALSCDKException(
                        String.format("No provider domain was defined in the SU for '%s'", config.getDomain()));
            }
            pd2provides.get(jpd).add(new Pair<>(provides, config));
        }
        return pd2provides;
    }

    public static Map<JbiConsumerDomain, Collection<Consumes>> getConsumesPerDomain(final @Nullable Services services,
            final Properties placeholders, final Logger logger) throws PEtALSCDKException {
        assert services != null;

        final Map<String, JbiConsumerDomain> jcds = new HashMap<>();
        final Map<JbiConsumerDomain, Collection<Consumes>> cd2consumes = new HashMap<>();

        for (final JbiConsumerDomain jcd : JbiGatewayJBIHelper.getConsumerDomains(services, placeholders, logger)) {
            jcds.put(jcd.getId(), jcd);
            cd2consumes.put(jcd, new ArrayList<Consumes>());
        }

        for (final Consumes consumes : services.getConsumes()) {
            assert consumes != null;
            for (final String cd : JbiGatewayJBIHelper.getConsumerDomains(consumes)) {
                final JbiConsumerDomain jcd = jcds.get(cd);
                if (jcd == null) {
                    throw new PEtALSCDKException(
                            String.format("No consumer domain was defined in the SU for '%s'", cd));
                }
                // it is non-null
                cd2consumes.get(jcd).add(consumes);
            }
        }
        return cd2consumes;
    }

    public static Collection<String> getConsumerDomains(final @Nullable Consumes consumes) throws PEtALSCDKException {
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

    public static class Pair<A, B> {
        private final A a;

        private final B b;

        public Pair(final A a, final B b) {
            this.a = a;
            this.b = b;
        }

        public A getA() {
            return a;
        }

        public B getB() {
            return b;
        }
    }
}
