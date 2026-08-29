# Emitted output is a function of the program, not of the JVM run

Compiling the same source with the same build twice must produce byte-identical
output -- on every backend. This is not a nicety: byte-identity is how changes in
this repo are shown to leave unrelated programs alone (`.kb/library-defun-pruning.md`
pins the pruner that way, the EH-mode gate the same, and `.todo/179`'s whole landing
gate was a byte comparison). A compiler that emits a different-but-equivalent module
each run makes that evidence unavailable, and every future diff becomes unreadable.

## The trap: `Map.of` / `Set.of` iterate in a per-process-random order

`java.util.ImmutableCollections` seeds a `SALT` from `System.nanoTime()` at
class-initialization and uses it to scramble iteration order. So `Map.of`,
`Map.ofEntries`, `Set.of`, `Map.copyOf` and `Set.copyOf` iterate in an order that
differs between JVM runs -- by design, to stop callers depending on it.

This is fine for a membership set (`contains`) or a lookup table (`get`), which is
what nearly every such table here is for. It is a live bug the moment the ITERATION
order reaches emitted output.

**The one that bit us** (found 2026-07-26 while landing `.todo/179` phase 1):
`LispMacroExpander.SUBTYPEP_PARENTS` -- the built-in type lattice -- was a
`Map.ofEntries`. `subtypepUniverse` iterates it into a `LinkedHashSet`, which fixes
the group order of `subtypepAncestorTableForms`, which is emitted as the
`%SUBTYPEP-ANCESTOR-TABLE` data table for any program with a computed `subtypep`.
Four compiles of a four-line program with one unmodified jar produced four different
modules; `examples/db/postgres-hello.lisp` differed by ~1,200 bytes between any two
runs, always the same permutation of the same 1,954 emitted strings. Fixed with
`LispMacroExpander.orderedMap` (an insertion-ordered unmodifiable map); pinned by
`LispMacroExpanderTest#theRuntimeSubtypepTableIsEmittedInLatticeDeclarationOrder`.

`ClosRegistry` is the model to copy: every one of its maps is a `LinkedHashMap`, and
`classes()` is documented as "in definition order" precisely because that order is
emitted.

**The second one** (found 2026-08-29 while checking whether todo-562 shifted any
emitted bytes, fixed the same day): `SpecialVarCollector` passed the three
standard-stream specials as a `Set.of` to `collectDynamicallyBound`, whose `progv`
over-collection arm does `bound.addAll(specials)` into a `LinkedHashSet`. A program
containing a `progv` cannot be walked statically, so it takes that arm and the whole
seeded set lands in salted order -- which is the order BOTH backends mint their
globals in. Measured on a six-line `progv` program compiled eight times with one
unmodified build: **three distinct `.class` files and three distinct `.wasm` modules**
out of eight runs (`_g$*ERROR-OUTPUT*` and `_g$*STANDARD-INPUT*` swapping places, every
later constant-pool index shifting with them; the equivalent permutation of the WASM
global indices). On the concatenated ci-spec corpus the same swap moved 211 bytes.
`*STANDARD-OUTPUT*` looks stable only because most programs bind it and the static walk
adds it first.

Fixed by giving the seeded set an ORDER THE TYPE SYSTEM ENFORCES rather than a
convention: `SpecialVarCollector.SEEDED_STREAM_SPECIALS` is an unmodifiable
`SequencedSet`, and `collectDynamicallyBound(List, SequencedSet<String>)` now DECLARES
that parameter as `SequencedSet` -- a type no `Set.of`/`Set.copyOf` satisfies, so the
shape that caused this bug no longer compiles. `WitImportDirective.defpackageForm`,
flagged below since 2026-07-26 as the same shape, took the same tightening.
Pinned by `SpecialVarCollectorTest#aProgvProgramSeedsTheStreamSpecialsInAFixedOrder`
(and the two order-contract tests beside it).

**Prefer a `SequencedSet`/`SequencedMap` parameter type over a comment** wherever a
caller's iteration order reaches emitted output. It is the only form of this rule a
future refactor cannot quietly violate: both sites this file records were a `Set.of`
handed to something declared `Set`, and both would have been compile errors.

## The sweeps

Every `Map.of`/`Map.ofEntries`/`Set.of`/`Map.copyOf`/`Set.copyOf` site in `src/main/java`
has been walked twice: 313 sites on 2026-07-26, which found `SUBTYPEP_PARENTS` and
declared no other -- it looked only at CONSTANTS, not at what a `Set`-typed PARAMETER is
handed, which is exactly how it missed the second bug; and 384 sites on 2026-08-29
(122 of them the empty `Map.of()`/`Set.of()`, 0 in `src/web/java`), which added the
parameter question. **There is no third emission-reaching site.** Every iterated site
either sorts, or accumulates into something order-neutral (a `contains` set, a boolean, a
monotone fixpoint, a `HashMap` of `String`), or feeds a map that is only looked up by key
while the emitted order comes from a `List`. `am.ik.wasm` holds no hash collection at all.

