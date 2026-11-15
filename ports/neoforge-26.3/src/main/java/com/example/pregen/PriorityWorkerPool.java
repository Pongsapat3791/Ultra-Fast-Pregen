package com.example.pregen;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The worker threads, taking a batch tail's tasks (lighting, mob spawning, encoding) before the
 * next batch's: the next batch's features wait for that tail, while its structures, terrain and
 * carvers have time to spare. Tasks of one rank run in submission order.
 */
final class PriorityWorkerPool extends ThreadPoolExecutor {
    private static final int URGENT = 0;
    private static final int NORMAL = 1;

    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService urgent = new AbstractExecutorService() {
        @Override
        public void execute(Runnable task) {
            PriorityWorkerPool.super.execute(new Ranked(task, URGENT, sequence.getAndIncrement()));
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException("shut down the pool itself");
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException("shut down the pool itself");
        }

        @Override
        public boolean isShutdown() {
            return PriorityWorkerPool.this.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return PriorityWorkerPool.this.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return PriorityWorkerPool.this.awaitTermination(timeout, unit);
        }
    };

    PriorityWorkerPool(int threads, ThreadFactory factory) {
        super(threads, threads, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), factory);
    }

    @Override
    public void execute(Runnable task) {
        super.execute(task instanceof Ranked ? task : new Ranked(task, NORMAL, sequence.getAndIncrement()));
    }

    /** The same threads, for tasks that go ahead of every normal one still queued. */
    ExecutorService urgent() {
        return urgent;
    }

    private record Ranked(Runnable task, int rank, long order) implements Runnable, Comparable<Ranked> {
        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(Ranked other) {
            return rank != other.rank ? Integer.compare(rank, other.rank) : Long.compare(order, other.order);
        }
    }
}
