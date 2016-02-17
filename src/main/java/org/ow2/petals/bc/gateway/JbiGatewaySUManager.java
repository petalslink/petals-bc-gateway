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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerAuthenticator;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.component.framework.AbstractComponent;
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
public class JbiGatewaySUManager extends AbstractServiceUnitManager implements ConsumerAuthenticator {

    /**
     * These are the consumer domains declared in the SU jbi.xml.
     * 
     * They are indexed by their id!
     */
    private final Map<String, JbiConsumerDomain> jbiConsumerDomains = new HashMap<>();

    /**
     * These are the provider domains declared in the SU jbi.xml.
     * 
     * They are indexed by their id!
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

    /**
     * The {@link Provides} must be working after deploy!
     */
    @Override
    protected void doDeploy(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final Collection<JbiConsumerDomain> jcds = JbiGatewayJBIHelper
                .getConsumerDomains(suDH.getDescriptor().getServices());
        final Collection<JbiProviderDomain> jpds = JbiGatewayJBIHelper
                .getProviderDomains(suDH.getDescriptor().getServices());

        final Collection<JbiTransportListener> tls = JbiGatewayJBIHelper
                .getTransportListeners(suDH.getDescriptor().getServices());
        if (JbiGatewayJBIHelper.isRestrictedToComponentListeners(
                getComponent().getJbiComponentDescriptor().getComponent()) && !tls.isEmpty()) {
            throw new PEtALSCDKException("Defining transporter listeners in the SU is forbidden by the component");
        }

        final List<Provides> registered = new ArrayList<>();
        try {
            for (final JbiTransportListener jtl : tls) {
                getComponent().addSUTransporterListener(suDH.getName(), jtl);
            }

            for (final JbiConsumerDomain jcd : jcds) {
                final TransportListener tl = getComponent().getTransportListener(suDH.getName(), jcd.getTransport());
                if (tl == null) {
                    throw new PEtALSCDKException(
                            String.format("Missing transporter '%s' needed by consumer domain '%s' in SU '%s'",
                                    jcd.getTransport(), jcd.getId(), suDH.getName()));
                }
                jbiConsumerDomains.put(jcd.getId(), jcd);
                final ConsumerDomain cd = new ConsumerDomain(getComponent().getSender(), jcd);
                consumerDomains.put(jcd.getAuthName(), cd);
            }

            for (final JbiProviderDomain jpd : jpds) {
                this.jbiProviderDomains.put(jpd.getId(), jpd);
                // TODO create that only after we parsed the Provides and we have the list of concerned provides to pass
                // it to its constructor! (and also so that we did all the desired checks)
                final ProviderDomain pd = getComponent().createConnection(createConnectionName(suDH, jpd), jpd);
                providerDomains.put(jpd.getId(), pd);
            }

            for (final Provides provides : suDH.getDescriptor().getServices().getProvides()) {
                assert provides != null;
                getProviderDomain(provides).registerProvides(provides);
                registered.add(provides);
            }
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Error during SU deploy, undoing everything");

            for (final JbiConsumerDomain jcd : jcds) {
                jbiConsumerDomains.remove(jcd.getId());
                consumerDomains.remove(jcd.getAuthName());
            }

            for (final JbiProviderDomain jpd : jpds) {
                jbiProviderDomains.remove(jpd.getId());
                providerDomains.remove(jpd.getId());
                try {
                    getComponent().deleteConnection(createConnectionName(suDH, jpd));
                } catch (final Exception e1) {
                    this.logger.log(Level.WARNING, "Error while removing provider domain", e1);
                }
            }

            for (final JbiTransportListener jtl : tls) {
                try {
                    getComponent().removeSUTransporterListener(suDH.getName(), jtl);
                } catch (final Exception e1) {
                    this.logger.log(Level.WARNING, "Error while removing SU transporter listener", e1);
                }
            }

            throw e;
        }
    }

    /**
     * The {@link Consumes} are only registered on start, not before
     */
    @Override
    protected void doStart(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final List<Consumes> registered = new ArrayList<>();
        try {
            for (final Consumes consumes : suDH.getDescriptor().getServices().getConsumes()) {
                assert consumes != null;
                for (final ConsumerDomain cd : getConsumerDomains(consumes)) {
                    cd.register(consumes);
                }
                registered.add(consumes);
            }
        } catch (final Exception e) {
            this.logger.warning("Error during SU start, undoing everything");
            for (final Consumes consumes : registered) {
                assert consumes != null;
                for (final ConsumerDomain cd : getConsumerDomains(consumes)) {
                    try {
                        cd.deregister(consumes);
                    } catch (final Exception e1) {
                        this.logger.log(Level.WARNING, "Error while deregistering consumes", e1);
                    }
                }
            }
            throw e;
        }
    }

    @Override
    protected void doStop(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final List<Throwable> exceptions = new ArrayList<>();
        for (final Consumes consumes : suDH.getDescriptor().getServices().getConsumes()) {
            assert consumes != null;
            for (final ConsumerDomain cd : getConsumerDomains(consumes)) {
                try {
                    cd.deregister(consumes);
                } catch (final Exception e) {
                    exceptions.add(e);
                }
            }
        }

        if (!exceptions.isEmpty()) {
            final PEtALSCDKException ex = new PEtALSCDKException("Errors during SU stop");
            for (final Throwable e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    @Override
    protected void doUndeploy(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final List<Throwable> exceptions = new ArrayList<>();

        for (final JbiConsumerDomain jcd : jbiConsumerDomains.values()) {
            jbiConsumerDomains.remove(jcd.getId());
            consumerDomains.remove(jcd.getAuthName());
        }

        for (final JbiProviderDomain jpd : jbiProviderDomains.values()) {
            jbiProviderDomains.remove(jpd.getId());
            providerDomains.remove(jpd.getId());
            try {
                // TODO prefer to have a private collection for the connections?
                getComponent().deleteConnection(createConnectionName(suDH, jpd));
            } catch (final Exception e) {
                exceptions.add(e);
            }
        }

        if (!exceptions.isEmpty()) {
            final PEtALSCDKException ex = new PEtALSCDKException("Errors during SU undeploy");
            for (final Throwable e : exceptions) {
                ex.addSuppressed(e);
            }
            throw ex;
        }
    }

    /**
     * TODO move that computation in the component
     */
    private String createConnectionName(final ServiceUnitDataHandler suDH, final JbiProviderDomain jpd) {
        return suDH.getName() + ":" + jpd.getId();
    }

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }

    private ProviderDomain getProviderDomain(final Provides provides) throws PEtALSCDKException {
        final String providerDomainId = JbiGatewayJBIHelper.getProviderDomain(provides);
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

    private Collection<ConsumerDomain> getConsumerDomains(final Consumes consumes) throws PEtALSCDKException {
        final List<ConsumerDomain> cds = new ArrayList<>();
        for (final String consumerDomainId : JbiGatewayJBIHelper.getConsumerDomain(consumes)) {
            final JbiConsumerDomain jcd = jbiConsumerDomains.get(consumerDomainId);
            if (jcd == null) {
                throw new PEtALSCDKException(
                        String.format("No consumer domain was defined in the SU for '%s'", consumerDomainId));
            }
            final ConsumerDomain cd = consumerDomains.get(jcd.getAuthName());
            // it can't be null, the SUManager should have created it!
            assert cd != null;
            cds.add(cd);
        }
        return cds;
    }

    /**
     * TODO move that to component
     */
    @Override
    public @Nullable ConsumerDomain authenticate(final String authName) {
        return consumerDomains.get(authName);
    }
}
