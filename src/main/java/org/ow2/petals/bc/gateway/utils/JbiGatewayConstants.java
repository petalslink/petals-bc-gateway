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

import javax.xml.namespace.QName;

public interface JbiGatewayConstants {

    public static final String JG_NS_URI = "http://petals.ow2.org/components/petals-bc-jbi-gateway/version-1.0";

    public static final QName EL_RESTRICT_TO_COMPONENT_LISTENERS = new QName(JG_NS_URI, "restrict-to-component-listeners");

}
