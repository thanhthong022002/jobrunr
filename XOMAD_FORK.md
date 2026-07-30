# XOMAD fork of JobRunr

A **long-lived fork**. JobRunr underpins the internal **flow** framework in the backend's `ai`
module, and building that out means changing JobRunr itself — so this fork is where XOMAD-only
changes live, and it exists to get them into the **company Nexus** where internal work can build
against them. Expect it to accumulate changes over time; the publishing setup below is the permanent
part, not a one-off for any single feature.

Without this, a JobRunr change is unusable: upstream's build only knows how to publish to Sonatype /
Maven Central, so there is no way to hand a modified JobRunr to `xomad-internal-tools`.

## XOMAD changes carried here

| Change | Notes |
|---|---|
| Instant (push-based) job processing | [`INSTANT_JOB_PROCESSING.md`](INSTANT_JOB_PROCESSING.md) — removes the 0–`pollInterval` wait before an enqueued job is picked up |
| Nexus publishing | this document + [`gradle/xomad-nexus-publishing.gradle`](gradle/xomad-nexus-publishing.gradle) |

Add a row when you add a change. If a change touches a module that is not yet published, add it to
the published-module list too — see [Publishing](#publishing).

Tracked as **IT-64**.

## Baseline: upstream `master` (JobRunr 8.x)

This branch sits on latest upstream `master` (currently `v8.7.1` + 1 commit). That is deliberate: the
consumer is the backend's **`ai` module**, which runs **Spring Boot 4.0.5 / Java 25** and already
depends on `org.jobrunr:jobrunr-spring-boot-4-starter` (`ai/pom.xml`).

The **`api` module is not a consumer yet.** It runs Spring Boot 2.5.6 and depends on
`jobrunr-spring-boot-2-starter` — a module upstream **deleted after 7.5.3**, so it does not exist on
this baseline. `api` is being upgraded separately; when it is, it consumes the same Spring Boot 4 (or
3) starter as `ai` and only needs adding to the published-module list below. Do **not** re-base this
branch onto `v7.5.3` to serve `api` — that would drag `ai` back to 7.x.

Note for whoever bumps `ai`: it currently pins `8.5.2`, so moving to a fork of `master` also moves it
to `8.7.1`-era JobRunr. Skim upstream's 8.6 → 8.7 release notes rather than assuming it is a no-op.

## Toolchain

Use **JDK 26**. The Gradle wrapper is 9.5.

```bash
export JAVA_HOME="$HOME/.jdks/jdk-26.0.1+8"
```

Two separate reasons, worth knowing because they fail differently:

- **Building** `:core` needs **JDK 25+**: it is a multi-release jar (`src/main/java`, `java17`,
  `java25` source sets compiled with `--release 8/17/25`).
- **Running the tests** needs **JDK 26**: upstream's `core/build.gradle` passes
  `jvmArgs '--illegal-final-field-mutation=deny'` to every `Test` task, and that flag does not exist
  before 26. On JDK 25 every test task dies with `Unrecognized option:
  --illegal-final-field-mutation=deny` / `Could not create the Java Virtual Machine` — which looks
  like a broken fork but is just the wrong JDK.

Publishing alone works on JDK 25, since it never starts a test JVM.

## Publishing

Artifacts go to the same Nexus repositories the backend's own modules publish to (compare
`simple-acl/pom.xml` and `mailchimp-client/pom.xml` `<distributionManagement>`):

| version | repository |
|---|---|
| `…-SNAPSHOT` | `https://nexus.internal.xomad.com/repository/maven-snapshots/` |
| anything else | `https://nexus.internal.xomad.com/repository/maven-releases/` |

This is a **Maven artifact** repository, and it is the right target. `us.gcr.io/xomad-1084/` is a
*container image* registry — the jib base images and the deployable `code/app/*` images, see
`<xomad.container-prefix>` in the backend poms — and no Maven build can resolve a jar out of it.

### Credentials

Never commit these. Either `~/.gradle/gradle.properties`:

```properties
xomadNexusUsername=you@xomad.com
xomadNexusPassword=<nexus password>
```

or the environment (`NEXUS_USERNAME` / `NEXUS_PASSWORD`). Same credential as the Maven/npm Nexus
setup.

### Publish

```bash
./gradlew -PxomadVersion=8.7.1-xomad.1 publishXomadArtifacts
```

`publishXomadArtifacts` publishes the modules XOMAD consumes — currently `:core`
(`org.jobrunr:jobrunr`) and `:framework-support:jobrunr-spring-boot-4-starter`. The quarkus /
micronaut / kotlin modules and the Spring Boot 3 starter are left out because nothing here consumes
them and they only cost build time.

**Adding a module** is one line — `ext.xomadPublishedProjects` in
[`gradle/xomad-nexus-publishing.gradle`](gradle/xomad-nexus-publishing.gradle). Any subproject that
applies `maven-publish` already gets the `xomadNexus` repository, so nothing else needs touching.

**Dry-run against a throwaway local repository first** — the real Nexus is shared and a published
release version cannot be replaced:

```bash
./gradlew -PxomadVersion=8.7.1-xomad.1 -PxomadNexusBaseUrl=file:///tmp/fake-nexus publishXomadArtifacts
```

### Versioning — snapshots while developing, releases to pin

