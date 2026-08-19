# 448. `--optimize` on by default, `--optimize=off` to decline (parent)

Difficulty: Medium

Today `--optimize` is opt-in, and the two shapes are so far apart that the
flagless one is not a shape anybody ships. Same jar, same machine, 2026-08-19:

| program / target | no flag | `--optimize` |
| --- | ---: | ---: |
| hello-world, wasm-GC Preview 1 | 156,641 | **532** |
| `size-report/programs/zlib`, wasm-GC | 347,228 | **122,300** |
| `size-report/programs/zlib`, JVM class | 403,913 | **151,992** |

So the flag is pasted into every build script, every doc command line and every
example as boilerplate, and the one invocation that forgets it silently ships
nearly 300x the bytes. Flip it: **an absent `--optimize` means `DEFAULT`, and
`--optimize=off` is the only way to ask for the unoptimized shape.**

`--optimize` / `--optimize=default` / `--optimize=size` keep meaning exactly
what they mean today, so no existing build script changes meaning.

## What the spike established (2026-08-19)

The spike patched `OptimizeLevel` alone (`parse(null) -> DEFAULT`,
`NONE("off")`) and measured against the pre-patch exec jar.

1. **The flip moves no semantics.** The one behavior a reader expects to be
   `--optimize`-gated -- a function reachable only through a designator the
   compiler cannot read stops resolving -- is NOT gated on the level:
   `Wasm/JvmLispCompiler.dispatchableFuncIds` bails on `this.dynamic ||
   anyNameResolvable` and never asks `eliminatesDeadCode()`. Verified: a
   program funcalling `(intern (string-upcase (concatenate 'string "gre"
   suffix)))` over a runtime `suffix` already fails identically at both levels
   on the JVM and on wasm-GC, and works on the interpreter at both. What
   `--optimize` adds is the DROP of code the gate had already made
   unreachable. The `eval`/`read`/`read-from-string`/`load` bail
   (`RuntimeNameProducers.anyNameResolvable`) is likewise level-independent, so
   an `eval`-using program keeps every function whether or not the flag is on.
2. **Compile time does not move**: hello 0.92 s -> 0.94 s, zlib wasm 2.09 s ->
   2.21 s (+6%), zlib JVM 2.20 s -> 2.21 s (best of three each).
3. **`./mvnw test` is green under the CLI-level flip**: 7,903 tests, 3
   failures, all three in `OptimizeLevelTest` and all three pinning the old
   meaning by name.
4. **The whole `ci-spec.yaml` corpus is green shaken except two cases**: run
   through a binary stand-in that injects `--optimize` into every compile,
   1,570 of 1,572 pass on all four backends. The two that fail are
   `uncaught-condition-report` and `uncaught-simple-error-report` on
   `WASM_COMPONENT`, and they are a REAL pre-existing bug in
   `--optimize --component` -- `.todo/452`, a blocker for this parent.
5. The passes that DO switch on with the flip are six
   `eliminatesDeadCode()` sites: the two shakers (`WasmTreeShaker` +
   `WasmBodyFolder`, `JvmClassShaker`), `DeadTypeBranchPruner`,
   `GenericDispatchNarrowing` (also `&& !dynamic`), the `--component` fixed-WASI
   surface narrowing, and `NoGcWasmCompiler`'s shake. Each is
   behavior-preserving by its own analysis and each is already exercised at
   `--optimize` today -- what is NEW is the corpora that have never run shaken
   (below).

## Why this is not one item

One thing must be FIXED before the flip can land, and three more do not follow
from the CLI flip and would be silently wrong if left behind.

The blocker: **`--component --optimize` loses the uncaught-condition report**
(`.todo/452`). The component's fixed-WASI narrowing decides "can this program
present fd 2" from a scan of the SOURCE, and the EH landing pad's `%warn` is
synthesized during Pass 2, so the scan never sees it and the report's write
becomes the trap the narrow half promises. It is broken today at `--optimize`
and at `--optimize=size`; the flip would make it the default answer.

The three that would be left behind:

- **the embedder API and the browser playground** still default to `NONE`, so
  `new WasmLispCompiler()` and the playground's "compile to WASM" button would
  keep emitting the 150 KB shape while the CLI emits 500 B (`.todo/450`);
- **the size-report matrix measures its baseline by passing NO flags**
  (`size-report/measure.sh`'s `*_plain` rows), so after the flip its "(none)"
  rows would silently re-measure the OPTIMIZED module and the daily workflow
  would commit that as if a compiler change had produced it (`.todo/451`);
- **the docs teach the flag as opt-in** on 14 pages per language, and the
  README, the agent skill template and two `printUsage()` texts repeat it
  (`.todo/453`).

## The children

1. `.todo/452` -- **first**: `--component --optimize` narrows away the
   uncaught-condition report. Independent of everything else here and worth
   fixing on its own merits; the flip must not land before it.
2. `.todo/449` -- the flip itself: `NONE` gets the `off` spelling, the absent
   flag becomes `DEFAULT`, the two help texts, the unit pins.
3. `.todo/450` -- the compiler classes and the playground follow the CLI, so
   there is one answer to "what does rontolisp do by default".
4. `.todo/451` -- the measurement baselines say `--optimize=off` instead of
   saying nothing.
5. `.todo/453` -- the documentation stops calling it opt-in (en + ja, README,
   skill template). Folds `.todo/414`, which rewrites the same two passages.

Order: 452, then 449 (everything after it reads its spelling); 450, 451 and 453
are independent of each other. `.kb/optimize-dead-code-elimination.md` is
updated by 449 (the level table and the `NONE` invariant) and by 451 (the
baseline rows); do not split that file's edit across all five.

## The corpora that have never run shaken

`CiSpecE2eTest` compiles with no `--optimize` on ALL THREE compile backends
(`-o Test.class`, `-o test.wasm`, `-o test.component.wasm`), and
`ExamplesE2eTest`'s `jvm` / `jvm-compile` legs likewise (its `wasm`,
`wasm-component` and `no-gc` legs already pass the flag). So the flip puts 392
ci-spec cases x 3 compile backends and every JVM example through the shakers
for the first time. That is coverage worth having, and it is also where a
latent shaker bug would surface -- treat a failure there as a first-class
finding of this parent, not as a reason to narrow the flip. It already found
one (`.todo/452`); the spike's remaining 1,570 green cases are the evidence
that it found only one.

**The spike's stand-in is reusable.** `CiSpecE2eTest` only needs
`-Drontolisp.binary` to point at something executable, so a two-line shell
script around the exec jar that appends `--optimize` whenever `-o` is present
runs the whole four-backend corpus in about a minute -- no `native-image` build
needed to answer "does the flip break anything". Keep the native run for the
final acceptance, where it is the CI job being reproduced.

## Acceptance (the parent closes when all five children are done)

- `--optimize=off` is byte-identical to today's flagless output, and bare
  `--optimize` / `=default` / `=size` byte-identical to today's, on all four
  backends (this is the pin that says the flip is a re-spelling, not a
  re-definition).
- The native `CiSpecE2eTest` run is green on all four backends, and
  `ExamplesE2eTest` is green.
- `size-report/measure.sh` still reports an unoptimized baseline row, and a
  re-run commits nothing but the date/commit stamps.
- `grep -rn -- --optimize` over `doc/`, `README.md`, the skill template and the
  two `printUsage()` texts turns up no sentence that calls the flag opt-in.
