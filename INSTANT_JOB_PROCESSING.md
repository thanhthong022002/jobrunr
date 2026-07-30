# Instant job processing (push-based pickup)

Upstream JobRunr picks up enqueued jobs by **polling**. `pollInterval` defaults to **15 seconds**, and
`AbstractStorageProvider.validatePollInterval` enforces a **5 second floor** for every SQL/NoSQL provider
(200 ms only for `InMemoryStorageProvider`). So a job you enqueue right now waits somewhere between 0 and
`pollInterval` before a worker touches it, and you cannot configure that away.

This fork adds a **push** path on top of the poll: whoever enqueues a job tells the
`BackgroundJobServer` about it, and the server onboards it immediately. The poll stays exactly as it was and
becomes the safety net.

| | upstream | this fork |
|---|---|---|
| enqueue → PROCESSING latency | 0 – `pollInterval` (15 s default, 5 s floor) | sub-millisecond |
| `SCHEDULED` → PROCESSING | up to 2 × `pollInterval` (two separate tasks) | one `pollInterval` hop removed |
| DB load | 1 job-election query per `pollInterval` per server | unchanged when idle; coalesced under load |
| extra infrastructure | – | none (single node) / none but Postgres itself (cluster) |

## What was already there

Two things upstream already does, which this builds on rather than duplicates:

- `JobSteward.notifyThreadIdle()` calls `OnboardNewWorkTask.runTaskThreadSafe()` the moment a worker frees up.
  So a **busy** server never waits for the tick — only the first job after an idle gap pays. This fork
  refactors `notifyThreadIdle()` to go through the new `onboardNewWorkNow()` so there is one path, not two.
- `OnboardNewWorkTask` is guarded by a `ReentrantLock.tryLock()`, and `JobTable.selectJobsToProcess` uses
  `FOR UPDATE SKIP LOCKED`. That is why extra or concurrent wake-ups are safe: they are no-ops, not
  duplicate work.

## Changes to existing classes

Three small additions, no behaviour change for anyone who does not opt in:

| File | Change |
|---|---|
| `JobSteward` | new `public void onboardNewWorkNow()`; `notifyThreadIdle()` now delegates to it |
| `BackgroundJobServer` | new `public void onboardNewWorkNow()` (guards on `isNotReadyToProcessJobs()`) |
| `core/build.gradle` | `compileOnly 'org.postgresql:postgresql'` — only the Postgres bridge needs it, so no new runtime dependency |

## New classes — `org.jobrunr.server.instant`

- **`InstantJobProcessingFilter`** — the whole feature. Implements **both**
  - `JobClientFilter.onCreated` — somebody called `enqueue(...)`; runs in the enqueueing JVM;
  - `ApplyStateFilter.onStateApplied` — a `SCHEDULED`/carbon-aware job came due and was enqueued by
    `ProcessScheduledJobsTask` **on the master node**.

  Both are needed. A client-side-only filter misses every scheduled job; a server-side-only filter misses
  every direct `enqueue(...)` from an app node that is not the master.

- **`InstantJobProcessing`** — one-call wiring, returns an `AutoCloseable`.
- **`JobEnqueuedPublisher` / `JobEnqueuedSubscriber`** — the cluster SPI (no-op for a single node).
- **`postgres.PostgresJobEnqueuedBridge`** — `LISTEN`/`NOTIFY` implementation of both SPIs.

### Wake-ups are async and coalesced

`requestOnboarding()` never runs the job-election query on the caller's thread — it hands off to a single
daemon thread behind a compare-and-set flag:

- the **enqueueing** thread (often a request thread) never pays for a DB round-trip;
- enqueueing 10 000 jobs in a loop produces a handful of onboarding passes, not 10 000.

No wake-up can be lost. The pass resets the flag **before** it queries, and `onCreated` fires **after** the
job is saved. So a job that commits while a pass is in flight either is already visible to that pass, or
sets the flag again and gets its own pass.

Every failure in this path is logged and swallowed on purpose: a missed wake-up only costs latency, because
the poll still picks the job up.

## Wiring — single node

```java
BackgroundJobServer backgroundJobServer = /* as usual */;
InstantJobProcessing instantProcessing = InstantJobProcessing.enableOn(backgroundJobServer);

// the filter MUST also go on the scheduler: JobScheduler takes its filters in the constructor
// and never exposes them, so it cannot be retrofitted afterwards.
JobScheduler scheduler = new JobScheduler(storageProvider, singletonList(instantProcessing.jobFilter()));

// on shutdown
instantProcessing.close();
```

> ⚠️ **Do not copy that two-argument `new JobScheduler(...)` into a Spring Boot app.** It is correct
> for a hand-rolled setup, where you were choosing the `JobDetailsGenerator` yourself anyway — but
> `JobScheduler` has a `(storageProvider, jobDetailsGenerator, filters)` constructor, and the
> two-arg form falls back to the default generator. The Spring Boot starter configures that
> generator from `jobrunr.job-scheduler.job-details-generator` (default
> `CachingJobDetailsGenerator`), so overriding its bean with the two-arg form **silently drops the
> configured generator** and changes how job details are derived — with no error and no log line.
> Use the Spring Boot form below, which keeps it.

