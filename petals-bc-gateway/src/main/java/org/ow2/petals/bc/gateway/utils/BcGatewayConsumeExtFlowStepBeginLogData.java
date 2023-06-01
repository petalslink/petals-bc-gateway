/**
 * Copyright (c) 2016-2022 Linagora
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
package org.ow2.petals.bc.gateway.utils;

import org.ow2.petals.commons.log.FlowAttributes;
import org.ow2.petals.component.framework.logger.ConsumeExtFlowStepBeginLogData;

public class BcGatewayConsumeExtFlowStepBeginLogData extends ConsumeExtFlowStepBeginLogData {

    private static final long serialVersionUID = 5702156947022340494L;

    public static final String CONSUMER_KEY = "consumer-domain";

    /**
     * Name of the MONIT trace attribute containing the instance identifier of the flow on the other domain initiating
     * the request.
     */
    public static final String CORRELATED_FLOW_INSTANCE_ID_KEY = "correlatedFlowInstanceId";

    /**
     * Name of the MONIT trace attribute containing the step identifier of the flow on the other domain initiating the
     * request.
     */
    public static final String CORRELATED_FLOW_STEP_ID_KEY = "correlatedFlowStepId";

    public BcGatewayConsumeExtFlowStepBeginLogData(final FlowAttributes fa, final FlowAttributes correlated,
            final String consumer) {
        super(fa.getFlowInstanceId(), fa.getFlowStepId());
        putData(CORRELATED_FLOW_INSTANCE_ID_KEY, correlated.getFlowInstanceId());
        putData(CORRELATED_FLOW_STEP_ID_KEY, correlated.getFlowStepId());
        putData(CONSUMER_KEY, consumer);
    }

}
