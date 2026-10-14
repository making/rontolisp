# g08. CI native-image: the binary cannot resolve `java.lang.System` (spec `mm-pick` macro)

Difficulty: Medium

Found by CI run `38481770107` (2026-10-14, commit `cefdcc2a0`): the `test` job
passed (1h2m), but every `native-image` leg fails in `Verify native image` at
`ClojureNativeLoweringE2eTest.theBinaryLowersEveryProgramAsTheJvmDoes` with the
same error (amd64 + arm64):

```
error: spec.clj:2117:1: in macro `mm-pick`: No such class: java.lang.System
```

The corpus case `macro-arguments-keep-their-reader-metadata` (f66) expands
`(System/getProperty "no.such.property")` at macro time. The JVM lowers it from
class files, but the native binary runs on image reflection (`java.home` is null
in images, f69) and f69 registered only the front-end-named classes — `System`
is not among them, so the binary refuses a program the jar accepts.

## What decides the design

- f69's `reachability-metadata.json` list is pinned to front-end constants by
  `ClojureHostImageTypesTest`. `System/getProperty` is now a lowered portable
  static on wasm (f84) and a legitimate macro-time call — registering `System`
  extends that decision; weigh the web image's size as f69 did.
- The binary test runs the WHOLE corpus: enumerate every other unregistered
  macro-time host class the same way (run the binary test with a no-JDK binary
  locally, e.g. scrubbed `JAVA_HOME`/`PATH` per f69) and register the full set,
  or the next corpus addition breaks the leg again.

## Plan

1. Reproduce locally: no-JDK native binary over the corpus, list every
   `No such class` the corpus hits.
2. Register the set (with the constants-derived pin updated + size measured);
   `theBinaryLowersEveryProgramAsTheJvmDoes` green locally incl. the `mm-pick`
   case; docs en + ja if user-visible.
