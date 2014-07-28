package com.graphaware.runtime.schedule;


import org.neo4j.graphdb.GraphDatabaseService;

/**
 * {@link TimingStrategy} that causes tasks to be scheduled in regular intervals.
 */
public class FixedDelayTimingStrategy implements TimingStrategy {

    private final long initialDelay;
    private final long delay;

    public static FixedDelayTimingStrategy getInstance() {
        return new FixedDelayTimingStrategy(1000, 200);
    }

    /**
     * Construct a new strategy.
     *
     * @param initialDelay first delay in ms.
     * @param delay        fixed delay in ms.
     */
    private FixedDelayTimingStrategy(long initialDelay, long delay) {
        this.initialDelay = initialDelay;
        this.delay = delay;
    }

    public FixedDelayTimingStrategy withInitialDelay(long initialDelay) {
        return new FixedDelayTimingStrategy(initialDelay, this.delay);
    }

    public FixedDelayTimingStrategy withDelay(long delay) {
        return new FixedDelayTimingStrategy(this.initialDelay, delay);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(GraphDatabaseService database) {
        //no need to do anything
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long nextDelay(long lastTaskDuration) {
        if (lastTaskDuration == NEVER_RUN) {
            return initialDelay;
        }
        return delay;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FixedDelayTimingStrategy strategy = (FixedDelayTimingStrategy) o;

        if (delay != strategy.delay) return false;
        if (initialDelay != strategy.initialDelay) return false;

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int result = (int) (initialDelay ^ (initialDelay >>> 32));
        result = 31 * result + (int) (delay ^ (delay >>> 32));
        return result;
    }
}
