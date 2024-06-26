/*
 * Copyright (c) 2015, 2024 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.spi;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.jersey.innate.VirtualThreadUtil;
import org.glassfish.jersey.internal.LocalizationMessages;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;
import org.glassfish.jersey.internal.util.ExtendedLogger;
import org.glassfish.jersey.internal.util.collection.LazyValue;
import org.glassfish.jersey.internal.util.collection.Value;
import org.glassfish.jersey.internal.util.collection.Values;
import org.glassfish.jersey.process.JerseyProcessingUncaughtExceptionHandler;

import javax.ws.rs.core.Configuration;

/**
 * Abstract thread pool executor provider.
 * <p>
 * This class provides a skeleton implementation for provisioning and basic lifecycle management of thread pool executors.
 * Every instance of the concrete implementation of this provider class creates at most one shared and lazily initialized
 * thread pool executor instance, which can be retrieved by invoking the {@link #getExecutor()} method. This provider also makes
 * sure that the provisioned thread pool executor instance is properly shut down when the managing provider instance is
 * {@link #close() closed} (in case it has not been already shut down).
 * </p>
 * <p>
 * At minimum, concrete subclasses of this provider are expected to implement the {@link #createExecutor} method that is used
 * as a thread pool instance factory. The method is invoked lazily, with the first call to the {@code getExecutor()} method.
 * The result returned from the {@code createExecutor()} method is cached internally and is used as a return value for subsequent
 * calls to the {@code getExecutor()} method. This means, that {@code createExecutor()} method is guaranteed to be invoked
 * <em>at most once</em> during the lifetime of any particular provider instance.
 * </p>
 *
 * @author Marek Potociar
 * @since 2.18
 */
public abstract class AbstractThreadPoolProvider<E extends ThreadPoolExecutor> implements AutoCloseable {

    private static final ExtendedLogger LOGGER = new ExtendedLogger(
            Logger.getLogger(AbstractThreadPoolProvider.class.getName()), Level.FINEST);

    /**
     * Default thread pool executor termination timeout in milliseconds.
     */
    public static final int DEFAULT_TERMINATION_TIMEOUT = 5000;

    private final String name;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final LazyValue<E> lazyExecutorServiceProvider;
    /**
     * Inheritance constructor.
     *
     * @param name name of the provided thread pool executor. Will be used in the names of threads created & used by the
     *             provided thread pool executor.
     */
    protected AbstractThreadPoolProvider(final String name) {
        this(name, null);
    }

    /**
     * Inheritance constructor.
     *
     * @param name name of the provided thread pool executor. Will be used in the names of threads created & used by the
     *             provided thread pool executor.
     * @param configuration {@link Configuration} properties.
     */
    protected AbstractThreadPoolProvider(final String name, Configuration configuration) {
        this.name = name;
        lazyExecutorServiceProvider = Values.lazy((Value<E>) () ->
                createExecutor(getCorePoolSize(), createThreadFactory(configuration), getRejectedExecutionHandler()));
    }

    /**
     * Get the thread pool executor.
     * <p/>
     * The first invocation of this method will invoke the overridden {@link #createExecutor} method to retrieve the
     * provided thread pool executor instance. The created thread pool executor instance is then cached and will be returned upon
     * subsequent calls to this method.
     *
     * @return provided thread pool executor.
     * @throws java.lang.IllegalStateException in case the provider has been {@link #close() closed} already.
     * @see #createExecutor
     * @see #close()
     * @see #isClosed()
     */
    protected final E getExecutor() {
        if (isClosed()) {
            throw new IllegalStateException(LocalizationMessages.THREAD_POOL_EXECUTOR_PROVIDER_CLOSED());
        }
        return lazyExecutorServiceProvider.get();
    }

    /**
     * Create a new instance of the thread pool executor that should be provided by the {@link #getExecutor()} method.
     * <p>
     * Concrete implementations of this class must override this method and implement the logic that creates the executor
     * service to be provided. The returned thread pool executor will be shut down when this provider instance is
     * {@link #close() closed}.
     * </p>
     * <p>
     * This method is invoked at most once, during the first call to the {@code getExecutor()} method.
     * </p>
     *
     * @param corePoolSize  number of core threads the provisioned thread pool executor should provide.
     * @param threadFactory thread factory to be used by the provisioned thread pool executor when creating new threads.
     * @param handler       handler for tasks that cannot be executed by the provisioned thread pool executor (e.g. due to a
     *                      shutdown).
     * @return new instance of the provided thread pool executor.
     * @see #getExecutor()
     * @see #close()
     * @see #getCorePoolSize()
     * @see #getBackingThreadFactory()
     * @see #getRejectedExecutionHandler()
     */
    protected abstract E createExecutor(
            final int corePoolSize, final ThreadFactory threadFactory, final RejectedExecutionHandler handler);

