package sh.repost.client.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Thread-safe executor whose queued work runs only under explicit test control. */
public final class ManualExecutor extends AbstractExecutorService {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    /** Creates an empty manual executor. */
    public ManualExecutor() { }

    @Override
    public synchronized void execute(Runnable command) {
        if (shutdown) {
            throw new RejectedExecutionException("ManualExecutor is shut down");
        }
        tasks.addLast(java.util.Objects.requireNonNull(command, "command"));
    }

    /**
     * Runs the next queued task on the calling thread.
     *
     * @return whether a task was available
     */
    public boolean runNext() {
        Runnable task;
        synchronized (this) {
            task = tasks.pollFirst();
        }
        if (task == null) {
            return false;
        }
        task.run();
        return true;
    }

    /**
     * Runs all currently queued work, including work queued by those tasks.
     *
     * @return number of tasks run
     */
    public int runAll() {
        int count = 0;
        while (runNext()) {
            count++;
        }
        return count;
    }

    /**
     * Returns the number of tasks awaiting manual execution.
     *
     * @return pending task count
     */
    public synchronized int getPendingTaskCount() {
        return tasks.size();
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        ArrayList<Runnable> pending = new ArrayList<>(tasks);
        tasks.clear();
        return pending;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        java.util.Objects.requireNonNull(unit, "unit");
        return isTerminated();
    }
}