### Spring Boot

The starter declares `backgroundJobServer` and `jobScheduler` as `@ConditionalOnMissingBean`, so declaring
your own wins:

```java
@Bean(destroyMethod = "close")   // releases the listener connection + thread on shutdown
InstantJobProcessing instantJobProcessing(BackgroundJobServer backgroundJobServer) {
    return InstantJobProcessing.enableOn(backgroundJobServer);
}

@Bean
JobScheduler jobScheduler(StorageProvider storageProvider,
                          JobRunrProperties properties,
                          InstantJobProcessing instantJobProcessing) {
    // Mirror JobRunrAutoConfiguration#jobScheduler exactly apart from the filter list — in
    // particular keep the configured JobDetailsGenerator. Using the two-arg
    // new JobScheduler(storageProvider, filters) here would silently drop it.
    JobDetailsGenerator jobDetailsGenerator =
        ReflectionUtils.newInstance(properties.getJobScheduler().getJobDetailsGenerator());
    return new JobScheduler(
        storageProvider, jobDetailsGenerator, singletonList(instantJobProcessing.jobFilter()));
}
```

Two things that are easy to get wrong here:

- **Gate this on the background job server existing.** The starter declares `backgroundJobServer`
  `@ConditionalOnProperty(prefix = "jobrunr.background-job-server", name = "enabled", havingValue = "true")`,
  so in any profile that leaves it off (typically tests) there is no server to inject and every
  context load fails. Put the same condition on your configuration class.
  `@ConditionalOnBean(BackgroundJobServer.class)` does **not** work for this: user `@Configuration`
  is parsed before autoconfigurations register their bean definitions, so it always evaluates false.
- **`JobRequestScheduler` takes filters too** and is a separate `@ConditionalOnMissingBean`. If you
  use `JobRequest`s, override it the same way; otherwise it needs no attention.

## Wiring — cluster (Postgres `LISTEN`/`NOTIFY`)

A local wake-up only helps the JVM that enqueued. For more than one `BackgroundJobServer`, every node has to
hear about it. `PostgresJobEnqueuedBridge` does that through the database you are already using — no broker:

```java
PostgresJobEnqueuedBridge bridge = new PostgresJobEnqueuedBridge(dataSource);
InstantJobProcessing instantProcessing = InstantJobProcessing.enableOn(backgroundJobServer, bridge, bridge);
JobScheduler scheduler = new JobScheduler(storageProvider, singletonList(instantProcessing.jobFilter()));
```

Things worth knowing:

- **It holds one connection per node** for the lifetime of the subscriber, idle-blocked in
  `getNotifications(...)`. Size the pool for it, or give it its own `DataSource` — a pool that hands its last
  connection to the listener will starve.
- **Notifications are delivered on commit**, which is what we want: by the time the other nodes wake up, the
  job row is visible to them.
- A dropped connection is not fatal — the listener reconnects, and until then that node just falls back to
  its `pollInterval`.
- The channel name is validated against `[a-z_][a-z0-9_]{0,62}` rather than escaped, because a `LISTEN`
  channel is an identifier and cannot be a bind parameter.

### Alternative: a database trigger

```sql
CREATE OR REPLACE FUNCTION jobrunr_notify_job_enqueued() RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('jobrunr_job_enqueued', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER jobrunr_jobs_enqueued_trigger
AFTER INSERT OR UPDATE OF state ON jobrunr_jobs
FOR EACH ROW WHEN (NEW.state = 'ENQUEUED')
EXECUTE FUNCTION jobrunr_notify_job_enqueued();
```

Catches jobs enqueued by anything at all, including clients that are not this application — at the cost of
DDL plus one notification per row (the application-side publisher is coalesced per burst). Use it only if you
have enqueuers outside your JVMs.

## Not covered

- **Exact-time scheduling.** `ProcessScheduledJobsTask` deliberately pre-enqueues everything due within the
  next `pollInterval` (`scheduledBefore = now() + pollInterval`), so scheduled jobs can start *early*. That is
  a separate concern from pickup latency — see upstream discussion #442 — and this change does not touch it.
- **Priority queues.** Pickup is still strict FIFO (`ORDER BY updatedAt ASC`). Unrelated feature.
- **Non-Postgres clusters.** Implement `JobEnqueuedPublisher`/`JobEnqueuedSubscriber` over whatever you have
  (Redis pub/sub, Kafka, Rabbit, a Hazelcast topic). The SPI is two one-method interfaces.

## Tests

- `InstantJobProcessingFilterTest` — both enqueue paths trigger onboarding; scheduled jobs, recurring jobs and
  non-`ENQUEUED` transitions do not; burst coalescing; remote announcements do not re-announce; a failing
  publisher or a failing onboarding never breaks enqueueing; nothing fires after `close()`.
- `InstantJobProcessingTest` — end-to-end against `InMemoryStorageProvider` with a **30 s** `pollInterval`:
  with the filter the job succeeds within 5 s; the control (no filter) is still `ENQUEUED` after 5 s; a remote
  announcement alone also triggers processing.