Five places are one careless refactor away from the same bug (the sixth,
`LibraryDefunPruner.spellingsOf` / `ConstantCaseArmPruner.spellingsOf`, was closed on
2026-08-29 while landing the `DistClient` sort below: both private copies returned a
two-element `Set.of` that `List.copyOf` turned into a List whose element order varied
per run, and both are now the one shared `PackageRegistry.spellings`, which returns a
`List.of` in a fixed order -- external `pkg:member` first, then internal `pkg::member`.
Same principle as the `SequencedSet` parameters: the ORDER IS IN THE TYPE, so no
consumer can inherit a scrambled one. Pinned by
`PackageRegistryTest#bothSpellingsOfAQualifiedNameComeBackInAFixedOrder`):

- `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS` -- a `Set.copyOf` that IS iterated,
  but only into an exclusion set consulted with `contains`; the emitted wrapper order
  comes from the `WRAPPER_DEFS` list.
- `WasmComponentBuilder.FIXED_BLOCK_IFACES` -- a `Map.of` whose `keySet()` fills the
  `blockBound` map passed to `lowerFixedFromBlock`. Safe only because that method never
  iterates it; one `for (var e : instanceOf.entrySet())` and the component's
  alias/canon/core-instance order goes per-run.
- `WasmSocketsRewrite.SYNC_DISPATCH` -- inverted through a `Map.copyOf` into
  `fallbackTargets`, which IS emitted into `builtinForwarderDefun`. Safe only because the
  nine values are distinct, so last-write-wins never fires.
- `UiopLibrary.RESOURCES` -- a `Map.of` iterated while building the per-name form lists
  that are emitted wholesale. Safe only because no name is defined in two different
  `uiop-*.lisp` files. (The method already comments its OUTPUT maps against `Map.copyOf`;
  the input map is the half that was missed.)
- The `LinkedHashSet` chains in `FreeVarAnalyzer`/`GlobalVarCollector` that mint JVM
  static fields.

The `Set`/`Map` PARAMETERS that genuinely carry emission order are now all typed
`SequencedSet` (`SpecialVarCollector.collectDynamicallyBound`,
`JvmDynVarRuntimeBuilder.build`, `JvmRawGlobals.collect`,
`WitComponentTypeEncoder.encode`, `WitImportWorldEmitter.iface`,
`WitImportDirective.defpackageForm`), so no caller can hand one an unordered set.
The rest are fed ordered collections by discipline and copy into a `HashMap`/`HashSet` or
use the argument as a predicate: `AsdfRuntimeLibrary.registryDefvar`/`runTestOpDefun`
(a `LinkedHashMap`/`LinkedHashSet` from `LoadInliner`),
`LispMacroExpander.expandRuntimeFindPackage`/`expandPackageQuery` (`TreeMap`s from
`PackageResolver`), `JvmRuntimeBuilder.buildDispatchMethods` (a `HashMap` of `String`,
safe per the rule below), `ComponentImportBlock.prune`, `WasmExports.retain`.

## The sibling: what the emitted program PRINTS must not vary either

The same rule applies one level down, to the stdout of the compiled program: run it
twice, get the same text. The way to break it is to let a host `toString` reach a
printer, because the default `Object.toString` ends in an identity hash. The JVM
backend did exactly that for a hash table until `.todo/430` (`{"a"=[Ljava.lang.Object;@3fee733d}`,
a different number each run) and still does for the mutex handle -- which is why
`.kb/mutexes.md` declares the handle opaque and unprintable rather than leaving the
question open. A printer arm that answers a fixed tag is the fix; `.kb/hash-tables.md`
has the shape.

## Why CI cannot catch this

`native-image` freezes `ImmutableCollections.SALT` at BUILD time, so the native binary
is reproducible even when the JVM build is not. `CiSpecE2eTest` runs the native binary
(`-Drontolisp.binary`), so the whole cross-backend E2E suite is blind to it, and
`./mvnw test` compares behavior rather than bytes. Reproducibility has to be checked
the way the bug was found: compile the same program N times with `java -jar` and
compare the bytes.

## What the DistClient sort moved (2026-08-29)

The sort was landed with the measurement, because reordering a search path can pick a
different `.asd` for one system name and therefore change a program's emitted bytes.
Measured on this host's real quicklisp cache (82 releases, 194 `.asd` files):

