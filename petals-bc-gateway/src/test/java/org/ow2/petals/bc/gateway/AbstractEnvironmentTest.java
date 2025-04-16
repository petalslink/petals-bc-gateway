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
package org.ow2.petals.bc.gateway;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.Callable;

import javax.jbi.servicedesc.ServiceEndpoint;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;

import org.awaitility.Awaitility;
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
                specifyEndpoint ? EXTERNAL_HELLO_ENDPOINT : null, certificate, null, key, null, remoteCertificate, null,
                pollingDelay, timeout);
    }

    protected static ConsumesServiceConfiguration createConsumes(final QName interfaceName,
            final @Nullable QName service, final @Nullable String endpoint, final @Nullable String certificate,
            final @Nullable String key, final @Nullable String remoteCertificate, final @Nullable Long pollingDelay) {

        return createConsumes(interfaceName, service, endpoint, certificate, null, key, null, remoteCertificate, null,
                pollingDelay, null);
    }

    /**
     * @param interfaceName
     * @param service
     * @param endpoint
     * @param certificateValue
     *            The certificate to used. If {@code certificatePlaceholderName} is configured, this parameter is only
     *            used to add the certificate as resource of the SU.
     * @param certificatePlaceholderName
     *            If not {@code null} the associated SU parameter will be configured with this placeholder name. The
     *            caller is in charge of configuring the placeholder value.
     * @param keyValue
     *            The key to used. If {@code keyPlaceholderName} is configured, this parameter is only used to add the
     *            key as resource of the SU.
     * @param keyPlaceholderName
     *            If not {@code null} the associated SU parameter will be configured with this placeholder name. The
     *            caller is in charge of configuring the placeholder value.
     * @param remoteCertificateValue
     *            The remote certificate to used. If {@code remoteCertificatePlaceholderName} is configured, this
     *            parameter is only used to add the remote certificate as resource of the SU.
     * @param remoteCertificatePlaceholderName
     *            If not {@code null} the associated SU parameter will be configured with this placeholder name. The
     *            caller is in charge of configuring the placeholder value.
     * @param pollingDelay
     * @param timeout
     * @return
     */
    protected static ConsumesServiceConfiguration createConsumes(final QName interfaceName,
            final @Nullable QName service, final @Nullable String endpoint, final @Nullable String certificateValue,
            final @Nullable String certificatePlaceholderName, final @Nullable String keyValue,
            final @Nullable String keyPlaceholderName, final @Nullable String remoteCertificateValue,
            final @Nullable String remoteCertificatePlaceholderName, final @Nullable Long pollingDelay,
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
                if (certificatePlaceholderName != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_PROVIDER_DOMAIN_CRT)
                            .setTextContent("${" + certificatePlaceholderName + "}");
                } else if (certificateValue != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_CRT)
                            .setTextContent(new File(certificateValue).getName());
                }
                if (keyPlaceholderName != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_PROVIDER_DOMAIN_KEY)
                            .setTextContent("${" + keyPlaceholderName + "}");
                } else if (keyValue != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_KEY)
                            .setTextContent(new File(keyValue).getName());
                }
                if (remoteCertificatePlaceholderName != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_PROVIDER_DOMAIN_REMOTE_CRT)
                            .setTextContent("${" + remoteCertificatePlaceholderName + "}");
                } else if (remoteCertificateValue != null) {
                    addElement(jbiDocument, cDomain, EL_SERVICES_CONSUMER_DOMAIN_REMOTE_CRT)
                            .setTextContent(new File(remoteCertificateValue).getName());
                }
            }
        };

        if (certificateValue != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(certificateValue));
        }
        if (keyValue != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(keyValue));
        }
        if (remoteCertificateValue != null) {
            consumes.addResource(AbstractComponentTest.class.getResource(remoteCertificateValue));
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
        return createProvider(authName, port, null, null, null, null, null, null, null, null);
    }

    /**
     * @param authName
     * @param port
     * @param certificateValue
     *            The certificate to used. If {@code certificatePlaceholderName} is configured, this parameter is only
     *            used to add the certificate as resource of the SU.
     * @param certificatePlaceholderName
     *            If not {@code null} the associated SU parameter will be configured with this placeholder name. The
     *            caller is in charge of configuring the placeholder value.
     * @param keyValue
     *            The key to used. If {@code keyPlaceholderName} is configured, this parameter is only used to add the
     *            key as resource of the SU.
     * @param keyPlaceholderName
     *            If not {@code null} the associated SU parameter will be configured with this placeholder name. The
     *            caller is in charge of configuring the placeholder value.
     * @param remoteCertificateValue
     *            The remote certificate to used. If {@code remoteCertificatePlaceholderName} is configured, this
     *            parameter is only used to add the remote certificate as resource of the SU.
     * @param remoteCertificatePlaceholderName
     *            If not {@code null} the associated SU parameter will be configured with this placeholder name. The
     *            caller is in charge of configuring the placeholder value.
     * @param retryMax
     * @param retryDelay
     * @return
     */
    protected static ServiceConfiguration createProvider(final String authName, final int port,
            final @Nullable String certificateValue, final @Nullable String certificatePlaceholderName,
            final @Nullable String keyValue, final @Nullable String keyPlaceholderName,
            final @Nullable String remoteCertificateValue, final @Nullable String remoteCertificatePlaceholderName,
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
                if (certificatePlaceholderName != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_CRT)
                            .setTextContent("${" + certificatePlaceholderName + "}");
                } else if (certificateValue != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_CRT)
                            .setTextContent(new File(certificateValue).getName());
                }
                if (keyPlaceholderName != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_KEY)
                            .setTextContent("${" + keyPlaceholderName + "}");
                } else if (keyValue != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_KEY)
                            .setTextContent(new File(keyValue).getName());
                }
                if (remoteCertificatePlaceholderName != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_REMOTE_CRT)
                            .setTextContent("${" + remoteCertificatePlaceholderName + "}");
                } else if (remoteCertificateValue != null) {
                    addElement(jbiDocument, pDomain, EL_SERVICES_PROVIDER_DOMAIN_REMOTE_CRT)
                            .setTextContent(new File(remoteCertificateValue).getName());
                }
            }
        };

        if (certificateValue != null) {
            provides.addResource(AbstractComponentTest.class.getResource(certificateValue));
        }
        if (keyValue != null) {
            provides.addResource(AbstractComponentTest.class.getResource(keyValue));
        }
        if (remoteCertificateValue != null) {
            provides.addResource(AbstractComponentTest.class.getResource(remoteCertificateValue));
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
    private static @Nullable ServiceEndpoint getServiceEndpointDifferentFrom(final Component cut,
            final QName serviceName, final String endpointName) {
        for (final ServiceEndpoint endpoint : cut.getEndpointDirectory().resolveEndpointsForService(serviceName)) {
            if (!endpoint.getEndpointName().equals(endpointName)) {
                return endpoint;
            }
        }

        return null;
    }

    /**
     * TODO it would be relevant to check for all domain deployed that everything has been cleaned as desired
     */
    protected static ServiceEndpoint deployTwoDomains(final Component componentUnderTest,
            final ConsumesServiceConfiguration suConsume, final ServiceConfiguration suProvide) throws Exception {

        componentUnderTest.deployService(SU_CONSUMER_NAME, suConsume);

        componentUnderTest.deployService(SU_PROVIDER_NAME, suProvide);

        Awaitility.await("External endpoint not propagated !").atMost(Duration.ofSeconds(10))
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        return getPropagatedServiceEndpoint(componentUnderTest) != null;
                    }
                });

        final ServiceEndpoint endpoint = getPropagatedServiceEndpoint(componentUnderTest);
        assert endpoint != null;
        return endpoint;
    }
}
