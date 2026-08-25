# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Open RuneLite — a fork of RuneLite (OldSchool RuneScape client). `origin` is `Orvian/open-runelite`, `upstream` is
`melxin/open-runelite`, which itself tracks `runelite/runelite` master. The fork's stated goal (BUILD_GUIDE.md) is
**minimal downtime at game revision updates by staying close to upstream master**.

Practical consequence: every edit to an upstream-owned file is a merge conflict at the next revision update. Prefer
additive, isolated changes; don't mass-refactor upstream code (e.g. don't migrate the ~117 `-Xlint` deprecation
warnings in `runelite-client` — upstream has the same ones).

### Upstream reference checkout

A full vanilla `runelite/runelite` clone sits at `../runelite` (`/home/user/Documents/git/osrs/runelite`, on
`master`.**Use it.** It answers most "how is this supposed to work / did we break it or is it upstream" questions without a network trip:

```bash
diff -rq ../runelite/runelite-client/src/main/java ./runelite-client/src/main/java   # what this fork changed
diff ../runelite/<path> ./<path>                                                     # per-file divergence
git -C ../runelite log --oneline -S "someSymbol" -- <path>                           # when/why upstream changed it
git -C ../runelite log --oneline HEAD                                                # upstream commits not merged yet
```

Typical uses: confirm a warning/bug is inherited rather than fork-local, find the upstream replacement for a removed
API, copy an upstream fix, or check whether a file is upstream-owned before editing it.

## Build & test

The main build contains only `client` (dir `runelite-client`) and `jshell` (dir `runelite-jshell`). `cache`,
`runelite-api`, and `runelite-gradle-plugin` are **included builds** — plain `./gradlew build` does not build them.

```bash
./gradlew build                  # client + jshell only
./gradlew :cache:build :runelite-api:build :client:build
./gradlew buildAll               # root aggregate: main build + all included builds
./gradlew testAll cleanAll assembleAll publishAllToMavenLocal
./gradlew :client:test --tests "*SpecialCounterPluginTest*"     # single test class
./gradlew :client:clean :client:build                           # force full rebuild of all client jars
```

`build` runs checkstyle + PMD (`pmdTest` disabled). CI (`.github/workflows/CI.yml`) runs `./ci/build.sh` on **JDK 11**:
it fetches glslang, exports `ORG_GRADLE_PROJECT_glslangPath`, then `./gradlew --build-cache :buildAll`. Without that
property `gpu/ShaderTest` is skipped.

Run the client:

```bash
./gradlew :client:shadowJar
java -ea -jar runelite-client/build/libs/client-*-shaded.jar [--developer-mode]
```

`--developer-mode` also loads jars from `~/.runelite/sideloaded-plugins/`. Other flags come from the jopt parser in
`RuneLite.main` (`--safe-mode`, `--debug`, `--profile`, `--jav_config`, …).

## Toolchain gotchas

- Sources target Java 11 (`options.release = 11` in common.settings.gradle.kts); CI builds on JDK 11.
- On JDK 17+ mockito 3.1.0's transitive byte-buddy 1.9.10 cannot instrument, and interface default methods mocked
  with `CALLS_REAL_METHODS` blow up with "Cannot call abstract real method". byte-buddy 1.14.19 is pinned in
  libs.versions.toml for that reason — don't drop it while building on a modern JDK.
- **Gradle dependency verification is enabled.** Any new or version-bumped dependency needs a sha256 entry in
  `gradle/verification-metadata.xml` or the build fails with "Dependency verification failed".

## Architecture

**The game client is not in this repo.** `net.runelite:injected-client` is a `runtimeOnly` dependency pulled from
`repo.runelite.net`. `rs/ClientLoader` downloads `jav_config`, loads the initial class by name from the classpath,
and casts it to `net.runelite.api.Client`.

That drives the single most important API rule: `runelite-api` interfaces are implemented by a **prebuilt jar**.
Adding an abstract method to `Client` (or any interface it implements) breaks that jar at runtime. Add `default`
methods instead — the file already has ~11 deprecated defaults delegating to `getTopLevelWorldView()`.

Flow of control:

- The injected client calls back into `callback/Hooks` (`frame()`, `tick()`, `tickEnd()`, `draw()`, `drawScene()`,
  key/mouse) — this is the boundary between gamepack and client code.
- `Hooks` posts to `eventbus/EventBus`: reflection over `@Subscribe`-annotated, single-argument, void, non-static
  methods, ordered by priority. Plugins are registered/unregistered with the bus by `PluginManager`.
- `RuneLite.main` builds a Guice injector from `RuneLiteModule`. Every `Plugin` is itself a Guice `Module` with its
  own child injector; `@PluginDescriptor` carries name/tags/`enabledByDefault`/`developerPlugin`/`loadInSafeMode`;
  `PluginManager` drives `startUp()`/`shutDown()`.
- `callback/ClientThread` is the game-thread work queue (`invoke`, `invokeLater`, `invokeAtTickEnd`). Most API calls
  must run on the client thread.
- Config: `@ConfigGroup` interfaces are dynamic proxies (`config/ConfigInvocationHandler`) backed by `ConfigManager`,
  persisted per profile under `~/.runelite`.
- Overlays go through `ui/overlay/OverlayManager` + `OverlayRenderer`.
- ~131 plugin packages live in `runelite-client/src/main/java/net/runelite/client/plugins/`.

## Generated code and IDs

- `runelite-gradle-plugin` supplies four Gradle plugins used by the other modules: `component` generates
  `ComponentID`/`InterfaceID` from `runelite-api/src/main/interfaces/interfaces.toml`; `assemble` compiles the
  `.rs2asm` scripts in `runelite-client/src/main/scripts` into client resources; plus `index` and `jarsign`.
- `net.runelite.api.gameval.*` is the current ID surface and is regenerated wholesale per revision (see commits
  "Update GameVals to …"). Legacy `ItemID`/`NpcID`/`ObjectID`/`Varbits`/`WidgetInfo` are deprecated — use `gameval`.
- `runelite-api`'s `runtimeJar` deliberately excludes constant-only ID classes: they are inlined at compile time.

## Fork-specific behavior

- Sideloaded-plugin hot reload: `PluginManager.reloadSideLoaded(...)` plus the `plugins/pluginreloader` package
  (developer mode only). Not upstream.
- The plugin hub's verification infrastructure is unavailable, so external plugins are used via
  `~/.runelite/sideloaded-plugins/` + `--developer-mode`.
- Deprecated `default` compatibility shims are retained in the API for sideloaded plugins built against older
  revisions (`Client.getMapAngle()` → `getCameraYawTarget()`, `NPC`/`NPCComposition.getOverheadIcon()`). When a
  sideloaded plugin dies with `NoSuchMethodError` on a `net.runelite.api` symbol, adding such a shim is the
  established fix; `javap -c` on the plugin jar lists every API symbol it references.
- `coords/WorldArea` carries extra pathing helpers (`calculateNextTravellingPoint`).

## Code style

Checkstyle 8.3 (`config/checkstyle/`) enforces: **tab indentation**, Allman braces (`LeftCurly option=nl`), no
trailing whitespace, no unused imports, `else if` on one line. Lombok is used throughout (sources are delombok'd for
javadoc, which is why `build/generated/sources/delombok` shows up in warnings).
