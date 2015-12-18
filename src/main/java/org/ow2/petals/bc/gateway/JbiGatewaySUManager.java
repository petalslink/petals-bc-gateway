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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomainDispatcher;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.ConsumerDomain;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.configuration.SuConfigurationParameters;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.su.AbstractServiceUnitManager;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;

/**
 * There is one instance of this class for the whole component.
 * 
 * It mainly takes care of setting-up the {@link Provides} because the {@link Consumes} are taken care of by
 * {@link JbiGatewayExternalListener}.
 * 
 * @author vnoel
 *
 */
public class JbiGatewaySUManager extends AbstractServiceUnitManager {

    private final Map<String, ConsumerDomain> consumerDomains = new HashMap<>();

    public JbiGatewaySUManager(final JbiGatewayComponent component) {
        super(component);
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

        final List<Consumes> registered = new ArrayList<>();
        try {
            for (final Consumes consumes : suDH.getDescriptor().getServices().getConsumes()) {
                assert consumes != null;
                final SuConfigurationParameters extensions = suDH.getConfigurationExtensions(consumes);
                assert extensions != null;
                final ConsumerDomainDispatcher dispatcher = getDispatcher(consumes, extensions);
                dispatcher.register(consumes);
                registered.add(consumes);
            }
        } catch (final Exception e) {
            this.logger.warning("Error while registering consumes to the consumer domains, undoing it");
            for (final Consumes consumes : registered) {
                try {
                    assert consumes != null;
                    final SuConfigurationParameters extensions = suDH.getConfigurationExtensions(consumes);
                    assert extensions != null;
                    final ConsumerDomainDispatcher dispatcher = getDispatcher(consumes, extensions);
                    dispatcher.deregister(consumes);
                } catch (final Exception e1) {
                    this.logger.log(Level.WARNING, "Error while deregistering consumes", e1);
                }
            }
            throw e;
        }
    }

    @Override
    protected void doShutdown(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final List<Throwable> exceptions = new ArrayList<>();
        for (final Consumes consumes : suDH.getDescriptor().getServices().getConsumes()) {
            assert consumes != null;
            final SuConfigurationParameters extensions = suDH.getConfigurationExtensions(consumes);
            assert extensions != null;
            try {
                final ConsumerDomainDispatcher dispatcher = getDispatcher(consumes, extensions);
                dispatcher.deregister(consumes);
            } catch (final Exception e) {
                exceptions.add(e);
            }
        }

        for (final ConsumerDomain cd : consumerDomains.values()) {
            consumerDomains.remove(cd.id);
        }

        if (!exceptions.isEmpty()) {
            final PEtALSCDKException ex = new PEtALSCDKException("Errors while deregistering consumes");
            for (final Throwable e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }

        // TODO remove the provides from the JBIListener so that it stops giving it exchanges
    }

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }

    public @Nullable ConsumerDomain getConsumerDomain(final String consumerDomainId) {
        return consumerDomains.get(consumerDomainId);
    }

    private ConsumerDomainDispatcher getDispatcher(final Consumes consumes, final SuConfigurationParameters extensions)
            throws PEtALSCDKException {
        final String consumerDomain = extensions.get(JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN.getLocalPart());
        if (consumerDomain == null) {
            throw new PEtALSCDKException(String.format("Missing %s in Consumes for '%s/%s/%s'",
                    JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN, consumes.getInterfaceName(),
                    consumes.getServiceName(), consumes.getEndpointName()));
        }
        final ConsumerDomain cd = consumerDomains.get(consumerDomain);
        if (cd == null) {
            throw new PEtALSCDKException(
                    String.format("No consumer domain was defined in the SU for '%s'", consumerDomain));
        }
        final TransportListener tl = getComponent().getTransportListener(cd.transport);
        // it can't be null, the SUManager should have checked its existence!
        assert tl != null;
        final ConsumerDomainDispatcher dispatcher = tl.getConsumerDomainDispatcher(cd.authName);
        // it can't be null, the SUManager should have created it!
        assert dispatcher != null;
        return dispatcher;
    }
}
