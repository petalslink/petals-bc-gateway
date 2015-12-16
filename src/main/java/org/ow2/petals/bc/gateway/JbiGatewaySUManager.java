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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.JbiGatewayComponent.TransportListener;
import org.ow2.petals.bc.gateway.inbound.JbiGatewayExternalListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.ConsumerDomain;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.bc.AbstractBindingComponent;
import org.ow2.petals.component.framework.bc.BindingComponentServiceUnitManager;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.listener.AbstractExternalListener;
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

    private final Map<String, ConsumerDomain> consumerDomains = new HashMap<>();

    public JbiGatewaySUManager(final JbiGatewayComponent component) {
        super(component);
    }

    @Override
    protected AbstractExternalListener createExternalListener() {
        return new JbiGatewayExternalListener(this);
    }

    @Override
    protected void doInit(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;
        final List<ConsumerDomain> jbiConsumerDomains = JbiGatewayJBIHelper
                .getConsumerDomains(suDH.getDescriptor().getServices());
        try {
            for (final ConsumerDomain cd : jbiConsumerDomains) {
                final TransportListener tl = getComponent().getTransportListener(cd.transport);
                if (tl == null) {
                    throw new PEtALSCDKException(
                            String.format("Missing transporter '%s' needed by consumer domain '%s' in SU '%s'",
                                    cd.transport, cd.id, suDH.getName()));
                }
                tl.createConsumerDomainDispatcherIfNeeded(cd.authName);
                consumerDomains.put(cd.id, cd);
            }
            // TODO register the provides to the JBIListener so that it knows what to do with exchanges for it

            // TODO initialise provider domains
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Error during SU initialisation, undoing everything");
            for (final ConsumerDomain cd : jbiConsumerDomains) {
                consumerDomains.remove(cd.id);
            }
            throw e;
        }
    }

    @Override
    protected void doShutdown(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;
        final List<ConsumerDomain> jbiConsumerDomains = JbiGatewayJBIHelper
                .getConsumerDomains(suDH.getDescriptor().getServices());
        for (final ConsumerDomain cd : jbiConsumerDomains) {
            consumerDomains.remove(cd.id);
        }
        // TODO remove the provides from the JBIListener so that it stops giving it exchanges
    }

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractBindingComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }

    public @Nullable ConsumerDomain getConsumerDomain(final String consumerDomainId) {
        return consumerDomains.get(consumerDomainId);
    }
}
