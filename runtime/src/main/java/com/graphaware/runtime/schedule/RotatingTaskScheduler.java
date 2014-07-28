package com.graphaware.runtime.schedule;

import com.graphaware.common.util.Pair;
import com.graphaware.runtime.metadata.DefaultTimerDrivenModuleMetadata;
import com.graphaware.runtime.metadata.ModuleMetadataRepository;
import com.graphaware.runtime.metadata.TimerDrivenModuleContext;
import com.graphaware.runtime.module.TimerDrivenModule;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.graphaware.runtime.schedule.TimingStrategy.*;

/**
 * {@link TaskScheduler} that delegates to the registered {@link TimerDrivenModule}s in round-robin fashion, in the order
 * in which the modules were registered. All work performed by this implementation is done by a single thread.
 */
public class RotatingTaskScheduler implements TaskScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(RotatingTaskScheduler.class);

    private final GraphDatabaseService database;
    private final ModuleMetadataRepository repository;
    private final TimingStrategy timingStrategy;

    //these two should be made concurrent if we use more than 1 thread for the background work
    private final Map<TimerDrivenModule, TimerDrivenModuleContext> moduleContexts = new LinkedHashMap<>();
    private Iterator<Map.Entry<TimerDrivenModule, TimerDrivenModuleContext>> moduleContextIterator;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    /**
     * Construct a new task scheduler.
     *
     * @param database       against which the modules are running.
     * @param repository     for persisting metadata.
     * @param timingStrategy strategy for timing the work delegation.
     */
    public RotatingTaskScheduler(GraphDatabaseService database, ModuleMetadataRepository repository, TimingStrategy timingStrategy) {
        this.database = database;
        this.repository = repository;
        this.timingStrategy = timingStrategy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C extends TimerDrivenModuleContext, T extends TimerDrivenModule<C>> void registerModuleAndContext(T module, C context) {
        if (moduleContextIterator != null) {
            throw new IllegalStateException("Task scheduler can not accept modules after it has been started. This is a bug.");
        }

        LOG.info("Registering module " + module.getId() + " and its context with the task scheduler.");
        moduleContexts.put(module, context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        if (moduleContexts.isEmpty()) {
            LOG.info("There are no timer-driven runtime modules. Not scheduling any tasks.");
            return;
        }

        LOG.info("There are " + moduleContexts.size() + " timer-driven runtime modules. Scheduling the first task...");

        timingStrategy.initialize(database);

        scheduleNextTask(NEVER_RUN);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        LOG.info("Terminating task scheduler...");
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.warn("Did not manage to finish all tasks in 5 seconds.");
        }
        LOG.info("Task scheduler terminated successfully.");
    }

    /**
     * Schedule next task.
     *
     * @param lastTaskDuration duration of the last task in millis, negative if unknown.
     */
    private void scheduleNextTask(long lastTaskDuration) {
        long nextDelayMillis = timingStrategy.nextDelay(lastTaskDuration);
        LOG.debug("Scheduling next task with a delay of {} ms.", nextDelayMillis);
        worker.schedule(nextTask(), nextDelayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Create next task wrapped in a {@link Runnable}. The {@link Runnable} schedules the next task when finished.
     *
     * @return next task to be run wrapped in a {@link Runnable}.
     */
    protected Runnable nextTask() {
        return new Runnable() {
            @Override
            public void run() {
                long totalTime = UNKNOWN;
                try {
                    LOG.debug("Running a scheduled task...");
                    long startTime = System.currentTimeMillis();

                    runNextTask();

                    totalTime = (System.currentTimeMillis() - startTime);
                    LOG.debug("Successfully completed scheduled task in " + totalTime + " ms");
                } catch (Exception e) {
                    LOG.warn("Task execution threw an exception: " + e.getMessage(), e);
                } finally {
                    scheduleNextTask(totalTime);
                }
            }
        };
    }

    /**
     * Run the next task.
     *
     * @param <C> type of the context passed into the module below.
     * @param <T> module type of the module that will be delegated to.
     */
    private <C extends TimerDrivenModuleContext, T extends TimerDrivenModule<C>> void runNextTask() {
        if (!database.isAvailable(0)) {
            LOG.warn("Database not available, probably shutting down...");
            return;
        }

        Pair<T, C> moduleAndContext = findNextModuleAndContext();

        if (moduleAndContext == null) {
            return; //no module withes to run
        }

        T module = moduleAndContext.first();
        C context = moduleAndContext.second();

        try (Transaction tx = database.beginTx()) {
            C newContext = module.doSomeWork(context, database);
            repository.persistModuleMetadata(module, new DefaultTimerDrivenModuleMetadata(newContext));
            moduleContexts.put(module, newContext);
            tx.success();
        }
    }

    /**
     * Find the next module that is ready to be delegated to, and its context.
     *
     * @param <C> context type.
     * @param <T> module type.
     * @return module & context.
     */
    private <C extends TimerDrivenModuleContext, T extends TimerDrivenModule<C>> Pair<T, C> findNextModuleAndContext() {
        int totalModules = moduleContexts.size();
        long now = System.currentTimeMillis();

        for (int i = 0; i < totalModules; i++) {
            Pair<T, C> candidate = nextModuleAndContext();
            if (candidate.second() == null || candidate.second().earliestNextCall() <= now) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Find the next module whose turn it would be and its context.
     *
     * @param <C> context type.
     * @param <T> module type.
     * @return module & context.
     */
    private <C extends TimerDrivenModuleContext, T extends TimerDrivenModule<C>> Pair<T, C> nextModuleAndContext() {
        if (moduleContextIterator == null || !moduleContextIterator.hasNext()) {
            moduleContextIterator = moduleContexts.entrySet().iterator();
        }

        Map.Entry<TimerDrivenModule, TimerDrivenModuleContext> entry = moduleContextIterator.next();

        //noinspection unchecked
        return new Pair<>((T) entry.getKey(), (C) entry.getValue());
    }
}
