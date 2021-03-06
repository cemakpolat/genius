/*
 * Copyright (c) 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.genius.datastoreutils;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.GuardedBy;
import org.opendaylight.infrautils.jobcoordinator.JobCoordinator;
import org.opendaylight.infrautils.utils.concurrent.LoggingThreadUncaughtExceptionHandler;
import org.opendaylight.infrautils.utils.concurrent.LoggingUncaughtThreadDeathContextRunnable;
import org.opendaylight.infrautils.utils.concurrent.ThreadFactoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DataStoreJobCoordinator.
 *
 * @deprecated Use org.opendaylight.infrautils.jobcoordinator.JobCoordinator
 *             instead of this. Please note that in its new reincarnation its no
 *             longer a static singleton but now an OSGi service which can
 *             (must) {@literal @}Inject into your class using it.
 */
@Deprecated
public class DataStoreJobCoordinator implements JobCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(DataStoreJobCoordinator.class);

    private static final int THREADPOOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final long RETRY_WAIT_BASE_TIME = 100;

    // package local instead of private for TestDataStoreJobCoordinator
    final ForkJoinPool fjPool;
    final Map<Integer, Map<String, JobQueue>> jobQueueMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(5);
    private final ReentrantLock reentrantLock = new ReentrantLock();
    private final Condition waitCondition = reentrantLock.newCondition();

    @GuardedBy("reentrantLock")
    private boolean isJobAvailable = false;

    private static DataStoreJobCoordinator instance;

    static {
        instance = new DataStoreJobCoordinator();
    }

    public static DataStoreJobCoordinator getInstance() {
        return instance;
    }

    private DataStoreJobCoordinator() {
        fjPool = new ForkJoinPool(
                Math.min(/* MAX_CAP */ 0x7fff, Runtime.getRuntime().availableProcessors()),
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                LoggingThreadUncaughtExceptionHandler.toLogger(LOG),
                false);

        for (int i = 0; i < THREADPOOL_SIZE; i++) {
            Map<String, JobQueue> jobEntriesMap = new ConcurrentHashMap<>();
            jobQueueMap.put(i, jobEntriesMap);
        }

        ThreadFactoryProvider.builder()
            .namePrefix("DataStoreJobCoordinator-JobQueueHandler")
            .logger(LOG)
            .build().get()
        .newThread(new JobQueueHandler()).start();
    }

    /* package local */ void destroy() {
        fjPool.shutdownNow();
        scheduledExecutorService.shutdownNow();
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker) {
        enqueueJob(key, mainWorker, (RollbackCallable)null, 0);
    }

    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker,
            RollbackCallable rollbackWorker) {
        enqueueJob(key, mainWorker, rollbackWorker, 0);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker,
            org.opendaylight.infrautils.jobcoordinator.RollbackCallable rollbackWorker) {
        enqueueJob(key, mainWorker, new RollbackCallable() {
            @Override
            public List<ListenableFuture<Void>> call() throws Exception {
                return rollbackWorker.call();
            }
        }, 0);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker, int maxRetries) {
        enqueueJob(key, mainWorker, (RollbackCallable)null, maxRetries);
    }

    @Override
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker,
            org.opendaylight.infrautils.jobcoordinator.RollbackCallable rollbackWorker, int maxRetries) {
        enqueueJob(key, mainWorker, new RollbackCallable() {
            @Override
            public List<ListenableFuture<Void>> call() throws Exception {
                return rollbackWorker.call();
            }
        }, maxRetries);
    }

    /**
     *    This is used by the external applications to enqueue a Job
     *    with an appropriate key. A JobEntry is created and queued
     *    appropriately.
     */
    public void enqueueJob(String key, Callable<List<ListenableFuture<Void>>> mainWorker,
                           RollbackCallable rollbackWorker, int maxRetries) {
        JobEntry jobEntry = new JobEntry(key, mainWorker, rollbackWorker, maxRetries);
        Integer hashKey = getHashKey(key);
        LOG.debug("Obtained Hashkey: {}, for jobkey: {}", hashKey, key);

        Map<String, JobQueue> jobEntriesMap = jobQueueMap.get(hashKey);
        synchronized (jobEntriesMap) {
            JobQueue jobQueue = jobEntriesMap.get(key);
            if (jobQueue == null) {
                jobQueue = new JobQueue();
            }
            if (LOG.isTraceEnabled()) {
                LOG.trace("Adding jobkey {} to queue {} with size {}", key, hashKey, jobEntriesMap.size());
            }
            jobQueue.addEntry(jobEntry);
            jobEntriesMap.put(key, jobQueue);

            DataStoreJobCoordinatorCounters.jobs_pending.inc();
            DataStoreJobCoordinatorCounters.jobs_incomplete.inc();
            DataStoreJobCoordinatorCounters.jobs_created.inc();
        }
        signalForNextJob();
    }

    public long getIncompleteTaskCount() {
        return DataStoreJobCoordinatorCounters.jobs_incomplete.get();
    }

    /**
     * Cleanup the submitted job from the job queue.
     **/
    private void clearJob(JobEntry jobEntry) {
        Integer hashKey = getHashKey(jobEntry.getKey());
        Map<String, JobQueue> jobEntriesMap = jobQueueMap.get(hashKey);
        LOG.trace("About to clear jobkey {} from queue {}", jobEntry.getKey(), hashKey);
        synchronized (jobEntriesMap) {
            JobQueue jobQueue = jobEntriesMap.get(jobEntry.getKey());
            jobQueue.setExecutingEntry(null);
            if (jobQueue.getWaitingEntries().isEmpty()) {
                LOG.trace("Clear jobkey {} from queue {}", jobEntry.getKey(), hashKey);
                jobEntriesMap.remove(jobEntry.getKey());
            }
        }
        DataStoreJobCoordinatorCounters.jobs_cleared.inc();
        DataStoreJobCoordinatorCounters.jobs_incomplete.dec();
        signalForNextJob();
    }

    /**
     * Used to generate the hashkey in to the jobQueueMap.
     */
    private Integer getHashKey(String key) {
        int code = key.hashCode();
        return (code % THREADPOOL_SIZE + THREADPOOL_SIZE) % THREADPOOL_SIZE;
    }

    /**
     * JobCallback class is used as a future callback for main and rollback
     * workers to handle success and failure.
     */
    private class JobCallback implements FutureCallback<List<Void>> {
        private final JobEntry jobEntry;

        JobCallback(JobEntry jobEntry) {
            this.jobEntry = jobEntry;
        }

        /**
         * This implies that all the future instances have returned
         * success. -- TODO: Confirm this
         */
        @Override
        public void onSuccess(List<Void> voids) {
            LOG.trace("Job {} completed successfully", jobEntry.getKey());
            clearJob(jobEntry);
        }

        /**
         * This method is used to handle failure callbacks. If more
         * retry needed, the retrycount is decremented and mainworker
         * is executed again. After retries completed, rollbackworker
         * is executed. If rollbackworker fails, this is a
         * double-fault. Double fault is logged and ignored.
         */
        @Override
        public void onFailure(Throwable throwable) {
            LOG.warn("Job: {} failed", jobEntry, throwable);
            if (jobEntry.getMainWorker() == null) {
                LOG.error("Job: {} failed with Double-Fault. Bailing Out.", jobEntry);
                clearJob(jobEntry);
                return;
            }

            int retryCount = jobEntry.decrementRetryCountAndGet();
            if (retryCount > 0) {
                long waitTime = RETRY_WAIT_BASE_TIME * 10 / retryCount;
                scheduledExecutorService.schedule(() -> {
                    MainTask worker = new MainTask(jobEntry);
                    fjPool.execute(worker);
                }, waitTime, TimeUnit.MILLISECONDS);
                return;
            }

            if (jobEntry.getRollbackWorker() != null) {
                jobEntry.setMainWorker(null);
                RollbackTask rollbackTask = new RollbackTask(jobEntry);
                fjPool.execute(rollbackTask);
                return;
            }

            clearJob(jobEntry);
        }
    }

    /**
     * RollbackTask is used to execute the RollbackCallable provided by the
     * application in the eventuality of a failure.
     */
    private class RollbackTask extends LoggingUncaughtThreadDeathContextRunnable {
        private final JobEntry jobEntry;

        RollbackTask(JobEntry jobEntry) {
            super(LOG, jobEntry::toString);
            this.jobEntry = jobEntry;
        }

        @Override
        @SuppressWarnings("checkstyle:IllegalCatch")
        public void runWithUncheckedExceptionLogging() {
            RollbackCallable callable = jobEntry.getRollbackWorker();
            callable.setFutures(jobEntry.getFutures());
            List<ListenableFuture<Void>> futures = null;

            try {
                futures = callable.call();
            } catch (Exception e) {
                LOG.error("Exception when executing jobEntry: {}", jobEntry, e);
            }

            if (futures == null || futures.isEmpty()) {
                clearJob(jobEntry);
                return;
            }

            ListenableFuture<List<Void>> listenableFuture = Futures.allAsList(futures);
            Futures.addCallback(listenableFuture, new JobCallback(jobEntry), MoreExecutors.directExecutor());
            jobEntry.setFutures(futures);
        }
    }

    /**
     * Execute the MainWorker callable.
     */
    private class MainTask extends LoggingUncaughtThreadDeathContextRunnable {
        private static final int LONG_JOBS_THRESHOLD = 1000; // MS
        private final JobEntry jobEntry;

        MainTask(JobEntry jobEntry) {
            super(LOG, jobEntry::toString);
            this.jobEntry = jobEntry;
        }

        @Override
        @SuppressWarnings("checkstyle:illegalcatch")
        public void runWithUncheckedExceptionLogging() {
            List<ListenableFuture<Void>> futures = null;
            long jobStartTimestamp = System.currentTimeMillis();
            LOG.trace("Running job {}", jobEntry.getKey());

            try {
                futures = jobEntry.getMainWorker().call();
                long jobExecutionTime = System.currentTimeMillis() - jobStartTimestamp;
                printJobs(jobExecutionTime);
            } catch (Throwable e) {
                LOG.error("Exception when executing jobEntry: {}", jobEntry, e);
            }

            if (futures == null || futures.isEmpty()) {
                clearJob(jobEntry);
                return;
            }

            ListenableFuture<List<Void>> listenableFuture = Futures.allAsList(futures);
            Futures.addCallback(listenableFuture, new JobCallback(jobEntry), MoreExecutors.directExecutor());
            jobEntry.setFutures(futures);
        }

        private void printJobs(long jobExecutionTime) {
            if (jobExecutionTime > LONG_JOBS_THRESHOLD) {
                LOG.warn("Job {} took {}ms to complete", jobEntry.getKey(), jobExecutionTime);
                return;
            }
            LOG.trace("Job {} took {}ms to complete", jobEntry.getKey(), jobExecutionTime);
        }
    }

    private class JobQueueHandler implements Runnable {
        @Override
        @SuppressWarnings("checkstyle:illegalcatch")
        public void run() {
            LOG.info("Starting JobQueue Handler Thread with pool size {}", THREADPOOL_SIZE);
            while (true) {
                try {
                    for (int i = 0; i < THREADPOOL_SIZE; i++) {
                        Map<String, JobQueue> jobEntriesMap = jobQueueMap.get(i);
                        if (jobEntriesMap.isEmpty()) {
                            continue;
                        }
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("JobQueueHandler handling queue {} with key size {}. Keys: {} ", i,
                                jobEntriesMap.size(), jobEntriesMap.keySet());
                        }

                        synchronized (jobEntriesMap) {
                            Iterator<Map.Entry<String, JobQueue>> it = jobEntriesMap.entrySet().iterator();
                            while (it.hasNext()) {
                                Map.Entry<String, JobQueue> entry = it.next();
                                JobEntry executingEntry = entry.getValue().getExecutingEntry();
                                if (executingEntry != null) {
                                    LOG.trace("Job is under execution {}", executingEntry);
                                    continue;
                                }
                                JobEntry jobEntry = entry.getValue().getWaitingEntries().poll();
                                if (jobEntry != null) {
                                    entry.getValue().setExecutingEntry(jobEntry);
                                    MainTask worker = new MainTask(jobEntry);
                                    LOG.trace("Executing job {} from queue {}", jobEntry.getKey(), i);
                                    fjPool.execute(worker);
                                    DataStoreJobCoordinatorCounters.jobs_pending.dec();

                                } else {
                                    it.remove();
                                }
                            }
                        }
                    }
                    waitForJobIfNeeded();
                } catch (Exception e) {
                    LOG.error("Exception while executing the tasks", e);
                } catch (Throwable e) {
                    LOG.error("Error while executing the tasks", e);
                }
            }
        }
    }

    private boolean isJobQueueEmpty() {
        for (int i = 0; i < THREADPOOL_SIZE; i++) {
            Map<String, JobQueue> jobEntriesMap = jobQueueMap.get(i);
            if (!jobEntriesMap.isEmpty()) {
                return false;
            }
        }

        return true;
    }


    private void signalForNextJob() {
        reentrantLock.lock();
        try {
            isJobAvailable = true;
            waitCondition.signalAll();
        } finally {
            reentrantLock.unlock();
        }
    }

    private void waitForJobIfNeeded() throws InterruptedException {
        reentrantLock.lock();
        try {
            while (!isJobAvailable) {
                waitCondition.await(1, TimeUnit.SECONDS);
            }
            isJobAvailable = false;
        } finally {
            reentrantLock.unlock();
        }
    }
}
