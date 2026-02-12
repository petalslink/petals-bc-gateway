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
package org.ow2.petals.bc.gateway.junit.extensions.impl;

import static org.junit.platform.commons.util.AnnotationUtils.findAnnotatedFields;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;
import static org.junit.platform.commons.util.ReflectionUtils.makeAccessible;

import java.lang.reflect.Field;
import java.util.function.Predicate;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ExceptionUtils;
import org.junit.platform.commons.util.ReflectionUtils;
import org.ow2.petals.bc.gateway.junit.extensions.EnsurePortsAreOKExtension;
import org.ow2.petals.bc.gateway.junit.extensions.api.EnsurePortsAreOK;

/**
 * The implementation of JUnit 5 extension {@link EnsurePortsAreOKExtension} that ensure ports are free.
 * 
 * @see EnsurePortsAreOKExtension
 */
public class EnsurePortsAreOKExtensionImpl implements BeforeAllCallback, BeforeEachCallback {

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        this.injectStaticFields(context, context.getRequiredTestClass());
    }

    @Override
    public void beforeEach(final ExtensionContext context) throws Exception {
        context.getRequiredTestInstances().getAllInstances()
                .forEach(instance -> injectInstanceFields(context, instance));
    }

    private void injectStaticFields(final ExtensionContext context, final Class<?> testClass) {
        this.injectFields(context, null, testClass, ReflectionUtils::isStatic);
    }

    private void injectInstanceFields(final ExtensionContext context, final Object instance) {
        this.injectFields(context, instance, instance.getClass(), ReflectionUtils::isNotStatic);
    }

    private void injectFields(final ExtensionContext context, final Object testInstance, final Class<?> testClass,
            final Predicate<Field> predicate) {

        findAnnotatedFields(testClass, EnsurePortsAreOKExtension.class, predicate).forEach(field -> {
            assertSupportedType("field", field.getType());

            try {
                // Create the component instance of the current component project
                final int[] ports = this.determinePortsForField(field);
                final EnsurePortsAreOK ensurePorts = new EnsurePortsAreOKImpl(ports);

                // Set the field value
                makeAccessible(field).set(testInstance, ensurePorts);

                // Checking if ports are free
                ensurePorts.check();

            } catch (final Throwable t) {
                ExceptionUtils.throwAsUncheckedException(t);
            }
        });
    }

    private void assertSupportedType(String target, Class<?> type) {
        if (type != EnsurePortsAreOK.class) {
            throw new ExtensionConfigurationException("Can only resolve @" + EnsurePortsAreOKExtension.class.getName()
                    + " " + target + " of type " + EnsurePortsAreOK.class.getName() + " but was: " + type.getName());
        }
    }

    private int[] determinePortsForField(final Field field) {
        final EnsurePortsAreOKExtension ensurePorts = findAnnotation(field, EnsurePortsAreOKExtension.class)
                .orElseThrow(() -> new JUnitException(
                        "Field " + field + " must be annotated with @" + EnsurePortsAreOKExtension.class.getName()));
        return ensurePorts.ports();
    }
}
