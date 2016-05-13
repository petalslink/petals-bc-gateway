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
package org.ow2.petals.bc.gateway.utils;

import java.net.URISyntaxException;
import java.util.logging.Logger;

import javax.xml.namespace.QName;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.easywsdl.wsdl.WSDLFactory;
import org.ow2.easywsdl.wsdl.api.Binding;
import org.ow2.easywsdl.wsdl.api.Description;
import org.ow2.easywsdl.wsdl.api.Endpoint;
import org.ow2.easywsdl.wsdl.api.InterfaceType;
import org.ow2.easywsdl.wsdl.api.Service;
import org.ow2.easywsdl.wsdl.api.WSDLException;
import org.ow2.petals.bc.gateway.commons.messages.ServiceKey;
import org.ow2.petals.commons.log.Level;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.util.ServiceEndpointKey;
import org.ow2.petals.component.framework.util.WSDLUtilImpl;
import org.w3c.dom.Document;

/**
 * 
 * @author vnoel
 *
 */
public class JbiGatewayServiceEndpointHelper {

    private JbiGatewayServiceEndpointHelper() {
        // utility class
    }

    public static Document generateDescription(final @Nullable Document originalDescription,
            final ServiceKey originalKey, final ServiceEndpointKey newKey, final QName newInterfaceName, final Logger logger)
            throws PEtALSCDKException {

        Description description = null;
        if (originalDescription != null) {
            // TODO reuse the reader, the instance, or whatever
            try {
                description = WSDLFactory.newInstance().newWSDLReader().read(originalDescription);
            } catch (final WSDLException | URISyntaxException e) {
                final String msg = "Couldn't read the received description for " + originalKey
                        + ", generating a lightweigth description";
                logger.warning(msg);
                logger.log(Level.FINE, msg, e);
            }

            if (description != null) {
                final Service service = description.getService(originalKey.service);

                if (service != null) {
                    
                    // it can be different in the case of a Provides
                    service.setQName(newKey.getServiceName());

                    final Endpoint endpoint;
                    if (originalKey.endpointName != null) {
                        endpoint = service.getEndpoint(originalKey.endpointName);
                    } else {
                        // TODO how do I know which endpoint is the right one? maybe the provider domain should send us
                        // this information on top of the rest?!

                        // for now let's take the first one!
                        endpoint = service.getEndpoints().isEmpty() ? null : service.getEndpoints().get(0);
                    }

                    // we always generate the endpoint name!
                    if (endpoint != null) {
                        endpoint.setName(newKey.getEndpointName());
                        
                        InterfaceType interfaceType;
                        try {
                            interfaceType = getInterfaceFromService(service.getQName(), endpoint.getName(),
                                    description, logger);
                        } catch (final WSDLException e) {
                            interfaceType = null;
                        }

                        if (interfaceType != null) {
                            // it can be different in the case of a Provides
                            interfaceType.setQName(newInterfaceName);
                        } else {
                            logger.warning("Couldn't find the interface of " + originalKey
                                    + " in the received description, generating a lightweigth description");
                            // TODO should I do that or just keep it... ?
                            description = null;
                        }
                    } else {
                        logger.warning("Couldn't find the endpoint of " + originalKey
                                + " in the received description, generating a lightweigth description");
                        // TODO should I do that or just keep it... ?
                        description = null;
                    }
                } else {
                    logger.warning("Couldn't find the service of " + originalKey
                            + " in the received description, generating a lightweigth description");
                    // TODO should I do that or just keep it... ?
                    description = null;
                }
            }
        } else {
            logger.warning("No description received for " + originalKey + ", generating a lightweigth description");
        }

        if (description == null) {
            // let's generate a minimal one for now
            // but we won't store it, in case we get one from the other side later
            try {
                description = WSDLUtilImpl.createLightWSDL20Description(originalKey.interfaceName,
                        newKey.getServiceName(), newKey.getEndpointName());
            } catch (final WSDLException e) {
                throw new PEtALSCDKException(e);
            }
        }
        assert description != null;

        try {
            Document desc = WSDLUtilImpl.convertDescriptionToDocument(description);
            assert desc != null;
            return desc;
        } catch (final WSDLException e) {
            throw new PEtALSCDKException(e);
        }
    }

    /**
     * TODO Copied from ServiceEndpointHelper from petals-message-exchange, it should be moved to easywsdl normally!
     */
    private static @Nullable InterfaceType getInterfaceFromService(final QName serviceName, final String endpointName,
            final Description description, final Logger logger) throws WSDLException {
        final Service ss = description.getService(serviceName);
        if (ss != null) {
            final Endpoint endpoint = ss.getEndpoint(endpointName);
            if (endpoint != null) {
                final Binding binding = endpoint.getBinding();
                if (binding != null && binding.getInterface() != null) {
                    return binding.getInterface();
                } else {
                    logger.config("No valid binding for endpoint '" + endpointName
                            + "' in component description for service '" + serviceName
                            + "', using service's interface");
                    return ss.getInterface();
                }
            } else {
                logger.config("No endpoint '" + endpointName + "' in component description for service '" + serviceName
                        + "', using service's interface");
                return ss.getInterface();
            }
        } else {
            logger.config("No service '" + serviceName + "' in component description");
            return null;
        }
    }
}