- **The search path does reorder.** Four of the cached releases hold `.asd` files in more
  than one directory, and every one of them came back in an order the host chose:
  `jzon-v1.1.4` walked `test/` before `src/`, `mgl-pax` walked `autoload/` before its own
  top level, `iterate`'s five `ext/*` directories came back permuted, and `cffi`'s
  `uffi-compat/` sat after the root by accident rather than by rule. After the sort all
  four are in path order, top level first.
- **Nothing resolved differently.** No cached release ships two `.asd` files with the same
  basename, and `AsdfSystems.locate` asks each search directory for `NAME.asd` -- so on
  this corpus exactly one directory can answer for any system name, whatever the order.
  (The two duplicated basenames in the cache, `alexandria.asd` and `rt.asd`, are duplicated
  ACROSS releases -- `iterate`'s vendored `ext/alexandria` beside the real alexandria
  release -- and which of those wins is decided by the PROJECT order in `ensureAvailable`,
  which this change does not touch.)
- **So no emitted bytes moved.** Eleven quicklisp-backed examples were compiled to
  `.class` with the jar before and after the change; the eight that compile offline here
  (`asdf/str-demo`, `net/hello-clack`, `net/httpbin-clack`, `net/httpbin-ningle`,
  `net/httpbin-tiny-routes`, `net/httpbin-jzon`, `db/postgres-hello`, `jvm/cffi-sqlite` --
  including the jzon and cffi releases that reordered) are byte-identical, md5 for md5.
- **Nothing else consumes the order.** The list only ever becomes `Ctx.systemPath` /
  the evaluator's search path, which is read by `AsdfSystems.locate` and never emitted;
  no runtime variable exposes it (there is no `asdf:*central-registry*` here). Hence this
  change cannot shift cross-backend output, and `CiSpecE2eTest` -- which downloads
  nothing -- is unaffected.

The exposure the sort closes is therefore not on this machine's corpus: it is a release
that ships a top-level `foo.asd` beside a `test/foo.asd`, where the two developers'
filesystems disagree about which one `locate` sees first. That case is synthesized by
`DistClientTest#aReleaseDefiningOneSystemTwiceResolvesToItsTopLevelAsd`.

## Rules

- A collection whose iteration order can reach emitted bytes, generated AST, or WIT
  text must be insertion-ordered (`LinkedHashMap`/`LinkedHashSet`, or
  `LispMacroExpander.orderedMap`) or explicitly sorted. Never `Map.of`/`Set.of`.
- Say that in the TYPE, not a comment, when the collection crosses a method boundary:
  declare the parameter (and the field) `SequencedSet`/`SequencedMap`. `Set.of`,
  `Set.copyOf`, `Map.of` and `HashSet` are none of those, so a caller cannot hand one
  over by accident. Both sites above were a `Set.of` passed to a `Set` parameter.
- A plain `HashMap`/`HashSet` is deterministic for a given key set (`String.hashCode`
  is specified), so it is safe for emission order -- but only while its keys have
  stable hash codes. A map keyed by an object that does not override `hashCode` uses
  identity hashes and varies per run.
- Filesystem order (`Files.list`, `File.listFiles`, `Files.walk`) is not an order.
  Sort it. `cli/FormatCommand` does, and `eval/DistClient` now does too
  (`addAsdDirs`, 2026-08-29): the quicklisp `.asd` search path was the `Files.walk`
  order of each extracted release, so which `.asd` won when a release ships two
  defining one system name was the host's directory order. Machine-dependent rather
  than run-dependent, so compiling twice on one host could not see it -- the
  measurement is in the "What the DistClient sort moved" section above. Pinned by
  `DistClientTest#theAsdDirectoriesOfAReleaseAreSortedWhateverOrderTheHostWalkedThemIn`
  (the ordering itself, on a synthetic walk order) and
  `#aReleaseDefiningOneSystemTwiceResolvesToItsTopLevelAsd` (the consequence: a
  release's top-level `.asd` beats one nested under it, because a parent path is a
  prefix of its children). (`eval/SourceLoader.list` is also unsorted but only feeds
  the RUNTIME `directory` built-in, which CL leaves unordered anyway.)
- Generated temp names (`__mv<N>`, `__db<N>`, `__flet<N>`, `gensym`) come from
  counters and may legitimately renumber when the amount of macro-time evaluation
  changes (`.kb/flet-labels.md`, `.kb/gensym-macroexpand.md`). Renumbering across two
  DIFFERENT compilers is expected; renumbering across two runs of the SAME compiler is
  this bug.
