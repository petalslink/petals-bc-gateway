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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.ow2.petals.bc.gateway.inbound.ConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiConsumerDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProviderDomain;
import org.ow2.petals.bc.gateway.jbidescriptor.generated.JbiProvidesConfig;
import org.ow2.petals.bc.gateway.outbound.ProviderDomain;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper;
import org.ow2.petals.bc.gateway.utils.JbiGatewayJBIHelper.Pair;
import org.ow2.petals.component.framework.AbstractComponent;
import org.ow2.petals.component.framework.api.exception.PEtALSCDKException;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
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
        // let's disable endpoint activation, we will do it ourselves!
        super(component, false);
    }

    /**
     * No need for synchronization, only accessed during lifecycles
     */
    private static class SUData {

        private final Map<String, ProviderDomain> providerDomains = new HashMap<>();

        private final Map<String, ConsumerDomain> consumerDomains = new HashMap<>();
    }

    public Collection<ProviderDomain> getProviderDomains() {
        final List<ProviderDomain> pds = new ArrayList<>();
        for (final SUData data : suDatas.values()) {
            pds.addAll(data.providerDomains.values());
        }
        return pds;
    }

    public Collection<ConsumerDomain> getConsumerDomains() {
        final List<ConsumerDomain> cds = new ArrayList<>();
        for (final SUData data : suDatas.values()) {
            cds.addAll(data.consumerDomains.values());
        }
        return cds;
    }

    /**
     * The {@link Provides} must be working after deploy!
     */
    @Override
    protected void doDeploy(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final Map<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> pd2provides = this.getJPDs(suDH);
        final Map<JbiConsumerDomain, Collection<Consumes>> cd2consumes = this.getJCDs(suDH);

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final SUData data = new SUData();
        this.suDatas.put(ownerSU, data);

        for (final Entry<JbiConsumerDomain, Collection<Consumes>> entry : cd2consumes.entrySet()) {
            final JbiConsumerDomain jcd = entry.getKey();
            assert jcd != null;
            final Collection<Consumes> consumes = entry.getValue();
            assert consumes != null;
            final ConsumerDomain cd = getComponent().createConsumerDomain(suDH, jcd, consumes);
            data.consumerDomains.put(jcd.getId(), cd);
        }

        for (final Entry<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> entry : pd2provides
                .entrySet()) {
            final JbiProviderDomain jpd = entry.getKey();
            assert jpd != null;
            final Collection<Pair<Provides, JbiProvidesConfig>> provides = entry.getValue();
            assert provides != null;
            final ProviderDomain pd = getComponent().createProviderDomain(suDH, jpd, provides);
            data.providerDomains.put(jpd.getId(), pd);
        }
    }

    @Override
    protected void doInit(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final SUData data = suDatas.get(suDH.getName());

        for (final ProviderDomain pd : data.providerDomains.values()) {
            pd.register();
        }
    }

    @Override
    protected void doStart(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final SUData data = suDatas.get(ownerSU);

        for (final ConsumerDomain cd : data.consumerDomains.values()) {
            cd.open();
        }
    }

    @Override
    protected void doStop(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final SUData data = suDatas.get(ownerSU);

        final PEtALSCDKException ex = new PEtALSCDKException("Errors during SU stop");

        for (final ConsumerDomain cd : data.consumerDomains.values()) {
            try {
                cd.close();
            } catch (final Exception e) {
                ex.addSuppressed(e);
            }
        }

        ex.throwIfNeeded();
    }

    @Override
    protected void doShutdown(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final SUData data = suDatas.get(suDH.getName());

        final PEtALSCDKException ex = new PEtALSCDKException("Errors during SU shutdown");

        for (final ProviderDomain pd : data.providerDomains.values()) {
            try {
                // connection will stay open until undeploy so that previous exchanges are finished
                pd.deregister();
            } catch (final Exception e) {
                ex.addSuppressed(e);
            }
        }

        ex.throwIfNeeded();
    }

    @Override
    protected void doUndeploy(final @Nullable ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        assert suDH != null;

        final String ownerSU = suDH.getName();
        assert ownerSU != null;

        final SUData data = suDatas.remove(ownerSU);

        if (data == null) {
            // can happen in case deploy failed early
            return;
        }

        final PEtALSCDKException ex = new PEtALSCDKException("Errors during SU undeploy");

        for (final ProviderDomain pd : data.providerDomains.values()) {
            try {
                pd.disconnect();
            } catch (final Exception e) {
                ex.addSuppressed(e);
            }
        }

        for (final ConsumerDomain cd : data.consumerDomains.values()) {
            try {
                cd.disconnect();
            } catch (final Exception e) {
                ex.addSuppressed(e);
            }
        }

        ex.throwIfNeeded();
    }

    @Override
    public void onPlaceHolderValuesReloaded() {
        for (final ServiceUnitDataHandler suDH : getServiceUnitDataHandlers()) {
            assert suDH != null;
            try {
                // let's first gather data to ensure it is valid
                final Set<JbiProviderDomain> jpds = getJPDs(suDH).keySet();
                final Set<JbiConsumerDomain> jcds = getJCDs(suDH).keySet();

                final SUData data = suDatas.get(suDH.getName());

                for (final JbiProviderDomain jpd : jpds) {
                    data.providerDomains.get(jpd.getId()).reload(jpd);
                }

                for (final JbiConsumerDomain jcd : jcds) {
                    data.consumerDomains.get(jcd.getId()).reload(jcd);
                }
            } catch (final PEtALSCDKException e) {
                logger.log(Level.WARNING, "Can't reload placeholders for SU " + suDH.getName(), e);
            }
        }
    }

    private Map<JbiConsumerDomain, Collection<Consumes>> getJCDs(final ServiceUnitDataHandler suDH)
            throws PEtALSCDKException {
        return JbiGatewayJBIHelper.getConsumesPerDomain(suDH, getComponent().getPlaceHolders(), logger);
    }

    private Map<JbiProviderDomain, Collection<Pair<Provides, JbiProvidesConfig>>> getJPDs(
            final ServiceUnitDataHandler suDH) throws PEtALSCDKException {
        return JbiGatewayJBIHelper.getProvidesPerDomain(suDH, getComponent().getPlaceHolders(), logger);
    }

    @Override
    public JbiGatewayComponent getComponent() {
        final AbstractComponent component = super.getComponent();
        assert component != null;
        return (JbiGatewayComponent) component;
    }
}
