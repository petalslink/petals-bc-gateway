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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.JbiProviderDomain;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.configuration.SuConfigurationParameters;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
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
     * These are the provider domains declared in the SU jbi.xml.
     * 
     * They are indexed by their id!
     * 
     * TODO this is only useful for {@link #getProviderDomain(Provides, SuConfigurationParameters)} to work...
     */
    private final Map<String, JbiProviderDomain> jbiProviderDomains = new HashMap<>();

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

    /**
     * The key is the provider domain id!
     * 
     * The accesses are not very frequent, so no need to introduce a specific performance oriented locking. We just rely
     * on simple synchronization.
     * 
     * TODO we also need a map between created endpoints and ProviderDomain!
     */
    @SuppressWarnings("null")
    private final Map<String, ProviderDomain> providerDomains = Collections
            .synchronizedMap(new HashMap<String, ProviderDomain>());

    public JbiGatewaySUManager(final JbiGatewayComponent component) {
        super(component);
    }

    @Override
    protected void doInit(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;
        final List<JbiConsumerDomain> jcds = JbiGatewayJBIHelper
                .getConsumerDomains(suDH.getDescriptor().getServices());
        final List<JbiProviderDomain> jpds = JbiGatewayJBIHelper
                .getProviderDomains(suDH.getDescriptor().getServices());
        try {
            for (final JbiConsumerDomain jcd : jcds) {
                final TransportListener tl = getComponent().getTransportListener(jcd.transport);
                if (tl == null) {
                    throw new PEtALSCDKException(
                            String.format("Missing transporter '%s' needed by consumer domain '%s' in SU '%s'",
                                    jcd.transport, jcd.id, suDH.getName()));
                }
                jbiConsumerDomains.put(jcd.id, jcd);
                consumerDomains.put(jcd.authName, new ConsumerDomain(getComponent(), jcd));
            }

            for (final JbiProviderDomain jpd : jpds) {
                this.jbiProviderDomains.put(jpd.id, jpd);
                // TODO create that only after we parsed the Provides and we have the list of concerned provides to pass
                // it to its constructor! (and also so that we did all the desired checks)
                final ProviderDomain pd = getComponent().createConnection(createConnectionName(suDH, jpd), jpd);
                providerDomains.put(jpd.id, pd);
            }
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Error during SU initialisation, undoing everything");
            for (final JbiConsumerDomain jcd : jcds) {
                jbiConsumerDomains.remove(jcd.id);
                consumerDomains.remove(jcd.authName);
            }
            for (final JbiProviderDomain jpd : jpds) {
                assert jpd != null;
                jbiConsumerDomains.remove(jpd.id);
                getComponent().deleteConnection(createConnectionName(suDH, jpd));
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

        try {
            for (final Provides provides : suDH.getDescriptor().getServices().getProvides()) {
                assert provides != null;
                final SuConfigurationParameters extensions = suDH.getConfigurationExtensions(provides);
                assert extensions != null;
                getProviderDomain(provides, extensions).registerProvides(provides);
            }
        } catch (final Exception e) {
            this.logger.warning("Error while registering provides to the provider domains, undoing everything");
        }


    }

    /**
     * TODO this name is not necessary unique...
     */
    private String createConnectionName(final ServiceUnitDataHandler suDH, final JbiProviderDomain jpd) {
        return suDH.getName() + "/" + jpd.id;
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

        for (final Provides provides : suDH.getDescriptor().getServices().getProvides()) {
            assert provides != null;
            final SuConfigurationParameters extensions = suDH.getConfigurationExtensions(provides);
            assert extensions != null;
            try {
                getProviderDomain(provides, extensions).deregisterProvides(provides);
            } catch (final Exception e) {
                exceptions.add(e);
            }
        }

        for (final JbiConsumerDomain jcd : jbiConsumerDomains.values()) {
            jbiConsumerDomains.remove(jcd.id);
            consumerDomains.remove(jcd.authName);
        }

        // TODO prefer to have a private collection for the connections?
        final List<JbiProviderDomain> jpds = JbiGatewayJBIHelper.getProviderDomains(suDH.getDescriptor().getServices());
        for (final JbiProviderDomain jpd : jpds) {
            jbiConsumerDomains.remove(jpd.id);
            try {
                getComponent().deleteConnection(createConnectionName(suDH, jpd));
            } catch (final Exception e) {
                exceptions.add(e);
            }
        }

        if (!exceptions.isEmpty()) {
            final PEtALSCDKException ex = new PEtALSCDKException("Errors while deregistering consumes");
            for (final Throwable e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }

    }

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }

    private ProviderDomain getProviderDomain(final Provides provides, final SuConfigurationParameters extensions)
            throws PEtALSCDKException {
        final String providerDomainId = extensions.get(JbiGatewayJBIHelper.EL_PROVIDES_PROVIDER_DOMAIN);
        if (providerDomainId == null) {
            throw new PEtALSCDKException(String.format("Missing %s in Provides for '%s/%s/%s'",
                    JbiGatewayJBIHelper.EL_PROVIDES_PROVIDER_DOMAIN, provides.getInterfaceName(),
                    provides.getServiceName(), provides.getEndpointName()));
        }
        final JbiProviderDomain jpd = jbiProviderDomains.get(providerDomainId);
        if (jpd == null) {
            throw new PEtALSCDKException(
                    String.format("No provider domain was defined in the SU for '%s'", providerDomainId));
        }
        final ProviderDomain pd = providerDomains.get(providerDomainId);
        // it can't be null, the SUManager should have created it!
        assert pd != null;
        return pd;
    }

    private ConsumerDomain getConsumerDomain(final Consumes consumes, final SuConfigurationParameters extensions)
            throws PEtALSCDKException {
        final String consumerDomainId = extensions.get(JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN.getLocalPart());
        if (consumerDomainId == null) {
            throw new PEtALSCDKException(String.format("Missing %s in Consumes for '%s/%s/%s'",
                    JbiGatewayJBIHelper.EL_CONSUMES_CONSUMER_DOMAIN.getLocalPart(), consumes.getInterfaceName(),
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
