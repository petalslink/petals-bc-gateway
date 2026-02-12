/**
 * Copyright (c) 2024-2026 Linagora
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
package org.ow2.petals.bc.gateway.junit.extensions;

import static org.apiguardian.api.API.Status.STABLE;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ow2.petals.bc.gateway.junit.extensions.impl.EnsurePortsAreOKExtensionImpl;

/**
 * <p>
 * The annotation to use in unit tests to ensure ports are free.
 * </p>
 */
@Target({ ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith({ EnsurePortsAreOKExtensionImpl.class })
@API(status = STABLE, since = "1.3.0")
public @interface EnsurePortsAreOKExtension {

    /**
     * <p>
     * Ports to check.
     * </p>
     */
    @API(status = STABLE, since = "1.3.0")
    public int[] ports();
}
