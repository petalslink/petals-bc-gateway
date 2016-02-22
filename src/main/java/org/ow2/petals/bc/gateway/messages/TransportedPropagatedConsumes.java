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
package org.ow2.petals.bc.gateway.messages;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import javax.xml.transform.TransformerException;

import org.eclipse.jdt.annotation.Nullable;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.ebmwebsourcing.easycommons.xml.XMLHelper;

public class TransportedPropagatedConsumes implements Serializable {

    private static final long serialVersionUID = -5787838058168540231L;

    public final ServiceKey service;

    public transient @Nullable Document description;

    public TransportedPropagatedConsumes(final ServiceKey service, final @Nullable Document description) {
        this.service = service;
        this.description = description;
    }

    private void readObject(final ObjectInputStream s) throws IOException {
        try {
            s.defaultReadObject();

            if (s.readBoolean()) {
                this.description = XMLHelper.createDocumentFromString((String) s.readObject());
            }
        } catch (final ClassNotFoundException | SAXException e) {
            throw new IOException(e);
        }
    }

    private void writeObject(final ObjectOutputStream s) throws IOException {
        s.defaultWriteObject();

        final Document description = this.description;
        if (description != null) {
            s.writeBoolean(true);
            try {
                s.writeObject(XMLHelper.createStringFromDOMNode(description));
            } catch (final TransformerException e) {
                throw new IOException(e);
            }
        } else {
            s.writeBoolean(false);
        }
    }
}
