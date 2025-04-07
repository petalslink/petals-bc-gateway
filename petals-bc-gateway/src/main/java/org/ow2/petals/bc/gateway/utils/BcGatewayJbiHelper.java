/**
 * Copyright (c) 2015-2025 Linagora
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

import javax.xml.XMLConstants;
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
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.component.framework.api.configuration.ConfigurationExtensions;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.api.util.Placeholders;
import org.ow2.petals.component.framework.jbidescriptor.generated.Component;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.jbidescriptor.generated.Services;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
import org.ow2.petals.component.framework.util.ServiceUnitUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.ebmwebsourcing.easycommons.lang.StringHelper;
import com.ebmwebsourcing.easycommons.properties.PropertiesException;
import com.ebmwebsourcing.easycommons.properties.PropertiesHelper;
import com.ebmwebsourcing.easycommons.xml.DocumentBuilders;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

/**
 * Helper class to manipulate the jbi.xml according to the schema in the resources directory.
 * 
 * All the collections returned are immutables!
 * 
 * TODO one day we should actually exploit in an automated way this schema in the CDK directly.
 * 
 * TODO also we should support generic handling of placeholders directly in the jbi descriptor instead of the
 * {@link ConfigurationExtensions} if possible
 * 
 * @author vnoel
 *
 */
public class BcGatewayJbiHelper implements BcGatewayJbiConstants {

    private static @Nullable Unmarshaller unmarshaller;

    private static @Nullable PEtALSCDKException exception = null;

    static {
        try {
            final JAXBContext jaxbContext = JAXBContext.newInstance(
                    org.ow2.petals.bc.gateway.jbidescriptor.generated.ObjectFactory.class,
                    org.ow2.petals.jbi.descriptor.extension.generated.ObjectFactory.class);

            final SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            final Schema schemas = factory.newSchema(new StreamSource[] {
                    new StreamSource(BcGatewayJbiHelper.class.getResourceAsStream("/CDKextensions.xsd")),
                    new StreamSource(BcGatewayJbiHelper.class.getResourceAsStream("/CDKjbi.xsd")),
                    new StreamSource(BcGatewayJbiHelper.class.getResourceAsStream("/GatewayExtensions.xsd")),
                    new StreamSource(BcGatewayJbiHelper.class.getResourceAsStream("/petals-jbi.xsd")) });

            final Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            unmarshaller.setSchema(schemas);

            BcGatewayJbiHelper.unmarshaller = unmarshaller;
        } catch (final JAXBException | SAXException ex) {
            exception = new PEtALSCDKException(ex);
        }
    }

    private BcGatewayJbiHelper() {
        // Utility class --> No constructor
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

        final Element e = doc.createElementNS(GATEWAY_NS_URI, EL_TRANSPORT_LISTENER.getLocalPart());
        e.setAttribute("id", id);

        final Element eP = doc.createElementNS(GATEWAY_NS_URI, "port");
        eP.setTextContent(Integer.toString(port));
        e.appendChild(eP);

        // let's ensure it is correct
        final JbiTransportListener jtl = asObject(e, EL_TRANSPORT_LISTENER, JbiTransportListener.class);
        assert jtl != null;

        component.getAny().add(e);

        return jtl;
    }

    public static JbiTransportListener setTransportListenerPort(final String id, final int port,
            final @Nullable Component component) throws PEtALSCDKException {
        assert component != null;

        final Iterator<Element> iterator = component.getAny().iterator();
        while (iterator.hasNext()) {
            final Element e = iterator.next();
            assert e != null;
            final JbiTransportListener jtl = asObject(e, EL_TRANSPORT_LISTENER, JbiTransportListener.class);
            if (jtl != null && jtl.getId().equals(id)) {
                
                // let's update the underlying element
                e.getElementsByTagNameNS(GATEWAY_NS_URI, "port").item(0).setTextContent(Integer.toString(port));
                
                // and get the new object
                final JbiTransportListener newJtl = asObject(e, EL_TRANSPORT_LISTENER, JbiTransportListener.class);
                if (newJtl == null) {
                    throw new PEtALSCDKException("Can't set port (" + port + ") for transport listener '" + id + "'");
                }

                return newJtl;
            }
        }

        throw new PEtALSCDKException("No transport listener with id '" + id + "' exists in the jbi.xml");
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
        final Collection<JbiTransportListener> tls = getAll(component.getAny(), EL_TRANSPORT_LISTENER,
                JbiTransportListener.class);
        final Collection<JbiTransportListener> res = Collections.unmodifiableCollection(tls);
        assert res != null;
        return res;
    }

