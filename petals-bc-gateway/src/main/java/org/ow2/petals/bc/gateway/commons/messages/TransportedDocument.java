/**
 * Copyright (c) 2016-2019 Linagora
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
package org.ow2.petals.bc.gateway.commons.messages;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import javax.xml.transform.TransformerException;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.ebmwebsourcing.easycommons.xml.XMLHelper;

/**
 * TODO use proper DOM serialization?!
 *
 */
public class TransportedDocument implements Serializable {

    private static final long serialVersionUID = 405158251976165734L;

    private transient Document document;

    public TransportedDocument(final Document document) {
        this.document = document;
    }

    public Document getDocument() {
        return document;
    }

    @SuppressWarnings("null")
    private void readObject(final ObjectInputStream s) throws IOException {
        try {
            this.document = XMLHelper.createDocumentFromString((String) s.readObject());
        } catch (final ClassNotFoundException | SAXException e) {
            throw new IOException(e);
        }
    }

    private void writeObject(final ObjectOutputStream s) throws IOException {
        try {
            s.writeObject(XMLHelper.createStringFromDOMNode(this.document));
        } catch (final TransformerException e) {
            throw new IOException(e);
        }
    }
}
