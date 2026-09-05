# 688. The corpus tests duplicate `CompileFrontend`'s splice chain, and drift from it silently

Difficulty: Medium

## What happened (2026-09-03)

`JvmClassShakerCorpusTest.optimizesTheWholeCorpusWithoutDecoderGapsAndBehavesIdentically`
went red on `develop` with

```
The function TOKENIZER:PRE-TOKENIZE is undefined
```

the moment a `tokenizer:` case joined the shared `ci-spec.yaml` corpus. Nothing was
wrong with the case, the tokenizer library or the shaker. **Both corpus tests build
their own splice chain instead of calling the one the CLI uses**, and theirs had
fallen behind.

The single source of truth is `cli/CompileFrontend.java`'s chain (the
`List<LispVal> program = UnreadCharLibrary.process(...)` expression). The two copies:

- `src/test/java/am/ik/jvm/JvmClassShakerCorpusTest.java`
- `src/test/java/am/ik/wasm/WasmTreeShakerCorpusTest.java`

**Seven libraries were missing from them, not one** (counted on develop before the
fix): `TokenizersLibrary`, `SafetensorsLibrary`, `CheckpointLibrary`,
`SceneLibrary`, `MetalLibrary`, `AppKitLibrary`, `WitLibrary` -- and `GgufLibrary`
landed the same day, making eight. Only the tokenizer one showed, because it is the
only one the corpus reaches. That ratio is the argument: the copies do not drift by
one, they drift by however many libraries have been added since anyone last looked
at them.

The chain was patched in place (the four the checkpoint work needed) to clear the
red. **This item is the structural fix.**

## The second defect it uncovered: a green test that printed its own failure

Same run, same corpus, same missing splice, two different outcomes:

- the **JVM** leg throws on the FIRST undefined call, so it reported ONE name and
  went red;
- the **WASM** leg compiles every undefined call as a call-time error, prints a
  warning per call site, and **passed** -- fifteen lines of this on standard output
  under a green test:

```
warning: the function TOKENIZER:PRE-TOKENIZE is undefined; compiled as a call-time error   (x3)
warning: the function TOKENIZER:MAKE-BPE is undefined; compiled as a call-time error
warning: the function TOKENIZER:ENCODE is undefined; compiled as a call-time error         (x4)
warning: the function TOKENIZER:DECODE is undefined; compiled as a call-time error         (x2)
warning: the function TOKENIZER:VOCABULARY-SIZE / TOKEN-ID / TOKEN-STRING / BOS-ID / EOS-ID is undefined
```

**Do: a test that prints compile warnings must ASSERT on them.** A single
`warning: ... is undefined` line from a corpus compile makes the test red. The
fifteen lines above are the evidence that the rule is worth having, and
`WasmTreeShakerCorpusTest` still prints `RONTOLISP::%HTTP-REACTOR-DISPATCH is
undefined` today for the same reason (its chain has no `HttpReactorLibrary`, which
the JVM copy does) -- so the assertion has a live second instance to catch the
moment it is added.

**This is not the "the test never exercised the subject" failure mode.** The test
DID exercise it, the program WAS broken, the breakage WAS on standard output, and
the test was green. Counting inputs would not have found it. Only reading the
output would -- and nobody reads the output of a green test.

## The general shape

**There is one right place, and the copies diverge silently.** `CompileFrontend`'s
chain is order-critical (its comment explains each ordering: `TorchLibrary` before
`LinalgLibrary`, `JsonLibrary` after `GeomLibrary`, the macOS three innermost-first,
the checkpoint readers before the prelude), and two other files restate that order
by hand. Nothing checks that they agree.

This is the third of a family. `.todo/683` is a width asked for by NAME STRING in
places the exhaustive switch does not reach; `.todo/687` is a width CARRIED AS A
BOOLEAN where a type would do. Here it is an ORDERED PIPELINE written out three
times. Each is "the fact lives in one place, and the other places restate it
where nothing can notice they are wrong".

## Do

1. Put the chain in ONE place and call it from three. Either:
   - lift the whole `UserMacroExpander` -> splices -> `LibraryDefunPruner` stretch
     of `CompileFrontend.run` into a method both corpus tests call (it needs the
     target `Features`, the `LoadInliner` output and the HTTP-pass flags, all of
     which the tests already compute), or
   - have the corpus tests go through `CompileFrontend` itself, which means giving
     it a seam that stops before the backend -- it already returns a
     `CompileFrontend.Result`, so this may be the smaller change.
   **Whichever: the ORDER moves with it.** A version that exposes the libraries as a
   list for a caller to fold is the same bug with more steps.
2. Assert on compile warnings in both corpus tests: capture the compile's output and
   fail on `warning: ` + `is undefined`. Expect `WasmTreeShakerCorpusTest` to go red
   on `%HTTP-REACTOR-DISPATCH` immediately; fix that by giving the wasm copy the
   `HttpReactorLibrary` pass the JVM copy has (or by whatever step 1 makes of it).
3. A test that the two agree, if step 1 leaves any duplication at all: the set of
   library classes each path applies, compared.

## Done (2026-09-05)

**There is now exactly one copy of the order, and no test restates it.**