    private static Collection<JbiConsumerDomain> getConsumerDomains(final ServiceUnitDataHandler handler,
            final @Nullable Services services, final Placeholders placeholders, final Logger logger)
            throws PEtALSCDKException {
        assert services != null;
        final Collection<JbiConsumerDomain> jcds = getAll(services.getAnyOrAny(), EL_CONSUMER_DOMAIN,
                JbiConsumerDomain.class);
        for (final JbiConsumerDomain jcd : jcds) {
            assert jcd != null;
            replace(jcd, placeholders.toProperties(), logger);
            validate(handler, jcd);
        }
        return jcds;
    }

    private static void validate(final ServiceUnitDataHandler handler, final JbiConsumerDomain jcd)
            throws PEtALSCDKException {

        final String certificate = jcd.getCertificate();
        final String key = jcd.getKey();

        if (certificate != null ^ key != null) {
            throw new PEtALSCDKException(
                    "Either both or neither of certificate and key must be present for consumer domain (" + jcd.getId()
                            + ")");
        }

        final String remoteCertificate = jcd.getRemoteCertificate();

        if ((certificate == null && key == null) && remoteCertificate != null) {
            throw new PEtALSCDKException(
                    "Certificate and key must be present for remote certificate to be used for consumer domain ("
                            + jcd.getId() + ")");
        }

        if (certificate != null) {
            if (!ServiceUnitUtil.getFile(handler.getInstallRoot(), certificate).exists()) {
                throw new PEtALSCDKException(
                        "Missing certificate (" + certificate + ") for consumer domain (" + jcd.getId() + ")");
            }
        }

        if (key != null) {
            if (!ServiceUnitUtil.getFile(handler.getInstallRoot(), key).exists()) {
                throw new PEtALSCDKException("Missing key (" + key + ") for consumer domain (" + jcd.getId() + ")");
            }
        }

        if (remoteCertificate != null) {
            if (!ServiceUnitUtil.getFile(handler.getInstallRoot(), remoteCertificate).exists()) {
                throw new PEtALSCDKException("Missing remote certificate (" + remoteCertificate
                        + ") for consumer domain (" + jcd.getId() + ")");
            }
        }
    }

    private static void replace(final JbiConsumerDomain jcd, final Properties placeholders, final Logger logger) {
        final String authName = jcd.getAuthName();
        try {
            jcd.setAuthName(PropertiesHelper.resolveString(authName, placeholders));
        } catch (final PropertiesException e) {
            logger.log(Level.WARNING, "Error while resolving consumer domain ('" + jcd.getId() + "') auth name ('"
                    + authName + "') with placeholders", e);
        }

        final String remoteCertificate = jcd.getRemoteCertificate();
        if (!StringHelper.isNullOrEmpty(remoteCertificate)) {
            try {
                final String remoteCertificateResolvedValue = PropertiesHelper.resolveString(remoteCertificate,
                        placeholders);
                if (StringHelper.isNullOrEmpty(remoteCertificateResolvedValue)) {
                    jcd.setRemoteCertificate(null);
                } else {
                    jcd.setRemoteCertificate(remoteCertificateResolvedValue);
                }
            } catch (final PropertiesException e) {
                logger.log(Level.WARNING, "Error while resolving consumer domain ('" + jcd.getId()
                        + "') remote certificate ('" + remoteCertificate + "') with placeholders", e);
            }
        } else {
            jcd.setRemoteCertificate(null);
        }

        final String certificate = jcd.getCertificate();
        if (!StringHelper.isNullOrEmpty(certificate)) {
            try {
                final String certificateResolvedValue = PropertiesHelper.resolveString(certificate, placeholders);
                if (StringHelper.isNullOrEmpty(certificateResolvedValue)) {
                    jcd.setCertificate(null);
                } else {
                    jcd.setCertificate(certificateResolvedValue);
                }
            } catch (final PropertiesException e) {
                logger.log(Level.WARNING, "Error while resolving consumer domain ('" + jcd.getId() + "') certificate ('"
                        + certificate + "') with placeholders", e);
            }
        } else {
            jcd.setCertificate(null);
        }

        final String key = jcd.getKey();
        if (!StringHelper.isNullOrEmpty(key)) {
            try {
                final String keyResolvedValue = PropertiesHelper.resolveString(key, placeholders);
                if (StringHelper.isNullOrEmpty(keyResolvedValue)) {
                    jcd.setKey(null);
                } else {
                    jcd.setKey(keyResolvedValue);
                }
            } catch (final PropertiesException e) {
                logger.log(Level.WARNING, "Error while resolving consumer domain ('" + jcd.getId() + "') key ('" + key
                        + "') with placeholders", e);
            }
        } else {
            jcd.setKey(null);
        }

        final String passphrase = jcd.getPassphrase();
        if (!StringHelper.isNullOrEmpty(passphrase)) {
            try {
                jcd.setPassphrase(PropertiesHelper.resolveString(passphrase, placeholders));
            } catch (final PropertiesException e) {
                logger.log(Level.WARNING, "Error while resolving consumer domain ('" + jcd.getId() + "') passphrase ('"
                        + passphrase + "') with placeholders", e);
            }
        } else {
            jcd.setPassphrase(null);
        }
    }

