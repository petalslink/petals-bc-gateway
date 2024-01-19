/**
 * Copyright (c) 2023-2024 Linagora
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
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Properties;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ow2.petals.bc.gateway.AbstractComponentTest.BasicComponentConfiguration;
import org.ow2.petals.bc.gateway.junit.extensions.EnsurePortsAreOKExtension;
import org.ow2.petals.bc.gateway.junit.extensions.api.EnsurePortsAreOK;
import org.ow2.petals.component.framework.junit.extensions.ComponentConfigurationExtension;
import org.ow2.petals.component.framework.junit.extensions.ComponentUnderTestExtension;
import org.ow2.petals.component.framework.junit.extensions.api.ComponentUnderTest;
import org.ow2.petals.component.framework.junit.rule.ParameterGenerator;
import org.ow2.petals.junit.extensions.log.handler.InMemoryLogHandlerExtension;

public class BcGatewayReloadPlaceholderTest extends AbstractEnvironmentTest implements BcGatewayJbiTestConstants {

    private static final String SSL_CLIENT_CERTIFICATE_PLACEHOLDER_NAME = "ssl.client.certificate";

    private static final String SSL_CLIENT_KEY_PLACEHOLDER_NAME = "ssl.client.key";

    private static final String SSL_CLIENT_REMOTE_CERTIFICATE_PLACEHOLDER_NAME = "ssl.client.remote.certificate";

    private static final String SSL_SERVER_CERTIFICATE_PLACEHOLDER_NAME = "ssl.server.certificate";

    private static final String SSL_SERVER_KEY_PLACEHOLDER_NAME = "ssl.server.key";

    private static final String SSL_SERVER_REMOTE_CERTIFICATE_PLACEHOLDER_NAME = "ssl.server.remote.certificate";

    @Order(0)
    @EnsurePortsAreOKExtension(
            ports = { AbstractEnvironmentTest.TEST_TRANSPORT_PORT, AbstractEnvironmentTest.DEFAULT_PORT }
    )
    private static EnsurePortsAreOK ENSURE_PORTS_ARE_OK;

    @Order(1)
    @TempDir
    private static Path TEMP_FOLDER;

    private static String COMPONENT_PROPERTIES_FILE;

    @Order(2)
    @ComponentUnderTestExtension(
            inMemoryLogHandler = @InMemoryLogHandlerExtension, explicitPostInitialization = true, componentConfiguration = @ComponentConfigurationExtension(
                    name = "G", implementation = BasicComponentConfiguration.class
            )
    )
    private static ComponentUnderTest COMPONENT_UNDER_TEST;

    @BeforeAll
    private static void completesComponentUnderTestConfiguration() throws Exception {
        COMPONENT_UNDER_TEST
                // we need faster checks for our tests, 2000 is too long!
                .setParameter(new QName(CDK_NAMESPACE_URI, "time-beetween-async-cleaner-runs"), "100")
                .setParameter(new QName("http://petals.ow2.org/components/extensions/version-5", "properties-file"),
                        new ParameterGenerator() {

                            @Override
                            public String generate() throws Exception {

                                final Properties componentProperties = new Properties();
                                componentProperties.setProperty(SSL_CLIENT_CERTIFICATE_PLACEHOLDER_NAME, "");
                                componentProperties.setProperty(SSL_CLIENT_KEY_PLACEHOLDER_NAME, "");
                                componentProperties.setProperty(SSL_CLIENT_REMOTE_CERTIFICATE_PLACEHOLDER_NAME, "");
                                componentProperties.setProperty(SSL_SERVER_CERTIFICATE_PLACEHOLDER_NAME, "");
                                componentProperties.setProperty(SSL_SERVER_KEY_PLACEHOLDER_NAME, "");
                                componentProperties.setProperty(SSL_SERVER_REMOTE_CERTIFICATE_PLACEHOLDER_NAME, "");

                                final File componentPropertiesFile = TEMP_FOLDER
                                        .resolve("component-properties.properties").toFile();
                                try (final FileOutputStream fos = new FileOutputStream(componentPropertiesFile)) {
                                    componentProperties.store(fos, "Initial placeholders");
                                }

                                COMPONENT_PROPERTIES_FILE = componentPropertiesFile.getAbsolutePath();
                                return COMPONENT_PROPERTIES_FILE;
                            }

                        })
                .registerExternalServiceProvider(EXTERNAL_HELLO_ENDPOINT, HELLO_SERVICE, HELLO_INTERFACE)
                .postInitComponentUnderTest();
    }

    /**
     * <p>
     * Check the right reloading of placeholder used to configure the SSL part.
     * </p>
     * <p>
     * First, domains are configured without SSL. And next, SSL configuration is added.
     * </p>
     */
    @Test
    public void reloadSSLPlaceholders() throws Exception {

        // 1 - Create domains configured without SSL through placeholders
        deployTwoDomains(COMPONENT_UNDER_TEST,
                createConsumes(HELLO_INTERFACE, HELLO_SERVICE, EXTERNAL_HELLO_ENDPOINT, BcGatewaySSLTest.SERVER_CRT,
                        SSL_SERVER_CERTIFICATE_PLACEHOLDER_NAME, BcGatewaySSLTest.SERVER_KEY,
                        SSL_SERVER_KEY_PLACEHOLDER_NAME, BcGatewaySSLTest.CLIENT_CRT,
                        SSL_CLIENT_CERTIFICATE_PLACEHOLDER_NAME, null, null),
                createProvider(TEST_AUTH_NAME, TEST_TRANSPORT_PORT, BcGatewaySSLTest.CLIENT_CRT,
                        SSL_CLIENT_CERTIFICATE_PLACEHOLDER_NAME, BcGatewaySSLTest.CLIENT_KEY,
                        SSL_CLIENT_KEY_PLACEHOLDER_NAME, BcGatewaySSLTest.SERVER_CRT,
                        SSL_CLIENT_REMOTE_CERTIFICATE_PLACEHOLDER_NAME, null, null));

        // 2 - Change placeholder configuration and reload it.
        {
            final Properties componentProperties = new Properties();
            componentProperties.setProperty(SSL_CLIENT_CERTIFICATE_PLACEHOLDER_NAME,
                    new File(BcGatewaySSLTest.CLIENT_CRT).getName());
            componentProperties.setProperty(SSL_CLIENT_KEY_PLACEHOLDER_NAME,
                    new File(BcGatewaySSLTest.CLIENT_KEY).getName());
            componentProperties.setProperty(SSL_CLIENT_REMOTE_CERTIFICATE_PLACEHOLDER_NAME,
                    new File(BcGatewaySSLTest.SERVER_CRT).getName());
            componentProperties.setProperty(SSL_SERVER_CERTIFICATE_PLACEHOLDER_NAME,
                    new File(BcGatewaySSLTest.SERVER_CRT).getName());
            componentProperties.setProperty(SSL_SERVER_KEY_PLACEHOLDER_NAME,
                    new File(BcGatewaySSLTest.SERVER_KEY).getName());
            componentProperties.setProperty(SSL_SERVER_REMOTE_CERTIFICATE_PLACEHOLDER_NAME,
                    new File(BcGatewaySSLTest.CLIENT_CRT).getName());
            try (final FileOutputStream fos = new FileOutputStream(COMPONENT_PROPERTIES_FILE)) {
                componentProperties.store(fos, "Updated placeholders");
            }
            COMPONENT_UNDER_TEST.getComponentObject().reloadPlaceHolders();
        }
    }
}
