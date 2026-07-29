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
