/**
 * Copyright (c) 2015-2023 Linagora
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

import java.io.File;

import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.component.framework.junit.Component;
import org.ow2.petals.component.framework.junit.impl.ConsumesServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.ServiceConfiguration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ebmwebsourcing.easycommons.lang.UncheckedException;

public class AbstractEnvironmentTest extends AbstractTest implements BcGatewayJbiTestConstants {

    protected static final String FAULT = "<c/>";

    protected static final String OUT = "<b/>";

    protected static final String IN = "<a/>";

    protected static final Exception ERROR = new Exception("exchange arriving too late");
    static {
        // we don't really care about the stacktrace
        ERROR.setStackTrace(new StackTraceElement[0]);
    }

    public static final String SU_CONSUMER_NAME = "suc";

    public static final String SU_PROVIDER_NAME = "sup";

    protected static final String HELLO_NS = "http://petals.ow2.org";

    public static final String HELLO_INTERFACE_LOCALPART = "HelloInterface";

    public static final QName HELLO_INTERFACE = new QName(HELLO_NS, HELLO_INTERFACE_LOCALPART);

    public static final String HELLO_SERVICE_LOCALPART = "HelloService";

    public static final QName HELLO_SERVICE = new QName(HELLO_NS, HELLO_SERVICE_LOCALPART);

    public static final QName HELLO_OPERATION = new QName(HELLO_NS, "sayHello");

    public static final QName PRINT_OPERATION = new QName(HELLO_NS, "printHello");

    public static final String EXTERNAL_HELLO_ENDPOINT = "externalHelloEndpoint";

    protected static final int TEST_TRANSPORT_PORT = 7501;

    public static final String TEST_TRANSPORT_NAME = "test-transport";

    protected static final String TEST_TRANSPORT2_NAME = "test-transport-default-port";

    protected static final String TEST_CONSUMER_DOMAIN = "test-consumer-domain";

    public static final String TEST_AUTH_NAME = "test-auth-name";

    protected static final String TEST_PROVIDER_DOMAIN = "test-provider-domain";

    protected static final long DEFAULT_TIMEOUT_FOR_COMPONENT_SEND = 2000;

    public static Marshaller MARSHALLER;

    public static Unmarshaller UNMARSHALLER;

    static {
        try {
            final JAXBContext context = JAXBContext.newInstance(org.ow2.petals.ObjectFactory.class);
            UNMARSHALLER = context.createUnmarshaller();
            MARSHALLER = context.createMarshaller();
            MARSHALLER.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        } catch (final JAXBException e) {
            throw new UncheckedException(e);
        }
    }

    protected static ConsumesServiceConfiguration createHelloConsumes(final boolean specifyService,
            final boolean specifyEndpoint) {
        return createHelloConsumes(specifyService, specifyEndpoint, null, null, null, null, null);
    }

    public static ConsumesServiceConfiguration createHelloConsumes(final boolean specifyService,
            final boolean specifyEndpoint, final @Nullable Long timeout) {
        return createHelloConsumes(specifyService, specifyEndpoint, null, null, null, null, timeout);
    }

    protected static ConsumesServiceConfiguration createHelloConsumes(final boolean specifyService,
            final boolean specifyEndpoint, final @Nullable String certificate, final @Nullable String key,
            final @Nullable String remoteCertificate, final @Nullable Long pollingDelay) {
        return createHelloConsumes(specifyService, specifyEndpoint, certificate, key, remoteCertificate, pollingDelay,
                null);
    }

    protected static ConsumesServiceConfiguration createHelloConsumes(final boolean specifyService,
            final boolean specifyEndpoint, final @Nullable String certificate, final @Nullable String key,
            final @Nullable String remoteCertificate, final @Nullable Long pollingDelay, final @Nullable Long timeout) {

        // can't have endpoint specified without service
        assert !specifyEndpoint || specifyService;

        return createConsumes(HELLO_INTERFACE, specifyService ? HELLO_SERVICE : null,
                specifyEndpoint ? EXTERNAL_HELLO_ENDPOINT : null, certificate, key, remoteCertificate, pollingDelay,
                timeout);
    }

    protected static ConsumesServiceConfiguration createConsumes(final QName interfaceName,
            final @Nullable QName service, final @Nullable String endpoint, final @Nullable String certificate,
            final @Nullable String key, final @Nullable String remoteCertificate, final @Nullable Long pollingDelay) {

        return createConsumes(interfaceName, service, endpoint, certificate, key, remoteCertificate, pollingDelay,
                null);
    }

    protected static ConsumesServiceConfiguration createConsumes(final QName interfaceName,
            final @Nullable QName service, final @Nullable String endpoint, final @Nullable String certificate,
            final @Nullable String key, final @Nullable String remoteCertificate, final @Nullable Long pollingDelay,
            final @Nullable Long timeout) {

        final ConsumesServiceConfiguration consumes = new ConsumesServiceConfiguration(interfaceName, service,
                endpoint) {
            @Override
            protected void extraServiceConfiguration(final @Nullable Document jbiDocument,
                    final @Nullable Element service) {
                assert jbiDocument != null;
                assert service != null;

                final Element mapping = addElement(jbiDocument, service, EL_CONSUMER);
                mapping.setAttribute(ATTR_CONSUMES_CONSUMER_DOMAIN, TEST_CONSUMER_DOMAIN);

                final Element compGAV = addElement(jbiDocument, service,
                        new org.ow2.petals.jbi.descriptor.extension.generated.ObjectFactory().createComponentGav(null)
                                .getName());
                compGAV.setTextContent("petals-bc-gateway");
            }

            @Override
            protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
                assert jbiDocument != null;

                final Element services = getOrCreateServicesElement(jbiDocument);

                final Element cDomain = addElement(jbiDocument, services, EL_CONSUMER_DOMAIN);
                cDomain.setAttribute(ATTR_SERVICES_CONSUMER_DOMAIN_ID, TEST_CONSUMER_DOMAIN);
                cDomain.setAttribute(ATTR_SERVICES_CONSUMER_DOMAIN_TRANSPORT, TEST_TRANSPORT_NAME);
                if (pollingDelay != null) {
                    cDomain.setAttribute(ATTR_SERVICES_CONSUMER_DOMAIN_POLLING_DELAY, "" + pollingDelay);
                }

                addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_AUTH_NAME, TEST_AUTH_NAME);
                if (certificate != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_CRT)
                            .setTextContent(new File(certificate).getName());
                }
                if (key != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_KEY)
                            .setTextContent(new File(key).getName());
                }
                if (remoteCertificate != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_REMOTE_CRT)
                            .setTextContent(new File(remoteCertificate).getName());
                }
            }
        };

        if (certificate != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(certificate));
        }
        if (key != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(key));
        }
        if (remoteCertificate != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(remoteCertificate));
        }

        if (timeout != null) {
            consumes.setTimeout(timeout);
        } else {
            // let's use a smaller timeout time by default
            consumes.setTimeout(DEFAULT_TIMEOUT_FOR_COMPONENT_SEND);
        }

        return consumes;
    }

    protected static ServiceConfiguration createProvider() {
        return createProvider(TEST_AUTH_NAME, TEST_TRANSPORT_PORT);
    }

    public static ServiceConfiguration createProvider(final String authName, final int port) {
        return createProvider(authName, port, null, null, null, null, null);
    }

    protected static ServiceConfiguration createProvider(final String authName, final int port,
            final @Nullable String certificate, final @Nullable String key, final @Nullable String remoteCertificate,
            final @Nullable Integer retryMax, final @Nullable Long retryDelay) {
        final ServiceConfiguration provides = new ServiceConfiguration() {
            @Override
            protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
                assert jbiDocument != null;

                final Element services = getOrCreateServicesElement(jbiDocument);

                final Element pDomain = addElement(jbiDocument, services, EL_PROVIDER_DOMAIN);
                pDomain.setAttribute(ATTR_SERVICES_PROVIDER_DOMAIN_ID, TEST_PROVIDER_DOMAIN);
                if (retryMax != null) {
                    pDomain.setAttribute(ATTR_SERVICES_PROVIDER_DOMAIN_RETRY_MAX, "" + retryMax);
                }
                if (retryDelay != null) {
                    pDomain.setAttribute(ATTR_SERVICES_PROVIDER_DOMAIN_RETRY_DELAY, "" + retryDelay);
                }
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_IP).setTextContent("localhost");
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_PORT).setTextContent("" + port);
                addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_AUTH_NAME).setTextContent(authName);
                if (certificate != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_CRT)
                            .setTextContent(new File(certificate).getName());
                }
                if (key != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_KEY)
                            .setTextContent(new File(key).getName());
                }
                if (remoteCertificate != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_REMOTE_CRT)
                            .setTextContent(new File(remoteCertificate).getName());
                }
            }
        };

        if (certificate != null) {
            provides.addResource(AbstractComponentTest.class.getResource(certificate));
        }
        if (key != null) {
            provides.addResource(AbstractComponentTest.class.getResource(key));
        }
        if (remoteCertificate != null) {
            provides.addResource(AbstractComponentTest.class.getResource(remoteCertificate));
        }

        return provides;
    }

    /**
     * <p>
     * Retrieve the service endpoint previously deployed, associated to:
     * </p>
     * <ul>
     * <li>service name: {{@value #HELLO_NS}}/{@value #HELLO_SERVICE_LOCALPART},</li>
     * <li>endpoint name: {@value #EXTERNAL_HELLO_ENDPOINT}.</li>
     * </ul>
     * 
     * @param cut
     *            The component under test on which the service endpoint should be deployed
     * @return The expected service endpoint or {@code null} if not deployed.
     */
    public static @Nullable ServiceEndpoint getPropagatedServiceEndpoint(final Component cut) {
        return getServiceEndpointDifferentFrom(cut, HELLO_SERVICE, EXTERNAL_HELLO_ENDPOINT);
    }

    /**
     * Retrieve a service endpoint previously deployed, matching the given service name, but <b>not</b> matching the
     * given endpoint name.
     * 
     * @param cut
     *            The component under test on which the service endpoint should be deployed
     * @param serviceName
     *            Service name of the service endpoint to retrieve
     * @param endpointName
     *            Endpoint name of the service endpoint to retrieve
     * @return The expected service endpoint or {@code null} if not deployed.
     */
    private static @Nullable ServiceEndpoint getServiceEndpointDifferentFrom(
            final Component cut,
            final QName serviceName, final String endpointName) {
        for (final ServiceEndpoint endpoint : cut.getEndpointDirectory().resolveEndpointsForService(serviceName)) {
            if (!endpoint.getEndpointName().equals(endpointName)) {
                return endpoint;
            }
        }

        return null;
    }
}
