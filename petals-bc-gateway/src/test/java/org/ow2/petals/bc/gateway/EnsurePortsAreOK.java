/**
 * Copyright (c) 2015-2025 Linagora
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
package org.ow2.petals.bc.gateway;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.Callable;

import org.awaitility.Awaitility;
import org.hamcrest.Matchers;
import org.junit.rules.ExternalResource;

public class EnsurePortsAreOK extends ExternalResource {

    private final int port1;

    private final int port2;

    public EnsurePortsAreOK(final int port1, final int port2) {
        super();
        this.port1 = port1;
        this.port2 = port2;
    }

    public EnsurePortsAreOK() {
        this(AbstractEnvironmentTest.TEST_TRANSPORT_PORT, AbstractEnvironmentTest.DEFAULT_PORT);
    }

    @Override
    protected void before() throws Throwable {
        assertAvailable(this.port1);
        assertAvailable(this.port2);
    }

    @Override
    protected void after() {
        assertAvailable(AbstractEnvironmentTest.TEST_TRANSPORT_PORT);
        assertAvailable(AbstractEnvironmentTest.DEFAULT_PORT);
    }

    protected static void assertAvailable(final int port) {
        assertAvailable(port, true);
    }

    protected static void assertNotAvailable(final int port) {
        assertAvailable(port, false);
    }

    protected static void assertAvailable(final int port, final boolean is) {
        Awaitility.await(is ? String.format("Port %d not available", port) : String.format("Port %d available", port))
                .atMost(Duration.ofSeconds(1)).until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        try (final Socket ignored = new Socket("localhost", port)) {
                            return false;
                        } catch (IOException ignored) {
                            return true;
                        }
                    }
                }, Matchers.is(is));
    }
}
