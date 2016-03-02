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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiTransportListener;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.jbidescriptor.generated.Services;
import org.ow2.petals.component.framework.su.AbstractServiceUnitManager;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;

/**
 * There is one instance of this class for the whole component.
 * 
 * @author vnoel
 *
 */
public class JbiGatewaySUManager extends AbstractServiceUnitManager {

    private final ConcurrentMap<String, SUData> suDatas = new ConcurrentHashMap<>();

    public JbiGatewaySUManager(final JbiGatewayComponent component) {
        super(component);
    }

    /**
     * No need for synchronization, only accessed during lifecycles
     */
    private static class SUData {

        private final List<ProviderDomain> providerDomains = new ArrayList<>();

        private final List<ConsumerDomain> consumerDomains = new ArrayList<>();

        private final List<JbiTransportListener> listeners = new ArrayList<>();
    }

    /**
     * The {@link Provides} must be working after deploy!
     */
    @Override
    protected void doDeploy(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final Services services = suDH.getDescriptor().getServices();

        final Collection<JbiTransportListener> jtls = JbiGatewayJBIHelper.getTransportListeners(services);

        if (JbiGatewayJBIHelper.isRestrictedToComponentListeners(
                getComponent().getJbiComponentDescriptor().getComponent()) && !jtls.isEmpty()) {
            throw new PEtALSCDKException("Defining transporter listeners in the SU is forbidden by the component");
        }

        final Map<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> pd2provides = JbiGatewayJBIHelper
                .getProvidesPerDomain(services);
        final Map<JbiConsumerDomain, Collection<Consumes>> cd2consumes = JbiGatewayJBIHelper
                .getConsumesPerDomain(services);

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final SUData data = new SUData();
        this.suDatas.put(ownerSU, data);

        try {
            for (final JbiTransportListener jtl : jtls) {
                assert jtl != null;
                getComponent().registerTransportListener(ownerSU, jtl);
                data.listeners.add(jtl);
            }

            for (final Entry<JbiConsumerDomain, Collection<Consumes>> entry : cd2consumes.entrySet()) {
                final JbiConsumerDomain jcd = entry.getKey();
                assert jcd != null;
                final Collection<Consumes> consumes = entry.getValue();
                assert consumes != null;
                final ConsumerDomain cd = getComponent().createConsumerDomain(ownerSU, jcd, consumes);
                data.consumerDomains.add(cd);
            }

            for (final Entry<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> entry : pd2provides
                    .entrySet()) {
                final JbiProviderDomain jpd = entry.getKey();
                assert jpd != null;
                final Collection<Pair<Provides, JbiProvidesConfig>> provides = entry.getValue();
                assert provides != null;
                final ProviderDomain pd = getComponent().registerProviderDomain(ownerSU, jpd, provides);
                data.providerDomains.add(pd);
            }
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Error during SU deploy, undoing everything");

            for (final ProviderDomain pd : data.providerDomains) {
                assert pd != null;
                try {
                    getComponent().deregisterProviderDomain(pd);
                } catch (final Exception e1) {
                    this.logger.log(Level.WARNING, "Error while removing provider domain", e1);
                }
            }

            for (final JbiTransportListener jtl : data.listeners) {
                assert jtl != null;
                try {
                    getComponent().deregisterTransportListener(ownerSU, jtl);
                } catch (final Exception e1) {
                    this.logger.log(Level.WARNING, "Error while removing SU transporter listener", e1);
                }
            }

            suDatas.remove(ownerSU);

            throw e;
        }
    }

    @Override
    protected void doInit(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final SUData data = suDatas.get(suDH.getName());

        final List<ProviderDomain> initialised = new ArrayList<>();
        try {
            for (final ProviderDomain pd : data.providerDomains) {
                pd.init();
                initialised.add(pd);
            }
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Error during SU init, undoing everything");

            for (final ProviderDomain pd : initialised) {
                try {
                    pd.shutdown();
                } catch (final Exception ex) {
                    this.logger.log(Level.WARNING, "Error while shutdowning provider domain", ex);
                }
            }

            throw e;
        }
    }

    @Override
    protected void doStart(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final SUData data = suDatas.get(ownerSU);

        final List<ConsumerDomain> opened = new ArrayList<>();
        try {
            for (final ConsumerDomain cd : data.consumerDomains) {
                assert cd != null;
                cd.open();
                opened.add(cd);
            }
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Error during SU init, undoing everything");

            for (final ConsumerDomain cd : opened) {
                assert cd != null;
                try {
                    cd.close();
                } catch (final Exception e1) {
                    this.logger.log(Level.WARNING, "Error while stopping consumer domain", e1);
                }
            }

            throw e;
        }
    }

    @Override
    protected void doStop(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final SUData data = suDatas.get(ownerSU);

        final List<Throwable> exceptions = new ArrayList<>();

        for (final ConsumerDomain cd : data.consumerDomains) {
            assert cd != null;
            try {
                cd.close();
            } catch (final Exception e) {
                exceptions.add(e);
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
    protected void doShutdown(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final SUData data = suDatas.get(suDH.getName());

        final List<Throwable> exceptions = new ArrayList<>();

        for (final ProviderDomain pd : data.providerDomains) {
            try {
                pd.shutdown();
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
    protected void doUndeploy(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final SUData data = suDatas.get(ownerSU);

        final List<Throwable> exceptions = new ArrayList<>();

        for (final ProviderDomain pd : data.providerDomains) {
            assert pd != null;
            try {
                getComponent().deregisterProviderDomain(pd);
            } catch (final Exception e) {
                exceptions.add(e);
            }
        }

        for (final JbiTransportListener jtl : data.listeners) {
            assert jtl != null;
            try {
                getComponent().deregisterTransportListener(ownerSU, jtl);
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
}