    private static Collection<JbiProviderDomain> getProviderDomains(final ServiceUnitDataHandler handler,
            final @Nullable Services services, final Placeholders placeholders, final Logger logger)
            throws PEtALSCDKException {
        assert services != null;
        final Collection<JbiProviderDomain> jpds = getAll(services.getAnyOrAny(), EL_PROVIDER_DOMAIN,
                JbiProviderDomain.class);
        for (final JbiProviderDomain jpd : jpds) {
            assert jpd != null;
            replace(jpd, placeholders.toProperties(), logger);
            validate(handler, jpd);
        }
        return jpds;
    }

    private static void validate(final ServiceUnitDataHandler handler, final JbiProviderDomain jpd)
            throws PEtALSCDKException {
        try {
            Integer.parseInt(jpd.getRemotePort());
        } catch (final NumberFormatException e) {
            throw new PEtALSCDKException(
                    "Invalid port (" + jpd.getRemotePort() + ") for provider domain (" + jpd.getId() + ")");
        }

        final String certificate = jpd.getCertificate();
        final String key = jpd.getKey();

        if (certificate != null ^ key != null) {
            throw new PEtALSCDKException(
                    "Either both or neither of certificate and key must be present for provider domain (" + jpd.getId()
                            + ")");
        }

        final String remoteCertificate = jpd.getRemoteCertificate();

        if ((certificate != null && key != null) && remoteCertificate == null) {
            throw new PEtALSCDKException(
                    "Remote certificate must be present for certificate and key to be used for provider domain ("
                            + jpd.getId() + ")");
        }

        if (certificate != null) {
            if (!ServiceUnitUtil.getFile(handler.getInstallRoot(), certificate).exists()) {
                throw new PEtALSCDKException(
                        "Missing certificate (" + certificate + ") for provider domain (" + jpd.getId() + ")");
            }
        }

        if (key != null) {
            if (!ServiceUnitUtil.getFile(handler.getInstallRoot(), key).exists()) {
                throw new PEtALSCDKException("Missing key (" + key + ") for provider domain (" + jpd.getId() + ")");
            }
        }

        if (remoteCertificate != null) {
            if (!ServiceUnitUtil.getFile(handler.getInstallRoot(), remoteCertificate).exists()) {
                throw new PEtALSCDKException("Missing remote certificate (" + remoteCertificate
                        + ") for provider domain (" + jpd.getId() + ")");
            }
        }

        final int retryMax = jpd.getRetryMax();
        if (retryMax != 0) {
            final long retryDelay = jpd.getRetryDelay();
            if (retryDelay < 0) {
                throw new PEtALSCDKException("retry delay (" + retryDelay + ") can't be negative if retry max ("
                        + retryMax + ") is set to a non-zero value");
            }
        }
    }

