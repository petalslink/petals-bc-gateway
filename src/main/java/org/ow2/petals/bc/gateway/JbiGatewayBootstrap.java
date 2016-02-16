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
package org.ow2.petals.bc.gateway;

import java.util.List;

import org.ow2.petals.bc.gateway.utils.JbiGatewayConstants;
import org.ow2.petals.component.framework.DefaultBootstrap;

/**
 * There is one instance of this class for the whole component. The class is declared in the jbi.xml.
 * 
 * @author vnoel
 *
 */
public class JbiGatewayBootstrap extends DefaultBootstrap {

    private static final String ATTR_NAME_RESTRICT = "restrictToComponentListeners";

    @SuppressWarnings("null")
    private static final String PARAM_NAME_RESTRICT = JbiGatewayConstants.EL_RESTRICT_TO_COMPONENT_LISTENERS
            .getLocalPart();

    @Override
    public List<String> getAttributeList() {

        final List<String> attributes = super.getAttributeList();

        attributes.add(ATTR_NAME_RESTRICT);

        return attributes;
    }

    public void setRestrictToComponentListeners(final boolean value) {
        setParam(PARAM_NAME_RESTRICT, Boolean.toString(value));
    }

    public boolean getRestrictToComponentListeners() {
        return getParamAsBoolean(PARAM_NAME_RESTRICT, false);
    }

}
