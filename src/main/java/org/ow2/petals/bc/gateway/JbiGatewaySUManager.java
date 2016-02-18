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
import java.util.Map.Entry;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerAuthenticator;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.inbound.TransportListener;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.jbidescriptor.generated.Services;
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
     * These are the actual consumer partner actually connected to us, potentially through multiple {@link Channel}
     * and/or {@link TransportListener}.
     * 
     * They are indexed by their auth-name! TODO which must be unique accross SUs !!!!
     * 
     * The accesses are not very frequent, so no need to introduce a specific performance oriented locking. We just rely
     * on simple synchronization.
     * 
     * TODO replace this by constructing it at deploy and moving it to component
     */
    @SuppressWarnings("null")
    private final Map<String, ConsumerDomain> consumerDomains = Collections
            .synchronizedMap(new HashMap<String, ConsumerDomain>());

    public JbiGatewaySUManager(final JbiGatewayComponent component) {
        super(component);
    }

    /**
     * The {@link Provides} must be working after deploy!
     */
    @Override
    protected void doDeploy(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final Services services = suDH.getDescriptor().getServices();

        final Collection<JbiConsumerDomain> jcds = JbiGatewayJBIHelper.getConsumerDomains(services);
        final Collection<JbiTransportListener> tls = JbiGatewayJBIHelper.getTransportListeners(services);

        if (JbiGatewayJBIHelper.isRestrictedToComponentListeners(
                getComponent().getJbiComponentDescriptor().getComponent()) && !tls.isEmpty()) {
            throw new PEtALSCDKException("Defining transporter listeners in the SU is forbidden by the component");
        }

        final Map<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> pd2provides = JbiGatewayJBIHelper
                .getProvidesPerDomain(services);
        final Map<JbiConsumerDomain, Collection<Consumes>> cd2consumes = JbiGatewayJBIHelper
                .getConsumesPerDomain(services);

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        try {
            for (final JbiTransportListener jtl : tls) {
                assert jtl != null;
                getComponent().addSUTransporterListener(ownerSU, jtl);
            }

            for (final Entry<JbiConsumerDomain, Collection<Consumes>> entry : cd2consumes.entrySet()) {
                final JbiConsumerDomain jcd = entry.getKey();
                // this is there only for the test... TODO why not directly register it now?
                final TransportListener tl = getComponent().getTransportListener(ownerSU, jcd.getTransport());
                if (tl == null) {
                    throw new PEtALSCDKException(
                            String.format("Missing transporter '%s' needed by consumer domain '%s' in SU '%s'",
                                    jcd.getTransport(), jcd.getId(), ownerSU));
                }
                final ConsumerDomain cd = new ConsumerDomain(getComponent().getSender(), jcd, entry.getValue());
                // TODO this could be moved at the transporter level (or ConsumerAuthenticator)
                consumerDomains.put(jcd.getAuthName(), cd);
            }

            for (final Entry<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> entry : pd2provides
                    .entrySet()) {
                final JbiProviderDomain jpd = entry.getKey();
                assert jpd != null;
                final Collection<Pair<Provides, JbiProvidesConfig>> list = entry.getValue();
                assert list != null;
                getComponent().registerProviderDomain(ownerSU, jpd, list);
            }
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Error during SU deploy, undoing everything");

            for (final JbiConsumerDomain jcd : jcds) {
                consumerDomains.remove(jcd.getAuthName());
            }

            for (final JbiProviderDomain jpd : pd2provides.keySet()) {
                assert jpd != null;
                try {
                    getComponent().deregisterProviderDomain(ownerSU, jpd);
                } catch (final Exception e1) {
                    this.logger.log(Level.WARNING, "Error while removing provider domain", e1);
                }
            }

            for (final JbiTransportListener jtl : tls) {
                assert jtl != null;
                try {
                    getComponent().removeSUTransporterListener(ownerSU, jtl);
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

        // TODO we need to start
    }

    @Override
    protected void doStop(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        // TODO we need to stop exchanges to be sent via consumes
    }

    @Override
    protected void doUndeploy(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final List<Throwable> exceptions = new ArrayList<>();

        final Collection<JbiConsumerDomain> jcds = JbiGatewayJBIHelper
                .getConsumerDomains(suDH.getDescriptor().getServices());
        final Collection<JbiProviderDomain> jpds = JbiGatewayJBIHelper
                .getProviderDomains(suDH.getDescriptor().getServices());

        for (final JbiConsumerDomain jcd : jcds) {
            consumerDomains.remove(jcd.getAuthName());
        }

        for (final JbiProviderDomain jpd : jpds) {
            assert jpd != null;
            try {
                getComponent().deregisterProviderDomain(ownerSU, jpd);
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

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }

    /**
     * TODO move that to component
     */
    @Override
    public @Nullable ConsumerDomain authenticate(final String authName) {
        return consumerDomains.get(authName);
    }
}
