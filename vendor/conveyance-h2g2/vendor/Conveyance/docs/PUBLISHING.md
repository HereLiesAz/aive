# Publishing to Maven Central: feasibility

An honest accounting of what's already true, what's missing, and what only the project owner can
decide -- not a plan to execute unilaterally, since two of the gaps below are exactly the kind of
call this framework's own build config has already, correctly, refused to make on its own (see the
root `build.gradle.kts`'s own comment on why it emits no `licenses { }` block).

## What's already in place

Every publishable module (`conveyance-core`, `conveyance-compose`) applies `maven-publish` and
gets a POM with `name`, `description`, `url`, `developers`, and `scm` -- the root `build.gradle.kts`
supplies the last three once, for every module, rather than each module repeating them. That is
most of what Sonatype's Central Portal actually validates on a POM.

## What's missing, and who has to decide it

Three gaps, in the order they'd actually block a release:

1. **A license.** Central rejects a POM with no `licenses { }` block, and the build already
   deliberately emits none: there's no `LICENSE` file in this repository, and choosing one is a
   real decision (permissive vs. copyleft, compatibility with whatever this project's own
   dependencies require) that belongs to the project owner, not to a default this build -- or this
   session -- should assume on their behalf.
2. **Namespace verification.** Central requires proving ownership of the group ID's namespace
   before it'll accept anything published under it. This repo's group is `com.hereliesaz.conveyance`
   -- reverse-DNS under `hereliesaz.com`. Two real paths, and they lead to different group IDs:
   - **Domain verification**: if `hereliesaz.com` (or another domain HereLiesAz controls) is real
     and reachable, Central verifies it via a DNS TXT record, and the existing `com.hereliesaz.*`
     group can be kept as-is.
   - **GitHub verification**: Central will also verify `io.github.hereliesaz` via GitHub OAuth,
     with no domain needed -- but that's a *different* group ID than the one this repo, and every
     downstream composable-set library, already depends on today via JitPack
     (`com.github.HereLiesAz.Conveyance:conveyance-core:main-SNAPSHOT`, resolved as
     `com.hereliesaz.conveyance:conveyance-core` once published there). Switching groups for
     Central would mean either publishing under two different coordinates depending on the
     repository, or updating every consumer -- a real migration, not a build-file toggle.

   Either path needs an account action (a DNS record, or a GitHub OAuth grant) only the account
   owner can perform.
3. **A Sonatype Central Portal account and a GPG signing key.** Central requires every artifact
   signed, and the portal itself needs a namespace registered against an account. Both the account
   and the GPG private key are credentials; neither should be generated or held by this session --
   an agent minting a signing key and account on someone else's behalf would be a bigger problem
   than the one this investigation is meant to solve.

## The recommended path, once those three exist

The [Vanniktech Gradle Maven Publish
Plugin](https://github.com/vanniktech/gradle-maven-publish-plugin) is the de facto standard for
exactly this shape of project -- a multi-module Kotlin Multiplatform library publishing to Central
via the modern Publisher API (the old OSSRH/Nexus staging flow Sonatype is sunsetting). It handles
signing, the KMP multi-target publication set, and the Central Portal upload in one plugin, and is
already what most comparable KMP libraries (including ones in the Compose Multiplatform ecosystem)
use for this. The alternative -- hand-rolling `maven-publish` + the `signing` plugin + a manual
`curl` to the Central Portal's upload API -- is more surface area to maintain for no real benefit
once those three prerequisites exist.

The wiring itself is a small, additive change once unblocked: add the plugin, point it at the
Central Portal, and feed it the GPG key and Sonatype token via Gradle properties or environment
variables (`ORG_GRADLE_PROJECT_signingInMemoryKey`, `mavenCentralUsername`, etc.) -- never
committed, so CI supplies them as secrets the same way any other credential is. It should be
additive to, not a replacement for, the current JitPack-based publishing this repo and its five
composable-set dependents already rely on: JitPack needs no account, no signing, and no namespace
verification, which is exactly why every one of those libraries -- appropriately -- reaches for it
first, pre-release. Central is worth adding once there's a real, licensed, tagged release; it isn't
a replacement for `main-SNAPSHOT` development coordinates.

## A gotcha for consumers with more than one Kotlin target

`conveyance-core` and `conveyance-compose` -- and each of the five separate composable-set repos
(`conveyance-h2g2`, `conveyance-expressive`, `conveyance-liquid`, `conveyance-bacterium`,
`conveyance-space`) that depend on them -- publish via AGP's split-module KMP layout: JitPack
resolves a root artifact (e.g. `conveyance-compose`) plus separate `-android`/`-desktop`
coordinates, joined by Gradle Module Metadata `available-at` redirects rather than classifiers.

That layout has a real Gradle resolution bug for any consumer whose own build configures more than
one Kotlin target: Gradle resolves **both** of a dependency's `available-at` targets as real graph
nodes, regardless of which target is actually being compiled -- not just the one the current
source set needs. Concretely, on a `desktopCompileClasspath`, the `-desktop` split module resolves
correctly with a fully attribute-matched variant, but the sibling `-android` split module is *also*
required directly by that same classpath and fails outright, since it correctly has no
jvm-platform variant to offer. This isn't hypothetical: it's exactly what broke
`conveyance-demo`'s own build once it depended on the five composable-set libraries from a project
that itself targets both `androidLibrary` and `jvm("desktop")` (see
[HereLiesAz/Conveyance#38](https://github.com/HereLiesAz/Conveyance/pull/38)), confirmed with
`./gradlew :conveyance-demo:dependencyInsight --configuration desktopCompileClasspath --dependency
conveyance-h2g2`.

The fix is to exclude, per source set, whichever split coordinate that target will never need:

```kotlin
val desktopMain by getting {
    dependencies {
        implementation("com.github.HereLiesAz:conveyance-h2g2:main-SNAPSHOT") {
            exclude(group = "com.github.HereLiesAz.conveyance-h2g2", module = "conveyance-h2g2-android")
        }
    }
}
val androidMain by getting {
    dependencies {
        implementation("com.github.HereLiesAz:conveyance-h2g2:main-SNAPSHOT") {
            exclude(group = "com.github.HereLiesAz.conveyance-h2g2", module = "conveyance-h2g2-desktop")
        }
    }
}
```

`conveyance-demo/build.gradle.kts`'s own `addFiveComposableSets` helper is the working, generalized
version of this pattern for all five composable-set libraries at once, worth reading as a template.
Anyone consuming more than one of Conveyance's own split-published libraries (this repo's or the
composable-set repos') from a project with more than one Kotlin target will hit the same failure
and needs the same per-target exclude -- this is a property of the split-module layout itself, not
something a Maven Central migration (above) would remove.

## Bottom line

Feasible, and the build is already most of the way there. What's actually blocking it is two
decisions (a license; which namespace-verification path, and therefore which group ID) and two
credentials (a Sonatype account, a GPG key) that have to come from the project owner -- not
engineering work this session can complete on its own behalf.