The group stays `org.jobrunr` and the artifact ids are unchanged, so consuming the fork is always a
one-line version bump with no import or code changes.

Since this fork keeps evolving, use the two channels for what they are:

- **Iterating on a JobRunr change** → a snapshot, e.g. `-PxomadVersion=8.7.1-xomad-SNAPSHOT`. It
  routes to `maven-snapshots` automatically (chosen from the version suffix), and snapshots **can**
  be republished, so you can push repeatedly under one version while the consumer keeps a fixed
  `<jobrunr.version>`. `ai/pom.xml` already enables snapshots on that repository. Consumers need
  `mvn -U` to pick up a re-published snapshot.
- **Anything anyone else builds against** → a release, `<nearest upstream tag>-xomad.N`, e.g.
  `8.7.1-xomad.1`. Bump `N` every time: `maven-releases` **rejects** overwriting an existing version,
  which is the point — a pinned release never changes underneath a build.

Don't leave a shared branch or a deploy pinned to a snapshot; cut an `-xomad.N` release once the
change settles.

Publishing refuses to run without `-PxomadVersion`, on purpose — upstream's fallback version is
`1.0.0-SNAPSHOT`, which would be a meaningless thing to push into a shared repository.

### Tag every release

**A release is not done until it is tagged.** The Maven coordinates alone do not say *which commit*
was published: this branch keeps moving, and `maven-releases` stores a jar, not a git ref. Without a
tag, "what is actually in `8.7.1-xomad.1`?" is only answerable by guessing from timestamps.

So, immediately after a successful `publishXomadArtifacts` to the real Nexus:

```bash
git tag -a v8.7.1-xomad.1 -m "XOMAD JobRunr 8.7.1-xomad.1" <commit-that-was-published>
git push fork v8.7.1-xomad.1
```

Rules:

- **Tag name is `v` + the exact `-PxomadVersion` value** — `v8.7.1-xomad.1`. Matches upstream's own
  `v8.7.1` style and never collides with it.
- **Annotated (`-a`), not lightweight**, so the tag carries who cut it and when.
- **Tag the commit that was built**, which is not necessarily `HEAD` at the time you remember to tag —
  if you have already committed documentation on top, pass the published commit explicitly.
- **Never move or delete a published tag.** `maven-releases` refuses to overwrite a version, and the
  tag has to stay just as immutable or it stops being evidence. Made a mistake? Cut `-xomad.N+1`.
- **Snapshots are not tagged.** They are re-publishable by design, so a tag would be a lie.

Then add a row to [Releases](#releases) below.

## Releases

One row per published `-xomad.N` release, newest first. This is the changelog: it answers "what
changed between two versions, and what commit is each one?" — which the Nexus artifacts cannot.

Record the **XOMAD-visible** change, not upstream's churn. If a release exists only to move the
upstream baseline, say that.

| Version | Tag | Commit | Upstream baseline | Published | Changes |
|---|---|---|---|---|---|
| `8.7.1-xomad.1` | `v8.7.1-xomad.1` | `6cc6cccf` | `v8.7.1` + 6 | 2026-07-30 | **First XOMAD release of the 8.x fork.** Carries (1) instant / push-based job processing — `org.jobrunr.server.instant.*`, `JobSteward.onboardNewWorkNow()`, `BackgroundJobServer.onboardNewWorkNow()`, and the Postgres `LISTEN`/`NOTIFY` bridge, see [`INSTANT_JOB_PROCESSING.md`](INSTANT_JOB_PROCESSING.md); (2) the XOMAD Nexus publishing setup itself (`gradle/xomad-nexus-publishing.gradle`). Consumed by `xomad-internal-tools` `ai` (IT-67), wired up in IT-68. Verified in that consumer: 62 ms enqueue → execution against a 45 s poll interval. |

### Adding a row

Do it in the same PR as the change, or immediately after publishing — a release that is only in
Nexus and not in this table is invisible to the next person. Include:

- the **consumer-visible effect**, not just the class names;
- the **ticket** (`IT-nn`) if there is one;
- anything a consumer must do beyond the version bump (new config keys, required beans, DDL). For
  `8.7.1-xomad.1` that is: instant processing is **opt-in**, so bumping the version alone changes
  nothing until the consumer registers the beans.

## Consuming it from the `ai` module

`ai/pom.xml` already declares both Nexus repositories, so this is the whole change:

```xml
<jobrunr.version>8.7.1-xomad.1</jobrunr.version>
```

The `jobrunr-spring-boot-4-starter` POM depends on `org.jobrunr:jobrunr` at the *same* forked
version, so the forked core is picked up automatically — there is no chance of pairing a forked
starter with upstream's core.

## How this is wired, and why it's additive

Upstream publishes to Sonatype OSSRH / Maven Central through the `nexusPublishing` block in the root
`build.gradle`. That block is **left untouched** so future upstream merges stay clean. Everything
XOMAD-specific lives in [`gradle/xomad-nexus-publishing.gradle`](gradle/xomad-nexus-publishing.gradle),
applied by a single line at the end of the root build. It only *adds* a publishing repository named
`xomadNexus` to the subprojects that already apply `maven-publish`.

GPG signing needs no attention: upstream already gates it on the `SIGNING_KEY` environment variable,
so with that unset nothing is signed — which Nexus accepts and Central would not.
