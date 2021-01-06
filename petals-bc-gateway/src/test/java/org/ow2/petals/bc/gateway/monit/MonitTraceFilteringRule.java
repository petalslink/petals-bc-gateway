/**
 * Copyright (c) 2019-2021 Linagora
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
package org.ow2.petals.bc.gateway.monit;

/**
 * <p>
 * Rule configuration for a service provider implementing an orchestration (ie. invoking service consumer(s)).
 * <p>
 * 
 * @author Christophe DENEUX - Linagora
 *
 */
public class MonitTraceFilteringRule {

    /**
     * Rule attributes linked to the consumer domain
     */

    protected String compConsDomEnableFlowTracing;

    protected String compConsDomEnableFlowTracingPropagation;

    protected String isConsDomProvMonitTraceLogged;

    /**
     * Rule attributes linked to the provider domain
     */

    protected String compProvDomEnableFlowTracing;

    protected String compProvDomEnableFlowTracingPropagation;

    protected String consEnableFlowTracing;

    protected String consEnableFlowTracingPropagation;

    protected String isProvDomConsMonitTraceLogged;

    protected String isProvMonitTraceLogged;

    /**
     * Rule attributes linked to the message exchange
     */

    protected String msgEnableFlowTracing;

    protected String expectedFlowTracingActivationState;

    public MonitTraceFilteringRule() {
        // Default constructor
    }

    public void setCompConsDomEnableFlowTracing(final String compConsDomEnableFlowTracing) {
        this.compConsDomEnableFlowTracing = compConsDomEnableFlowTracing;
    }

    public void setCompConsDomEnableFlowTracingPropagation(final String compConsDomEnableFlowTracingPropagation) {
        this.compConsDomEnableFlowTracingPropagation = compConsDomEnableFlowTracingPropagation;
    }

    public void setCompProvDomEnableFlowTracing(final String compProvDomEnableFlowTracing) {
        this.compProvDomEnableFlowTracing = compProvDomEnableFlowTracing;
    }

    public void setCompProvDomEnableFlowTracingPropagation(final String compProvDomEnableFlowTracingPropagation) {
        this.compProvDomEnableFlowTracingPropagation = compProvDomEnableFlowTracingPropagation;
    }

    public void setConsEnableFlowTracing(final String consEnableFlowTracing) {
        this.consEnableFlowTracing = consEnableFlowTracing;
    }

    public void setConsEnableFlowTracingPropagation(final String consEnableFlowTracingPropagation) {
        this.consEnableFlowTracingPropagation = consEnableFlowTracingPropagation;
    }

    public void setMsgEnableFlowTracing(final String msgEnableFlowTracing) {
        this.msgEnableFlowTracing = msgEnableFlowTracing;
    }

    public void setExpectedFlowTracingActivationState(final String expectedFlowTracingActivationState) {
        this.expectedFlowTracingActivationState = expectedFlowTracingActivationState;
    }

    public void setIsProvMonitTraceLogged(final String isProvMonitTraceLogged) {
        this.isProvMonitTraceLogged = isProvMonitTraceLogged;
    }

    public void setIsConsDomProvMonitTraceLogged(final String isConsDomProvMonitTraceLogged) {
        this.isConsDomProvMonitTraceLogged = isConsDomProvMonitTraceLogged;
    }

    public void setIsProvDomConsMonitTraceLogged(final String isProvDomConsMonitTraceLogged) {
        this.isProvDomConsMonitTraceLogged = isProvDomConsMonitTraceLogged;
    }

}