    /**
     * Get the provisioned thread pool executor termination time out (in milliseconds).
     * <p>
     * The method is used during the thread pool executor shutdown sequence to determine the shutdown timeout, when this provider
     * instance is {@link #close() closed}.
     * In case the thread pool executor shutdown is interrupted or the timeout expires, the provisioned thread pool executor is
     * {@link java.util.concurrent.ExecutorService#shutdownNow() shutdown forcefully}.
     * </p>
     * <p>
     * The method can be overridden to customize the thread pool executor termination time out. If not customized, the
     * method defaults to {@value #DEFAULT_TERMINATION_TIMEOUT} ms.
     * </p>
     *
     * @return provisioned thread pool executor termination time out (in milliseconds).
     * @see #close()
     * @see java.util.concurrent.ExecutorService#awaitTermination(long, java.util.concurrent.TimeUnit)
     */
    protected int getTerminationTimeout() {
        return DEFAULT_TERMINATION_TIMEOUT;
    }

    /**
     * Get the number of the core threads of the the provisioned thread pool executor.
     * <p>
     * The value from this method is passed as one of the input parameters in a call to the {@link #createExecutor} method.
     * </p>
     * <p>
     * The method can be overridden to customize the number of core threads of the provisioned thread pool executor.
     * If not customized, the method defaults to the number of {@link Runtime#availableProcessors() available processors}
     * in the system.
     * </p>
     *
     * @return number of core threads in the provisioned thread pool executor.
     * @see #createExecutor
     */
    protected int getCorePoolSize() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Get the handler for tasks that could not be executed by the provisioned thread pool executor.
     * <p>
     * The value from this method is passed as one of the input parameters in a call to the {@link #createExecutor} method.
     * </p>
     * <p>
     * The method can be overridden to customize the rejected task handler used by the provisioned thread pool executor.
     * If not customized, the method provides a basic default NO-OP implementation.
     * </p>
     *
     * @return handler for tasks that could not be executed by the provisioned thread pool executor.
     * @see #createExecutor
     */
    protected RejectedExecutionHandler getRejectedExecutionHandler() {
        return (r, executor) -> {
            // TODO: implement the rejected execution handler method.
        };
    }

    /**
     * Get a backing thread factory that should be used as a delegate for creating the new threads for the provisioned executor
     * service.
     * <p>
     * The value from this method is used as a backing {@link ThreadFactory} for an internally constructed thread factory
     * instance
     * that is passed as one of the input parameters in a call to the {@link #createExecutor} method.
     * When not {@code null}, the new threads will be created by invoking the {@link ThreadFactory#newThread(Runnable)} on
     * this backing {@code ThreadFactory}.
     * </p>
     * <p>
     * The method can be overridden to customize the backing thread factory for the provisioned thread pool executor.
     * If not customized, the method returns {@code null} by default.
     * </p>
     *
     * @return backing thread factory for the provisioned thread pool executor. May return {@code null}, in which case no backing
     * thread factory will be used.
     * @see #createExecutor
     */
    protected ThreadFactory getBackingThreadFactory() {
        return null;
    }

    private ThreadFactory createThreadFactory(Configuration configuration) {
        final ThreadFactoryBuilder factoryBuilder = new ThreadFactoryBuilder()
                .setNameFormat(name + "-%d")
                .setThreadFactory(VirtualThreadUtil.withConfig(configuration).getThreadFactory())
                .setUncaughtExceptionHandler(new JerseyProcessingUncaughtExceptionHandler());

        final ThreadFactory backingThreadFactory = getBackingThreadFactory();
        if (backingThreadFactory != null) {
            factoryBuilder.setThreadFactory(backingThreadFactory);
        }

        return factoryBuilder.build();
    }

    /**
     * Check if this thread pool executor provider has been {@link #close() closed}.
     *
     * @return {@code true} if this provider has been closed, {@code false} otherwise.
     * @see #close()
     */
    public final boolean isClosed() {
        return closed.get();
    }

    /**
     * Close event handler, that invoked during the {@link #close()} operation.
     * <p>
     * Concrete implementations of this provider class may override this method to perform any additional resource clean-up.
     * Default implementation is a NO-OP.
     * </p>
     *
     * @see #close()
     */
    protected void onClose() {
        // NO-OP default implementation.
    }

    /**
     * Close this thread pool executor provider.
     * <p>
     * Once the provider is closed, it will stop providing the thread pool executor and subsequent invocations to
     * {@link #getExecutor()} method will result in an {@link java.lang.IllegalStateException} being thrown. The current
     * status of the provider can be checked via {@link #isClosed()} method.
     * </p>
     * <p>
     * Upon invocation, the following tasks are performed:
     * <ul>
     * <li>The thread pool executor instance provisioning via {@code getExecutor()} method is stopped.</li>
     * <li>The {@link #onClose()} event handler is invoked.</li>
     * <li>The thread pool executor, if previously {@link #createExecutor created} and {@link #getExecutor() provisioned},
     * is shut down.</li>
     * </ul>
     * The actual thread pool executor shutdown is performed as follows:
     * </p>
     * <p>
     * First, a {@link java.util.concurrent.ExecutorService#shutdown() graceful shutdown} is attempted. The value returned from
     * a call to {@link #getTerminationTimeout()} method is used to determine the graceful shutdown timeout period.
     * </p>
     * <p>
     * In case the thread pool executor graceful shutdown is interrupted or the timeout expires, the provisioned thread pool
     * executor is
     * {@link java.util.concurrent.ExecutorService#shutdownNow() shutdown forcefully}. All tasks that have never commenced
     * execution are then {@link java.util.concurrent.Future#cancel cancelled interruptingly}, if possible.
     * </p>
     *
     * @see #isClosed()
     * @see #onClose()
     * @see #getExecutor()
     * @see #getTerminationTimeout()
     */
    public final void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            onClose();
        } finally {
            if (lazyExecutorServiceProvider.isInitialized()) {
                AccessController.doPrivileged(shutdownExecutor(
                        name,
                        lazyExecutorServiceProvider.get(),
                        getTerminationTimeout(),
                        TimeUnit.MILLISECONDS));
            }
        }
    }

    /**
     * Create a {@link java.security.PrivilegedAction} that contains logic for a proper shut-down sequence of an executor
     * service.
     *
     * @param executorName        executor service identification.
     * @param executorService     executor service instance.
     * @param terminationTimeout  orderly shut-down termination time-out value (maximum time to wait for the termination).
     * @param terminationTimeUnit orderly shut-down termination time-out time unit.
     * @return an executor shut-down logic wrapped in a privileged action.
     */
    private static PrivilegedAction<?> shutdownExecutor(
            final String executorName,
            final ExecutorService executorService,
            final int terminationTimeout,
            final TimeUnit terminationTimeUnit) {

        return (PrivilegedAction<Void>) () -> {
            if (!executorService.isShutdown()) {
                executorService.shutdown();
            }
            if (executorService.isTerminated()) {
                return null;
            }

            boolean terminated = false;
            boolean interrupted = false;
            try {
                terminated = executorService.awaitTermination(terminationTimeout, terminationTimeUnit);
            } catch (InterruptedException e) {
                if (LOGGER.isDebugLoggable()) {
                    LOGGER.log(LOGGER.getDebugLevel(),
                            "Interrupted while waiting for thread pool executor " + executorName + " to shutdown.", e);
                }
                interrupted = true;
            }

            try {
                if (!terminated) {
                    final List<Runnable> cancelledTasks = executorService.shutdownNow();
                    for (Runnable cancelledTask : cancelledTasks) {
                        if (cancelledTask instanceof Future) {
                            ((Future) cancelledTask).cancel(true);
                        }
                    }

                    if (LOGGER.isDebugLoggable()) {
                        LOGGER.debugLog("Thread pool executor {0} forced-shut down. List of cancelled tasks: {1}",
                                executorName, cancelledTasks);
                    }
                }
            } finally {
                if (interrupted) {
                    // restoring the interrupt flag
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        };
    }
}
