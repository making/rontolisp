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

A sweep of all 313 such sites in `src/main/java` (2026-07-26) found that one and no
other: every remaining `Map.of`/`Set.of` is queried, never iterated, and `am.ik.wasm`
holds no hash collection at all. Three places are one careless refactor away from the
same bug and are worth knowing about -- `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`
(a `Set.copyOf` that IS iterated, but only to accumulate an exclusion set consulted with
`contains`; the emitted wrapper order comes from the `WRAPPER_DEFS` list),
`WitImportDirective.defpackageForm(String, Set<String>)` (iterates a `Set` PARAMETER
straight into a generated `(defpackage ... :export ...)`; today's only caller passes a
document-ordered `LinkedHashSet`, but the signature promises nothing), and the
`LinkedHashSet` chains in `FreeVarAnalyzer`/`GlobalVarCollector` that mint JVM static
fields.

## Why CI cannot catch this

`native-image` freezes `ImmutableCollections.SALT` at BUILD time, so the native binary
is reproducible even when the JVM build is not. `CiSpecE2eTest` runs the native binary
(`-Drontolisp.binary`), so the whole cross-backend E2E suite is blind to it, and
`./mvnw test` compares behavior rather than bytes. Reproducibility has to be checked
the way the bug was found: compile the same program N times with `java -jar` and
compare the bytes.

## Rules

- A collection whose iteration order can reach emitted bytes, generated AST, or WIT
  text must be insertion-ordered (`LinkedHashMap`/`LinkedHashSet`, or
  `LispMacroExpander.orderedMap`) or explicitly sorted. Never `Map.of`/`Set.of`.
- A plain `HashMap`/`HashSet` is deterministic for a given key set (`String.hashCode`
  is specified), so it is safe for emission order -- but only while its keys have
  stable hash codes. A map keyed by an object that does not override `hashCode` uses
  identity hashes and varies per run.
- Filesystem order (`Files.list`, `File.listFiles`, `Files.walk`) is not an order.
  Sort it.
- Generated temp names (`__mv<N>`, `__db<N>`, `__flet<N>`, `gensym`) come from
  counters and may legitimately renumber when the amount of macro-time evaluation
  changes (`.kb/flet-labels.md`, `.kb/gensym-macroexpand.md`). Renumbering across two
  DIFFERENT compilers is expected; renumbering across two runs of the SAME compiler is
  this bug.