`CompileFrontend.run` was split: `run` is the read plus the `(load ...)` inlining, and
everything from `rewriteAsyncSugar` to `LibraryDefunPruner` -- every pass, every ordering
comment -- moved into `CompileFrontend.expand`. Both corpus guards call it through
`src/test/java/am/ik/rontolisp/cli/CorpusFrontend`, a test-only bridge that lives in the
`cli` package precisely so `expand` and `Result` stay package-private: the fix is to stop
duplicating an order, not to widen the CLI's API for a test.

**Why the split is at the READ and not further up.** The guards pass `LoadInliner` a source
loader that THROWS, and that is an assertion rather than a convenience: it is how they
guarantee the corpus can never come to depend on a file on disk. Routing them through
`run`, which hardcodes `SourceLoader.fileSystem()`, would have silently converted that
guarantee into "resolves against the real filesystem" -- so `run` keeps the read and
`expand` starts after it. This item's "Do" listed going through `CompileFrontend` itself as
probably the smaller change; it is not, once that property has to be preserved.

### The census, so nobody has to re-measure it

Measured against `CompileFrontend` on develop, 2026-09-05, before the fix. Both guards were
missing TEN passes each, not the eight this item recorded:

`SceneLibrary`, `MetalLibrary`, `AppKitLibrary`, `WitLibrary`, `SocketsLibrary`,
`StdinLibrary`, `EnvironmentLibrary`, `ExitLibrary`, `WitExportInliner`,
`CompileTimeBoundp`.

And the WASM guard was missing two more the JVM guard had: `UnreadCharLibrary` and
`HttpReactorLibrary`. This item predicted the second of those as the live instance the
warning assertion would catch; it was two, not one.

### The finding that membership counting could not have caught

**`VecLibrary` was PRESENT in all three places and ran in a different POSITION.**
`CompileFrontend` runs it near the end, after `EnvironmentLibrary` / `ExitLibrary` and
gated on `!(wasm && noGc)`. Both guards ran it innermost of their final group, AHEAD of the
Gray-streams, usocket and unread-char rewrites -- so a `vec:` reference any of those three
introduces was spliced by the CLI and missed by both guards. A census of pass names returns
"present" for all three. This is exactly what "the copies restate an order where nothing
can notice they are wrong" means, and it is the argument for moving the ORDER rather than
exporting a list of libraries: a list would have made this bug easier to write, not harder.

### The warning assertion, and its negative control

Step 2 needed no production change. `compiler/CompileWarnings` already routes every warning
to `System.err` -- straight through for the WASM backends, via `flushAttempt()` for the JVM
backend, which compiles twice and keeps one -- so each guard captures `System.err` around
its compiles and fails on any `warning: ` + `is undefined` line. Adding a collector to
`CompileWarnings` that only a test uses would have been test scaffolding in production.

**The assertion was proved to fire before being trusted.** `TokenizersLibrary` was removed
from `expand` and the JVM guard went red with ten undefined `TOKENIZER:` names -- the
2026-09-03 failure reproduced exactly, and this item's own lesson visible in the output:
ten names means the thing that supplies the surface never ran, not that a selection dropped
one. Restored, both guards green. An assertion nobody has watched fail is the same class of
defect this item is about.

### Step 3 is moot, deliberately

"A test that the two agree, **if step 1 leaves any duplication at all**" -- it leaves none.
Both guards call one method; there are no two sets of library classes to compare. A test
asserting that one pipeline equals itself would be noise.

### What did NOT move

Nothing needed relaxing or re-baselining. The JVM guard's constant-pool figure is 38846 of
its 52000 bound (it was already inside it), `optimized < plain` holds on both backends and
in both `--no-wasi` modes, and the `wasm-tools` round-trip fixpoint still holds -- despite
the guards now running ten more passes each. No threshold was updated and no invariant
broke, so neither of the two escalation cases arose.

Files: `src/main/java/am/ik/rontolisp/cli/CompileFrontend.java`,
`src/test/java/am/ik/rontolisp/cli/CorpusFrontend.java` (new),
`src/test/java/am/ik/jvm/JvmClassShakerCorpusTest.java`,
`src/test/java/am/ik/wasm/WasmTreeShakerCorpusTest.java`, plus the invariant in CLAUDE.md's
`CompileFrontend` bullet and `.kb/library-defun-pruning.md`. Nothing in `compiler/` or
`codegen/*/`.

## How the diagnosis went, and the lesson in it

Worth keeping, because the wrong diagnosis was the reasonable-looking one.

The safetensors lane identified the cause (no `TokenizersLibrary` in the chain).
Another lane had first read it as "the shaker's reachability analysis is dropping a
defun" -- which fits a single missing function perfectly. The orchestrator refuted
it from the log: **all eight of the package's public names were undefined, not one**,
so nothing had been dropped; the splice had never run. And the two legs' different
symptoms -- one name on the JVM, fifteen on wasm -- were not two bugs but one bug
seen through a fail-fast and a warn-and-continue front end.

**When narrowing a symptom to a mechanism, count what failed.** One name means a
selection bug; the whole surface means the thing that supplies the surface never
ran.
