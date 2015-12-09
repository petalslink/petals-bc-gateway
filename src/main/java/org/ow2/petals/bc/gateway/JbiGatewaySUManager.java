/**
 * Copyright (c) 2015 Linagora
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

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.su.BindingComponentServiceUnitManager;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;

/**
 * There is one instance of this class for the whole component. The class is declared in {@link JbiGatewayComponent}.
 * 
 * It mainly takes care of setting-up the {@link Provides} because the {@link Consumes} are taken care of by
 * {@link JbiGatewayExternalListener}.
 * 
 * @author vnoel
 *
 */
public class JbiGatewaySUManager extends BindingComponentServiceUnitManager {

    public JbiGatewaySUManager(final JbiGatewayComponent component) {
        super(component);
    }

    @Override
    protected void doInit(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        // TODO get SU informations from jbi.xml and initialise them
    }

    @Override
    protected void doShutdown(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        // TODO forget any SU related things
    }

    @Override
    protected void doStart(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;
        // TODO register the provides to the JBIListener so that it knows what to do with exchanges for it
        // Note: the Consumes are handled by the external listener
    }

    @Override
    protected void doStop(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;
        // TODO remove the provides from the JBIListener so that it stops giving it exchanges
        // the Consumes are handled by the external listener
    }
}
