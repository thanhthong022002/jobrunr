package org.jobrunr.server;

import org.jobrunr.jobs.Job;
import org.jobrunr.server.tasks.steward.OnboardNewWorkTask;
import org.jobrunr.server.tasks.steward.UpdateJobsInProgressTask;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The JobSteward manages everything related to local jobs (e.g. updating them periodically and fetching new work)
 */
public class JobSteward extends JobHandler implements Runnable {

    private final Map<Job, Thread> jobsCurrentlyInProgress;
    private final AtomicInteger occupiedWorkers;
    private final OnboardNewWorkTask onboardNewWorkTask;

    public JobSteward(BackgroundJobServer backgroundJobServer) {
        super(backgroundJobServer,
                new UpdateJobsInProgressTask(backgroundJobServer),
                new OnboardNewWorkTask(backgroundJobServer));
        this.jobsCurrentlyInProgress = new ConcurrentHashMap<>();
        this.occupiedWorkers = new AtomicInteger();
        this.onboardNewWorkTask = getTaskOfType(OnboardNewWorkTask.class);
    }

    public void startProcessing(Job job, Thread thread) {
        Optional<Job> optionalExistingThatMayBeReplacedJob = jobsCurrentlyInProgress.keySet().stream().filter(j -> j.getId().equals(job.getId())).findFirst();
        optionalExistingThatMayBeReplacedJob
                .map(j -> j.delete("Job has been replaced"))
                .map(jobsCurrentlyInProgress::get)
                .ifPresent(Thread::interrupt);
        jobsCurrentlyInProgress.put(job, thread);
    }

    public void stopProcessing(Job job) {
        jobsCurrentlyInProgress.remove(job);
    }

    public Set<Job> getJobsInProgress() {
        return jobsCurrentlyInProgress.keySet();
    }

    public Thread getThreadProcessingJob(Job job) {
        return jobsCurrentlyInProgress.get(job);
    }

    public int getOccupiedWorkerCount() {
        return occupiedWorkers.get();
    }

    public void notifyThreadOccupied() {
        occupiedWorkers.incrementAndGet();
    }

    public void notifyThreadIdle() {
        this.occupiedWorkers.decrementAndGet();
        onboardNewWorkNow();
    }

    /**
     * Immediately looks for enqueued jobs instead of waiting for the next pollInterval tick.
     * <p>
     * Safe to call from any thread and as often as you like: the underlying {@link OnboardNewWorkTask} is guarded by a
     * {@code tryLock} and the storage query uses {@code FOR UPDATE SKIP LOCKED}, so concurrent or redundant invocations
     * are no-ops rather than duplicate work.
     * <p>
     * <b><em>Please note:</em></b> this method runs the onboarding on the <em>calling</em> thread - it performs a
     * database round-trip. Callers on a latency-sensitive path (e.g. a request thread that just enqueued a job) should
     * hand off to another thread; {@link org.jobrunr.server.instant.InstantJobProcessingFilter} does exactly that.
     */
    public void onboardNewWorkNow() {
        onboardNewWorkTask.runTaskThreadSafe();
    }
}