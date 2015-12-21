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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiConsumerDomain;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.configuration.SuConfigurationParameters;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.su.AbstractServiceUnitManager;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;

import io.netty.channel.Channel;

/**
 * There is one instance of this class for the whole component.
 * 
 * @author vnoel
 *
 */
public class JbiGatewaySUManager extends AbstractServiceUnitManager {

    /**
     * These are the consumer domains declared in the SU jbi.xml.
     * 
     * They are indexed by their id!
     * 
     * TODO this is only useful for {@link #getConsumerDomain(Consumes, SuConfigurationParameters)} to work...
     */
    private final Map<String, JbiConsumerDomain> jbiConsumerDomains = new HashMap<>();

    /**
     * These are the actual consumer partner actually connected to us, potentially through multiple {@link Channel}
     * and/or {@link TransportListener}.
     * 
     * They are indexed by their auth-name!
     * 
     * The accesses are not very frequent, so no need to introduce a specific performance oriented locking. We just rely
     * on simple synchronization.
     * 
     * TODO The key is for now the auth-name declared in the jbi.xml, but later we need to introduce something better to
     * identify consumer and not simply a string Because this corresponds to a validity check of the consumer. e.g., a
     * public key fingerprint or something like that
     */
    @SuppressWarnings("null")
    private final Map<String, ConsumerDomain> consumerDomains = Collections
            .synchronizedMap(new HashMap<String, ConsumerDomain>());

    public JbiGatewaySUManager(final JbiGatewayComponent component) {
        super(component);
    }

    @Override
    protected void doInit(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;
        final List<JbiConsumerDomain> jcds = JbiGatewayJBIHelper
                .getConsumerDomains(suDH.getDescriptor().getServices());
        try {
            for (final JbiConsumerDomain jcd : jcds) {
                final TransportListener tl = getComponent().getTransportListener(jcd.transport);
                if (tl == null) {
                    throw new PEtALSCDKException(
                            String.format("Missing transporter '%s' needed by consumer domain '%s' in SU '%s'",
                                    jcd.transport, jcd.id, suDH.getName()));
                }
                jbiConsumerDomains.put(jcd.id, jcd);
                consumerDomains.put(jcd.authName, new ConsumerDomain(jcd));
            }
            // TODO initialise provider domains
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Error during SU initialisation, undoing everything");
            for (final JbiConsumerDomain jcd : jcds) {
                jbiConsumerDomains.remove(jcd.id);
                consumerDomains.remove(jcd.authName);
            }
            throw e;
        }

        final List<Consumes> registered = new ArrayList<>();
        try {
            for (final Consumes consumes : suDH.getDescriptor().getServices().getConsumes()) {
                assert consumes != null;
                final SuConfigurationParameters extensions = suDH.getConfigurationExtensions(consumes);
                assert extensions != null;
                getConsumerDomain(consumes, extensions).register(consumes);
                registered.add(consumes);
            }
        } catch (final Exception e) {
            this.logger.warning("Error while registering consumes to the consumer domains, undoing everything");
            for (final Consumes consumes : registered) {
                try {
                    assert consumes != null;
                    final SuConfigurationParameters extensions = suDH.getConfigurationExtensions(consumes);
                    assert extensions != null;
                    getConsumerDomain(consumes, extensions).deregister(consumes);
                } catch (final Exception e1) {
                    this.logger.log(Level.WARNING, "Error while deregistering consumes", e1);
                }
            }
            throw e;
        }

        // TODO register the provides to the JBIListener so that it knows what to do with exchanges for it

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
                getConsumerDomain(consumes, extensions).deregister(consumes);
            } catch (final Exception e) {
                exceptions.add(e);
            }
        }

        for (final JbiConsumerDomain cd : jbiConsumerDomains.values()) {
            jbiConsumerDomains.remove(cd.id);
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

    private ConsumerDomain getConsumerDomain(final Consumes consumes, final SuConfigurationParameters extensions)
            throws PEtALSCDKException {
        final String consumerDomainId = extensions.get(JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN.getLocalPart());
        if (consumerDomainId == null) {
            throw new PEtALSCDKException(String.format("Missing %s in Consumes for '%s/%s/%s'",
                    JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN, consumes.getInterfaceName(),
                    consumes.getServiceName(), consumes.getEndpointName()));
        }
        final JbiConsumerDomain jcd = jbiConsumerDomains.get(consumerDomainId);
        if (jcd == null) {
            throw new PEtALSCDKException(
                    String.format("No consumer domain was defined in the SU for '%s'", consumerDomainId));
        }
        final ConsumerDomain cd = getConsumerDomain(jcd.authName);
        // it can't be null, the SUManager should have created it!
        assert cd != null;
        return cd;
    }

    public @Nullable ConsumerDomain getConsumerDomain(final String consumerAuthName) {
        return consumerDomains.get(consumerAuthName);
    }
}
