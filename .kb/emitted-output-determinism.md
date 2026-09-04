# Emitted output is a function of the program, not of the JVM run

Compiling the same source with the same build twice must produce byte-identical output, on every backend. Byte-identity is how changes here are shown to leave unrelated programs alone (`.kb/library-defun-pruning.md` pins the pruner that way; the EH-mode gate likewise).

## The trap: `Map.of` / `Set.of` iterate in a per-process-random order

`java.util.ImmutableCollections` seeds a `SALT` from `System.nanoTime()` at class-initialization and scrambles iteration order with it. So `Map.of`, `Map.ofEntries`, `Set.of`, `Map.copyOf`, `Set.copyOf` iterate differently between JVM runs. Fine for a membership set (`contains`) or a lookup table (`get`); a live bug the moment ITERATION order reaches emitted output.

Two occurrences, both fixed:

- `LispMacroExpander.SUBTYPEP_PARENTS` (the built-in type lattice) was a `Map.ofEntries`. `subtypepUniverse` iterates it into a `LinkedHashSet`, fixing the group order of `subtypepAncestorTableForms`, emitted as the `%SUBTYPEP-ANCESTOR-TABLE` data table for any program with a computed `subtypep`. Fixed with `LispMacroExpander.orderedMap` (insertion-ordered unmodifiable map); pinned by `LispMacroExpanderTest#theRuntimeSubtypepTableIsEmittedInLatticeDeclarationOrder`.
- `SpecialVarCollector` passed the three standard-stream specials as a `Set.of` to `collectDynamicallyBound`, whose `progv` over-collection arm does `bound.addAll(specials)` into a `LinkedHashSet`. A program with `progv` cannot be walked statically, so the seeded set lands in salted order — which is the order BOTH backends mint their globals in. Eight compiles of one six-line program produced three distinct `.class` files and three distinct `.wasm` modules. Fixed by typing the order: `SpecialVarCollector.SEEDED_STREAM_SPECIALS` is an unmodifiable `SequencedSet` and `collectDynamicallyBound(List, SequencedSet<String>)` declares that parameter `SequencedSet` — a type no `Set.of`/`Set.copyOf` satisfies, so the bug's shape no longer compiles. `WitImportDirective.defpackageForm` took the same tightening. Pinned by `SpecialVarCollectorTest#aProgvProgramSeedsTheStreamSpecialsInAFixedOrder` plus the two order-contract tests beside it.

`ClosRegistry` is the model to copy: every map is a `LinkedHashMap`, and `classes()` is documented "in definition order" precisely because that order is emitted.

## Sweep status

Every `Map.of`/`Map.ofEntries`/`Set.of`/`Map.copyOf`/`Set.copyOf` site in `src/main/java` has been walked twice (313 sites, then 384 including the "what is a `Set`-typed PARAMETER handed" question; 0 in `src/web/java`). **There is no third emission-reaching site.** Every other iterated site sorts, accumulates into something order-neutral, or feeds a map looked up by key while emitted order comes from a `List`. `am.ik.wasm` holds no hash collection at all.

Five sites are one careless refactor from the same bug — safe today only by accident:

- `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS` — a `Set.copyOf` that IS iterated, but only into an exclusion set consulted with `contains`; emitted wrapper order comes from the `WRAPPER_DEFS` list.
- `WasmComponentBuilder.FIXED_BLOCK_IFACES` — a `Map.of` whose `keySet()` fills the `blockBound` map passed to `lowerFixedFromBlock`. Safe only because that method never iterates it; one `for (var e : instanceOf.entrySet())` and the component's alias/canon/core-instance order goes per-run.
- `WasmSocketsRewrite.SYNC_DISPATCH` — inverted through a `Map.copyOf` into `fallbackTargets`, emitted into `builtinForwarderDefun`. Safe only because the nine values are distinct, so last-write-wins never fires.
- `UiopLibrary.RESOURCES` — a `Map.of` iterated while building the per-name form lists that are emitted wholesale. Safe only because no name is defined in two different `uiop-*.lisp` files.
- The `LinkedHashSet` chains in `FreeVarAnalyzer`/`GlobalVarCollector` that mint JVM static fields.

A sixth was closed: `LibraryDefunPruner.spellingsOf` / `ConstantCaseArmPruner.spellingsOf` each returned a two-element `Set.of` that `List.copyOf` turned into a List whose element order varied per run. Both are now the one shared `PackageRegistry.spellings`, returning a `List.of` in a fixed order — external `pkg:member` first, then internal `pkg::member`. Pinned by `PackageRegistryTest#bothSpellingsOfAQualifiedNameComeBackInAFixedOrder`.

