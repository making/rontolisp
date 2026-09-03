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
