package sh.repost.client.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe scheduler whose clock and queued work advance only under test control. */
public final class ManualScheduler extends AbstractExecutorService
        implements ScheduledExecutorService {
    private final PriorityQueue<ManualTask<?>> tasks = new PriorityQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private long nowNanos;
    private boolean shutdown;

    /** Creates an empty scheduler at monotonic time zero. */
    public ManualScheduler() { }

    /**
     * Advances the scheduler clock and runs every task due at or before the new time.
     *
     * @param duration nonnegative amount to advance
     * @return number of task invocations run
     */
    public int advanceBy(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        synchronized (this) {
            nowNanos = Math.addExact(nowNanos, duration.toNanos());
        }
        return runDueTasks();
    }

    /**
     * Runs every task currently due without advancing time.
     *
     * @return number of task invocations run
     */
    public int runDueTasks() {
        int count = 0;
        while (true) {
            ManualTask<?> task;
            synchronized (this) {
                discardCancelled();
                task = tasks.peek();
                if (task == null || task.deadlineNanos > nowNanos) {
                    return count;
                }
                tasks.remove();
            }
            task.run();
            count++;
        }
    }

    /**
     * Returns the number of active scheduled tasks.
     *
     * @return pending task count
     */
    public synchronized int getPendingTaskCount() {
        discardCancelled();
        return tasks.size();
    }

    /**
     * Returns the manually controlled monotonic time.
     *
     * @return current scheduler time in nanoseconds
     */
    public synchronized long getCurrentTimeNanos() {
        return nowNanos;
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0L, TimeUnit.NANOSECONDS);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Objects.requireNonNull(command, "command");
        return schedule(java.util.concurrent.Executors.callable(command, null), delay, unit, 0L);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return schedule(Objects.requireNonNull(callable, "callable"), delay, unit, 0L);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command,
            long initialDelay,
            long period,
            TimeUnit unit) {
        if (period <= 0L) {
            throw new IllegalArgumentException("period must be positive");
        }
        TimeUnit checkedUnit = Objects.requireNonNull(unit, "unit");
        return schedule(
                java.util.concurrent.Executors.callable(
                        Objects.requireNonNull(command, "command"), null),
                initialDelay,
                checkedUnit,
                Math.abs(checkedUnit.toNanos(period)));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command,
            long initialDelay,
            long delay,
            TimeUnit unit) {
        if (delay <= 0L) {
            throw new IllegalArgumentException("delay must be positive");
        }
        long delayNanos = Objects.requireNonNull(unit, "unit").toNanos(delay);
        return schedule(
                java.util.concurrent.Executors.callable(
                        Objects.requireNonNull(command, "command"), null),
                initialDelay,
                unit,
                -Math.abs(delayNanos));
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        ArrayList<Runnable> pending = new ArrayList<>(tasks);
        for (ManualTask<?> task : tasks) {
            task.cancel(false);
        }
        tasks.clear();
        return pending;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        discardCancelled();
        return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        return isTerminated();
    }

    private <V> ScheduledFuture<V> schedule(
            Callable<V> callable,
            long delay,
            TimeUnit unit,
            long periodNanos) {
        Objects.requireNonNull(unit, "unit");
        long delayNanos = delay <= 0L ? 0L : unit.toNanos(delay);
        ManualTask<V> task;
        synchronized (this) {
            if (shutdown) {
                throw new RejectedExecutionException("ManualScheduler is shut down");
            }
            task = new ManualTask<>(
                    callable,
                    Math.addExact(nowNanos, delayNanos),
                    periodNanos,
                    sequence.getAndIncrement());
            tasks.add(task);
        }
        return task;
    }

    private synchronized void reschedule(ManualTask<?> task) {
        if (!shutdown && !task.isCancelled()) {
            if (task.periodNanos > 0L) {
                task.deadlineNanos = Math.addExact(task.deadlineNanos, task.periodNanos);
            } else {
                task.deadlineNanos = Math.addExact(nowNanos, -task.periodNanos);
            }
            task.sequenceNumber = sequence.getAndIncrement();
            tasks.add(task);
        }
    }

    private void discardCancelled() {
        tasks.removeIf(ManualTask::isCancelled);
    }

    private final class ManualTask<V> extends FutureTask<V>
            implements RunnableScheduledFuture<V> {
        private long deadlineNanos;
        private final long periodNanos;
        private long sequenceNumber;

        private ManualTask(
                Callable<V> callable,
                long deadlineNanos,
                long periodNanos,
                long sequenceNumber) {
            super(callable);
            this.deadlineNanos = deadlineNanos;
            this.periodNanos = periodNanos;
            this.sequenceNumber = sequenceNumber;
        }

        @Override
        public boolean isPeriodic() {
            return periodNanos != 0L;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long current;
            synchronized (ManualScheduler.this) {
                current = nowNanos;
            }
            return unit.convert(deadlineNanos - current, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            ManualTask<?> that = (ManualTask<?>) other;
            int deadlineOrder = Long.compare(deadlineNanos, that.deadlineNanos);
            return deadlineOrder != 0
                    ? deadlineOrder : Long.compare(sequenceNumber, that.sequenceNumber);
        }

        @Override
        public void run() {
            if (!isPeriodic()) {
                super.run();
            } else if (super.runAndReset()) {
                reschedule(this);
            }
        }
    }
}