Parameters that genuinely carry emission order are all typed `SequencedSet`: `SpecialVarCollector.collectDynamicallyBound`, `JvmDynVarRuntimeBuilder.build`, `JvmRawGlobals.collect`, `WitComponentTypeEncoder.encode`, `WitImportWorldEmitter.iface`, `WitImportDirective.defpackageForm`. The rest are fed ordered collections by discipline and copy into a `HashMap`/`HashSet` or use the argument as a predicate: `AsdfRuntimeLibrary.registryDefvar`/`runTestOpDefun`, `LispMacroExpander.expandRuntimeFindPackage`/`expandPackageQuery`, `JvmRuntimeBuilder.buildDispatchMethods`, `ComponentImportBlock.prune`, `WasmExports.retain`.

## The sibling: what the emitted program PRINTS must not vary either

Run the compiled program twice, get the same text. The way to break it is to let a host `toString` reach a printer — the default `Object.toString` ends in an identity hash. The JVM backend did that for a hash table (`{"a"=[Ljava.lang.Object;@3fee733d}`) and still does for the mutex handle, which is why `.kb/mutexes.md` declares the handle opaque and unprintable. The fix is a printer arm answering a fixed tag; `.kb/hash-tables.md` has the shape.

## Why CI cannot catch this

`native-image` freezes `ImmutableCollections.SALT` at BUILD time, so the native binary is reproducible even when the JVM build is not. `CiSpecE2eTest` runs the native binary (`-Drontolisp.binary`), so the cross-backend E2E suite is blind to it, and `./mvnw test` compares behavior rather than bytes. Check reproducibility the way the bugs were found: compile the same program N times with `java -jar` and compare bytes. Compile to a FIXED output path when comparing — the emitted class name derives from the `-o` path, so two temp directories differ in every byte for that reason alone.

## Rules

- A collection whose iteration order can reach emitted bytes, generated AST, or WIT text must be insertion-ordered (`LinkedHashMap`/`LinkedHashSet`, or `LispMacroExpander.orderedMap`) or explicitly sorted. Never `Map.of`/`Set.of`.
- Say that in the TYPE, not a comment, when the collection crosses a method boundary: declare the parameter (and field) `SequencedSet`/`SequencedMap`. `Set.of`, `Set.copyOf`, `Map.of` and `HashSet` are none of those. Both bugs above were a `Set.of` passed to a `Set` parameter.
- A plain `HashMap`/`HashSet` is deterministic for a given key set (`String.hashCode` is specified), so it is safe for emission order — but only while its keys have stable hash codes. A map keyed by an object that does not override `hashCode` uses identity hashes and varies per run.
- **Filesystem order (`Files.list`, `File.listFiles`, `Files.walk`) is not an order. Sort it.** `cli/FormatCommand` does, and `eval/DistClient.addAsdDirs` now does: the quicklisp `.asd` search path was each extracted release's `Files.walk` order, so which `.asd` won when a release ships two defining one system name was the host's directory order — machine-dependent rather than run-dependent, so compiling twice on one host could not see it. Pinned by `DistClientTest#theAsdDirectoriesOfAReleaseAreSortedWhateverOrderTheHostWalkedThemIn` and `#aReleaseDefiningOneSystemTwiceResolvesToItsTopLevelAsd` (a release's top-level `.asd` beats one nested under it, because a parent path is a prefix of its children). The walk is now only the fallback — a release contributes the `.asd` files its dist index names (`.kb/dists.md`) — and the sort covers the publisher's arbitrary order in that index column too. (`eval/SourceLoader.list` is also unsorted but only feeds the RUNTIME `directory` built-in, which CL leaves unordered.)
- Generated temp names (`__mv<N>`, `__db<N>`, `__flet<N>`, `gensym`) come from counters and may legitimately renumber when the amount of macro-time evaluation changes (`.kb/flet-labels.md`, `.kb/gensym-macroexpand.md`). Renumbering across two DIFFERENT compilers is expected; renumbering across two runs of the SAME compiler is this bug.

## Two behavior changes that moved no bytes

Both verified by compiling the quicklisp-backed `examples/` programs to `.class` before and after and comparing md5.

- The `DistClient` `.asd` sort: on this host's cache four releases hold `.asd` files in more than one directory and all reordered, but no cached release ships two `.asd` files with the same basename and `AsdfSystems.locate` asks each search directory for `NAME.asd`, so nothing resolved differently and no bytes moved. The list only becomes `Ctx.systemPath` / the evaluator's search path, read by `AsdfSystems.locate` and never emitted (there is no `asdf:*central-registry*` here), so it cannot shift cross-backend output. The exposure it closes is a release shipping a top-level `foo.asd` beside a `test/foo.asd`.
- Narrowing a release's contribution to the `.asd` files its dist index names: six directories out of 94 stop being contributed, all vendored copies of ANOTHER library (`cffi-*/uffi-compat`, iterate's five `ext/*`). Two unindexed `.asd` files stay reachable because contribution is per DIRECTORY (cl-sqlite's `sqlite-tests.asd`, trivia's `trivia.benchmark.asd`) — both the release's OWN extra system, which is what makes directory granularity right. The real behavior change is only visible across two releases and is pinned without a network by `DistClientTest#aVendoredCopyDoesNotShadowThatLibrarysOwnReleaseInEitherQuickloadOrder`.
