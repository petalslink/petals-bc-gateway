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

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.component.framework.junit.impl.ComponentConfiguration;
import org.ow2.petals.junit.rules.log.handler.InMemoryLogHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class AbstractComponentTestUtils extends AbstractEnvironmentTest implements BcGatewayJbiTestConstants {

    protected static final InMemoryLogHandler IN_MEMORY_LOG_HANDLER = new InMemoryLogHandler();

    protected static final ComponentConfiguration CONFIGURATION = new ComponentConfiguration("G") {
        @Override
        protected void extraJBIConfiguration(final @Nullable Document jbiDocument) {
            assert jbiDocument != null;

            final Element compo = getComponentElement(jbiDocument);

            final Element transport = addElement(jbiDocument, compo, EL_TRANSPORT_LISTENER);
            transport.setAttribute(ATTR_TRANSPORT_LISTENER_ID, TEST_TRANSPORT_NAME);
            addElement(jbiDocument, transport, EL_TRANSPORT_LISTENER_PORT, "" + TEST_TRANSPORT_PORT);

            final Element transport2 = addElement(jbiDocument, compo, EL_TRANSPORT_LISTENER);
            transport2.setAttribute(ATTR_TRANSPORT_LISTENER_ID, TEST_TRANSPORT2_NAME);
            // the element is needed even if without value!
            addElement(jbiDocument, transport2, EL_TRANSPORT_LISTENER_PORT);
        }
    };
}
