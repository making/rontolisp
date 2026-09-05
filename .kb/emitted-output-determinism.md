# Emitted output is a function of the program, not of the JVM run

Compiling the same source with the same build twice must produce byte-identical output, on every
backend. Byte-identity is how changes are shown to leave unrelated programs alone
(`.kb/library-defun-pruning.md`).

## The trap: `Map.of` / `Set.of` iterate in a per-process-random order
`java.util.ImmutableCollections` seeds `SALT` from `System.nanoTime()` at class-init, so `Map.of`,
`Map.ofEntries`, `Set.of`, `Map.copyOf`, `Set.copyOf` iterate differently between JVM runs. Fine
for `contains`/`get`; a live bug the moment ITERATION order reaches emitted output. Two
occurrences, both fixed:

- `LispMacroExpander.SUBTYPEP_PARENTS` -> `subtypepUniverse` -> `subtypepAncestorTableForms`,
  emitted as `%SUBTYPEP-ANCESTOR-TABLE`. Now `LispMacroExpander.orderedMap`; pinned by
  `LispMacroExpanderTest#theRuntimeSubtypepTableIsEmittedInLatticeDeclarationOrder`.
- `SpecialVarCollector`'s three standard-stream specials passed as a `Set.of` to
  `collectDynamicallyBound`, whose `progv` over-collection arm is the order both backends mint
  globals in — eight compiles of one six-line program gave three distinct `.class` and three
  distinct `.wasm`. Fixed by TYPING the order: `SEEDED_STREAM_SPECIALS` is an unmodifiable
  `SequencedSet` and the parameter is declared `SequencedSet`, which no `Set.of`/`Set.copyOf`
  satisfies. `WitImportDirective.defpackageForm` took the same tightening. Pinned by
  `SpecialVarCollectorTest#aProgvProgramSeedsTheStreamSpecialsInAFixedOrder`.

`ClosRegistry` is the model to copy: every map a `LinkedHashMap`, `classes()` documented "in
definition order".

## Sweep status
Every such site in `src/main/java` walked twice (313, then 384 sites; 0 in `src/web/java`).
**There is no third emission-reaching site.** `am.ik.wasm` holds no hash collection at all. Five
are one careless refactor from the same bug: `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`,
`WasmComponentBuilder.FIXED_BLOCK_IFACES`, `WasmSocketsRewrite.SYNC_DISPATCH`,
`UiopLibrary.RESOURCES`, and the `LinkedHashSet` chains in `FreeVarAnalyzer`/`GlobalVarCollector`.
A sixth was closed: `LibraryDefunPruner`/`ConstantCaseArmPruner.spellingsOf` now share
`PackageRegistry.spellings`, a fixed-order `List.of` (external `pkg:member` first, then internal
`pkg::member`); pinned by
`PackageRegistryTest#bothSpellingsOfAQualifiedNameComeBackInAFixedOrder`.

Parameters that genuinely carry emission order are all typed `SequencedSet`:
`SpecialVarCollector.collectDynamicallyBound`, `JvmDynVarRuntimeBuilder.build`,
`JvmRawGlobals.collect`, `WitComponentTypeEncoder.encode`, `WitImportWorldEmitter.iface`,
`WitImportDirective.defpackageForm`.

## Rules
- A collection whose iteration order can reach emitted bytes, generated AST or WIT text must be
  insertion-ordered (`LinkedHashMap`/`LinkedHashSet`/`orderedMap`) or explicitly sorted. Never
  `Map.of`/`Set.of`.
- Say it in the TYPE, not a comment, when the collection crosses a method boundary: declare the
  parameter and field `SequencedSet`/`SequencedMap`. Both bugs above were a `Set.of` passed to a
  `Set` parameter.
- A plain `HashMap`/`HashSet` is deterministic for a given key set (`String.hashCode` is
  specified) — but only while its keys have stable hash codes; identity hashes vary per run.
- **Filesystem order (`Files.list`, `File.listFiles`, `Files.walk`) is not an order. Sort it.**
  `cli/FormatCommand` and `eval/DistClient.addAsdDirs` do; the latter's quicklisp `.asd` search
  path was host-directory order, so it varied per HOST rather than per run. Pinned by
  `DistClientTest#theAsdDirectoriesOfAReleaseAreSortedWhateverOrderTheHostWalkedThemIn`,
  `#aReleaseDefiningOneSystemTwiceResolvesToItsTopLevelAsd` (a top-level `.asd` beats a nested
  one) and `#aVendoredCopyDoesNotShadowThatLibrarysOwnReleaseInEitherQuickloadOrder` (a release
  contributes the `.asd` files its dist index names, per DIRECTORY — `.kb/dists.md`).
  `eval/SourceLoader.list` is unsorted but only feeds the runtime `directory` built-in, which CL
  leaves unordered.
- Generated temp names (`__mv<N>`, `__db<N>`, `__flet<N>`, `gensym`) may legitimately renumber
  when macro-time evaluation changes (`.kb/flet-labels.md`, `.kb/gensym-macroexpand.md`), and
  across two DIFFERENT compilers; renumbering across two runs of the SAME compiler is this bug.
- Sibling invariant: what the emitted program PRINTS must not vary either. The way to break it is
  to let a host `toString` reach a printer (the default ends in an identity hash) — the JVM
  backend did that for a hash table and still does for the mutex handle, hence
  `.kb/mutexes.md`'s opaque/unprintable handle; the fix shape is in `.kb/hash-tables.md`.

## Why CI cannot catch this
`native-image` freezes `ImmutableCollections.SALT` at BUILD time, so the native binary is
reproducible even when the JVM build is not; `CiSpecE2eTest` runs the native binary and `./mvnw
test` compares behavior, not bytes. Check it the way the bugs were found: compile the same program
N times with `java -jar` and compare bytes, to a FIXED output path (the emitted class name derives
from the `-o` path).