    private static void replace(final JbiProviderDomain jpd, final Properties placeholders, final Logger logger) {
        final String remoteAuthName = jpd.getRemoteAuthName();
        try {
            jpd.setRemoteAuthName(PropertiesHelper.resolveString(remoteAuthName, placeholders));
        } catch (final PropertiesException e) {
            logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') auth name ('"
                    + remoteAuthName + "') with placeholders", e);
        }

        final String remoteIp = jpd.getRemoteIp();
        try {
            jpd.setRemoteIp(PropertiesHelper.resolveString(remoteIp, placeholders));
        } catch (final PropertiesException e) {
            logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') ip ('" + remoteIp
                    + "') with placeholders", e);
        }

        final String remotePort = jpd.getRemotePort();
        try {
            jpd.setRemotePort(PropertiesHelper.resolveString(remotePort, placeholders));
        } catch (final PropertiesException e) {
            logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') port ('"
                    + remotePort + "') with placeholders", e);
        }

        final String remoteCertificate = jpd.getRemoteCertificate();
        if (!StringHelper.isNullOrEmpty(remoteCertificate)) {
            try {
                final String remoteCertificateResolvedValue = PropertiesHelper.resolveString(remoteCertificate,
                        placeholders);
                if (StringHelper.isNullOrEmpty(remoteCertificateResolvedValue)) {
                    jpd.setRemoteCertificate(null);
                } else {
                    jpd.setRemoteCertificate(remoteCertificateResolvedValue);
                }
            } catch (final PropertiesException e) {
                logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId()
                        + "') remote certificate ('" + remoteCertificate + "') with placeholders", e);
            }
        } else {
            jpd.setRemoteCertificate(null);
        }

        final String certificate = jpd.getCertificate();
        if (!StringHelper.isNullOrEmpty(certificate)) {
            try {
                final String certificateResolvedValue = PropertiesHelper.resolveString(certificate, placeholders);
                if (StringHelper.isNullOrEmpty(certificateResolvedValue)) {
                    jpd.setCertificate(null);
                } else {
                    jpd.setCertificate(certificateResolvedValue);
                }
            } catch (final PropertiesException e) {
                logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') certificate ('"
                        + certificate + "') with placeholders", e);
            }
        } else {
            jpd.setCertificate(null);
        }

        final String key = jpd.getKey();
        if (!StringHelper.isNullOrEmpty(key)) {
            try {
                final String keyResolvedValue = PropertiesHelper.resolveString(key, placeholders);
                if (StringHelper.isNullOrEmpty(keyResolvedValue)) {
                    jpd.setKey(null);
                } else {
                    jpd.setKey(keyResolvedValue);
                }
            } catch (final PropertiesException e) {
                logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') key ('" + key
                        + "') with placeholders", e);
            }
        } else {
            jpd.setKey(null);
        }

        final String passphrase = jpd.getPassphrase();
        if (!StringHelper.isNullOrEmpty(passphrase)) {
            try {
                jpd.setPassphrase(PropertiesHelper.resolveString(passphrase, placeholders));
            } catch (final PropertiesException e) {
                logger.log(Level.WARNING, "Error while resolving provider domain ('" + jpd.getId() + "') passphrase ('"
                        + passphrase + "') with placeholders", e);
            }
        } else {
            jpd.setPassphrase(null);
        }
    }

    private static JbiProvidesConfig getProviderConfig(final @Nullable Provides provides) throws PEtALSCDKException {
        assert provides != null;

        final Collection<JbiProvidesConfig> configs = getAll(provides.getAny(), EL_PROVIDER, JbiProvidesConfig.class);
        if (configs.isEmpty()) {
            throw new PEtALSCDKException("Missing provider in Provides " + toString(provides));
        }
        if (configs.size() > 1) {
            throw new PEtALSCDKException("Duplicate provider in Provides " + toString(provides));
        }
        final JbiProvidesConfig config = configs.iterator().next();
        assert config != null;
        return config;
    }

    public static Map<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> getProvidesPerDomain(
            final ServiceUnitDataHandler handler, final Placeholders placeholders,
            final Logger logger) throws PEtALSCDKException {
        final Services services = handler.getDescriptor().getServices();
        assert services != null;

        final Map<String, JbiProviderDomain> jpds = new HashMap<>();
        final Map<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> pd2provides = new HashMap<>();

        for (final JbiProviderDomain jpd : getProviderDomains(handler, services, placeholders, logger)) {
            jpds.put(jpd.getId(), jpd);
            pd2provides.put(jpd, new ArrayList<Pair<Provides, JbiProvidesConfig>>());
        }

        for (final Provides provides : services.getProvides()) {
            assert provides != null;
            final JbiProvidesConfig config = BcGatewayJbiHelper.getProviderConfig(provides);
            final JbiProviderDomain jpd = jpds.get(config.getDomain());
            if (jpd == null) {
                throw new PEtALSCDKException(
                        String.format("No provider domain was defined in the SU for '%s'", config.getDomain()));
            }
            pd2provides.get(jpd).add(new Pair<>(provides, config));
        }

        for (final Entry<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> entry : pd2provides
                .entrySet()) {
            validate(entry.getValue(), entry.getKey());
        }

        // let's make that unmodifiable!
        return unmodifiable(pd2provides);
    }

    /**
     * This only validates that the configurations are not duplicated or subsuming each others
     * 
     * TODO check that CDK already checks that provides are valid (i.e. there is no duplicates)
     */
    private static void validate(final Collection<Pair<Provides, JbiProvidesConfig>> provides,
            final JbiProviderDomain jpd) throws PEtALSCDKException {

        for (final Pair<Provides, JbiProvidesConfig> sp : provides) {
            final JbiProvidesConfig sc = sp.getB();
            for (final Pair<Provides, JbiProvidesConfig> gp : provides) {
                final JbiProvidesConfig gc = gp.getB();
                // sc is the specific config
                // gc is the generic config
                if (sc != gc) {
                    // careful, they can null, hence the use of Objects
                    if (Objects.equals(sc.getProviderEndpointName(), gc.getProviderEndpointName())) {
                        throw new PEtALSCDKException("Provides configuration ambiguity for provider domain "
                                + jpd.getId() + ": " + toString(gp.getA()) + " and " + toString(sp.getA())
                                + " are identical");
                    }

                    if (gc.getProviderEndpointName() == null && sc.getProviderEndpointName() != null) {
                        throw new PEtALSCDKException("Provides configuration ambiguity for provider domain "
                                + jpd.getId() + ": " + toString(gp.getA()) + " already covers the more specific "
                                + toString(sp.getA()));
                    }
                }
            }
        }
    }

    private static <A, B> Map<A, Collection<B>> unmodifiable(final Map<A, Collection<B>> map) {
        for (final Entry<A, Collection<B>> entry : map.entrySet()) {
            entry.setValue(Collections.unmodifiableCollection(entry.getValue()));
        }

        final Map<A, Collection<B>> res = Collections.unmodifiableMap(map);
        assert res != null;
        return res;
    }

    public static Map<JbiConsumerDomain, Collection<Consumes>> getConsumesPerDomain(
            final ServiceUnitDataHandler handler, final Placeholders placeholders,
            final Logger logger) throws PEtALSCDKException {
        final Services services = handler.getDescriptor().getServices();
        assert services != null;

        final Map<String, JbiConsumerDomain> jcds = new HashMap<>();
        final Map<JbiConsumerDomain, Collection<Consumes>> cd2consumes = new HashMap<>();

        for (final JbiConsumerDomain jcd : BcGatewayJbiHelper.getConsumerDomains(handler, services, placeholders,
                logger)) {
            jcds.put(jcd.getId(), jcd);
            cd2consumes.put(jcd, new ArrayList<Consumes>());
        }

        for (final Consumes consumes : services.getConsumes()) {
            assert consumes != null;
            for (final String cd : BcGatewayJbiHelper.getConsumerDomains(consumes)) {
                final JbiConsumerDomain jcd = jcds.get(cd);
                if (jcd == null) {
                    throw new PEtALSCDKException(
                            String.format("No consumer domain was defined in the SU for '%s'", cd));
                }
                // it is non-null
                cd2consumes.get(jcd).add(consumes);
            }
        }

        for (final Entry<JbiConsumerDomain, Collection<Consumes>> entry : cd2consumes.entrySet()) {
            // we validate PER consumer domain!
            validate(entry.getValue(), entry.getKey());
        }

        // let's make that unmodifiable!
        return unmodifiable(cd2consumes);
    }

    /**
     * This validates that consumes are not identical or subsuming each others
     * 
     * TODO or we could simply ignore (and logs then) the most generic ones?! or the most specific ones?!
     *
     * TODO check that CDK already checks that consumes are valid (i.e. if there is an endpointname, then need a service
     * name)
     */
    private static void validate(final Collection<Consumes> consumes, final JbiConsumerDomain jcd)
            throws PEtALSCDKException {
        for (final Consumes sc : consumes) {
            assert sc != null;
            for (final Consumes gc : consumes) {
                assert gc != null;
                // sc is the specific consumes
                // gc is the generic consumes
                if (sc != gc) {
                    // careful service name and endpoint name can be null, hence Objects.equals
                    if (Objects.equals(sc.getInterfaceName(), gc.getInterfaceName())
                            && Objects.equals(sc.getServiceName(), gc.getServiceName())
                            && Objects.equals(sc.getEndpointName(), gc.getEndpointName())) {
                        throw new PEtALSCDKException("Consumes ambiguity for consumer domain " + jcd.getId() + ": "
                                + toString(gc) + " and " + toString(sc) + " are identical");
                    }
                    if (gc.getEndpointName() == null && sc.getInterfaceName().equals(gc.getInterfaceName())) {
                        // specific w.r.t. endpoint name
                        if (sc.getEndpointName() != null
                                && (gc.getServiceName() == null || gc.getServiceName().equals(sc.getServiceName()))) {
                            throw new PEtALSCDKException("Consumes ambiguity for consumer domain " + jcd.getId() + ": "
                                    + toString(gc) + " already covers the more specific " + toString(sc));
                        }

                        // specific w.r.t. service name
                        if (sc.getEndpointName() == null && sc.getServiceName() != null
                                && gc.getServiceName() == null) {
                            throw new PEtALSCDKException("Consumes ambiguity for consumer domain " + jcd.getId() + ": "
                                    + toString(gc) + " already covers the more specific " + toString(sc));
                        }
                    }
                }
            }
        }
    }

    public static String toString(final Consumes c) {
        final QName i = c.getInterfaceName();
        final QName s = c.getServiceName();
        final String e = c.getEndpointName();
        return i.toString() + (s != null ? "/" + s.toString() + (e != null ? "/" + e : "") : "");
    }

    public static String toString(final Provides p) {
        final QName i = p.getInterfaceName();
        final QName s = p.getServiceName();
        final String e = p.getEndpointName();
        return i + "/" + s + "/" + e;
    }

    private static Collection<String> getConsumerDomains(final @Nullable Consumes consumes) throws PEtALSCDKException {
        assert consumes != null;
        final Collection<JbiConsumesConfig> confs = getAll(consumes.getAny(), EL_CONSUMER, JbiConsumesConfig.class);
        if (confs.isEmpty()) {
            throw new PEtALSCDKException("Missing consumer in Consumes " + toString(consumes));
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

        private Pair(final A a, final B b) {
            this.a = a;
            this.b = b;
        }

        public A getA() {
            return a;
        }

        public B getB() {
            return b;
        }

        public static <A, B> Pair<A, B> of(final A a, final B b) {
            return new Pair<>(a, b);
        }
    }

    public static class Either<A, B> {

        private final @Nullable A a;

        private final @Nullable B b;

        private Either(final @Nullable A a, final @Nullable B b) {
            assert a == null ^ b == null;
            this.a = a;
            this.b = b;
        }

        public boolean isA() {
            return a != null;
        }

        public boolean isB() {
            return b != null;
        }

        public A getA() {
            assert a != null;
            return a;
        }

        public B getB() {
            assert b != null;
            return b;
        }

        public static <A, B> Either<A, B> ofA(final A a) {
            return new Either<A, B>(a, null);
        }

        public static <A, B> Either<A, B> ofB(final B b) {
            return new Either<A, B>(null, b);
        }
    }
}
