# Library defun pruning (AST tree-shaking) + constant-pool deduplication

Two compile-path size mechanisms that landed together for todo-118 (the ci-spec
corpus class had reached 65,520 of the 65,534 constant-pool ceiling):

1. **`LibraryDefunPruner`** (`am.ik.rontolisp.eval`): an AST-level pre-pass that
   drops spliced library definitions unreachable from the user program, before
   the compilers' Pass 1.
2. **`ConstantPool` deduplication** (`am.ik.jvm`): the pool builder now returns
   the existing entry when an identical constant is added again.

## Why both

The library pre-passes (`LinalgLibrary.process` &c) splice each library
**whole** -- linalg.lisp alone is ~102 defuns + 3 defparameters, vec.lisp 70,
json.lisp 29+2 wrappers, url.lisp 22 -- and the `--optimize` shakers can only
trim after the full class/module has been serialized once. Pruning fixes that
root cause for every program that uses a library. But measurement on the corpus
showed the pool was dominated by something else entirely: **duplicate
entries** (the builder never deduplicated -- 30,224 of 64,812 entries were
byte-identical repeats; the Utf8 `"t"` alone appeared 4,646 times). Corpus
numbers, plain (un-optimized) compile:

| stage | constant-pool entries |
|---|---|
| before todo-118 | 65,520 / 65,534 (14 free) |
| + LibraryDefunPruner | 64,812 |
| + ConstantPool dedup | **5,647** (91% headroom) |

The corpus only gains ~700 entries from pruning because ci-spec is the feature
catalogue -- it genuinely reaches 163 of the 230 spliced definitions. A typical
program keeps a handful, so the pruner's real win is ordinary artifact size
(and WASM module size, where there is no constant pool but every dead defun
still costs code bytes).

## The pruner

`LibraryDefunPruner.prune(program)` runs at the END of the compile-path splice
chain. Call sites:

- `RontoLispCli.compileToFile` -- after the conditional `VecLibrary.process`,
  skipped under `--dynamic` and `--no-prune` (both flags in `CliOptions`;
  `--no-prune` is in `noValueKeys`, remember the todo-92 dead-flag lesson);
- `RontoPlayground.compileJvm`/`compileWasm` (web playground, `-Pweb`);
- `JvmClassShakerCorpusTest` / `WasmTreeShakerCorpusTest` (they mirror the CLI
  pipeline; the shakers now decode exactly what the CLI emits -- the per-backend
  unit tests keep full-library codegen coverage).

NOT in: the interpreter (lazy-loads libraries whole at runtime), the
per-library compiler unit tests (they deliberately compile the full splice),
`AsdfLibraryE2eSupport` (splices only prelude/usocket -- a provable no-op).

**Prunable set**: top-level `defun`/`defparameter`/`defvar` forms whose name is
defined by linalg, vec, json (+ its `#'` wrapper defuns), url, or the prelude
(`equalp`/`string<`/... -- note `LispPreludeLibrary.process` selects the entries
to splice to a fixpoint, so a prelude defun pulled in only by ANOTHER prelude
defun, like the string comparison family's `%string-compare`, is present here
and stays reachable through the kept caller). Collected once from the libraries' `forms()` (see the
package-private `JsonLibrary.wrapperForms()` / `LispPreludeLibrary.names()`
accessors). **usocket is excluded entirely**: its `with-*` built-in macros
(`LispMacroExpander`) synthesize `usocket:socket-close` /
`usocket::%usock-guard` / `%usock-resignal` calls that are not textually
present in the pre-expansion AST, and it is only ~13 defuns.

**Reachability (carve-out semantics, user-confirmed 2026-07-12)**: a reference
is ANY occurrence of the name anywhere in a kept form -- operator position,
argument position, quoted data, `(function ...)` -- PLUS any string literal
containing a prunable name as a substring (keeps `(intern "linalg:norm")` /
`read-from-string` idioms working). Fixpoint from the roots = every top-level
form that is not a prunable library definition (the user program is never
pruned). One synthetic edge is hardcoded: `vec:aref` also references
`vec:aset`, because `(setf (vec:aref ...))` expands to `vec:aset`
(`LispMacroExpander.expandSetf`) AFTER the pruner runs -- the only place in the
whole codebase where a library-qualified name is synthesized rather than
written (verified by grep; re-verify if a new built-in macro expansion ever
emits a `linalg:`/`vec:`/`rontolisp::%json`/url/prelude name).

**Safety valves**:

- analysis runs on a `PackageResolver.resolveProgram` copy (index-aligned 1:1),
  so `in-package`/nickname/bare-exported spellings match the libraries'
  canonical fixed-point names; removal happens by index on the ORIGINAL list,
  so surviving forms are byte-identical (the `PackageResolverTest` fixed-point
  invariants are untouched);
- bail (prune nothing) when a runtime `load`/`require` survives the
  `LoadInliner` (loaded code can call anything by name), when resolution throws
  (the compiler reports it), or when no prunable definition is present;
- the CLI skips the pass under `--dynamic` (late binding resolves names at
  runtime) and `--no-prune` (pure escape hatch, no other codegen change).

**Documented limitation**: a program that forges a library function's qualified
name at runtime from computed strings (no textual/string-literal occurrence)
and invokes it via the eval family (`eval`/`apply`/computed `fboundp`) gets the
ordinary "undefined function" error for a pruned function. `--no-prune` or
`--dynamic` restores every library definition. This mirrors standard AOT
tree-shaking; the failure is loud, never silent wrong output. Deliberately NOT
a bail condition: `eval`/`apply`/`boundp`/... occurrences do not disable
pruning (the ci-spec corpus uses all of them and still prunes) -- quoted
symbols and string literals are counted as references instead.

Pinned by `LibraryDefunPrunerTest` (closure, RNG-seed defparameters,
vec:aset edge, `#'`/quoted/string references, in-package resolution, load
bail, usocket exclusion, order preservation) and the corpus tests' behavior
identity + constant-pool headroom guard (<= 52,000).

## Scope: rontolisp's OWN libraries only, and why the "dead top-level `let`" rule was rejected

`prunableNames()` is the whole scope decision: linalg, vec, json (+ its `#'`
wrappers), url and the prelude. **An ASDF-spliced third-party tree is never
pruned** -- its definitions are not in the prunable set, so they are roots, and
they additionally keep alive whatever rontolisp-library names they mention.

A rule was proposed for the top-level `(let ((x ...)) (defun ...))` idiom -- delete
the block when every definition inside it is dead -- on the premise that the pruner
"already knows" uax-15's `get-illegal-char-list` is unreachable. **The premise is
false and the rule was measured and rejected (2026-07-26).** Three findings, kept
here so the idea is not re-proposed:

- The pruner cannot know it. `definitionName` returns null for a `let`, so a defun
  inside one is not a definition at all; and uax-15 is third-party, so nothing in
  that file was ever in scope. The motivating block is also gone -- `eval/Uax15Tables`
  replaces that whole `let` at the source level (`.kb/asdf.md`).
- Across every loadable library (vendored + the quicklisp cache) the idiom occurs 14
  times and only TWO blocks are dead: cl-ppcre's and cl-who's `hyperdoc-lookup`,
  whose bodies are a `loop ... being the external-symbols` that rontolisp already
  lowers to an empty iteration. Deleting both is worth **771 bytes of a 1.55 MB
  module (0.05%)**. Worse, neither passes the existing purity judgment
  (`UserMacroExpander.isPure` rejects `loop` and any user call), so collecting the
  771 bytes would first require widening that allow-list.
- What IS worth measuring is the premise, not the rule: extending the pruner to
  third-party trees. Removing cl-ppcre's 13 statically dead top-level forms by hand
  cut the wasm module 3.0% (46.7 KB) and the `.class` 3.2%, and 20-23% of the
  SOURCE LINES of cl-postgres and the ironclad slice are unreachable. `--optimize`
  cannot reach any of it -- the WASM/JVM shakers root at exports and every compiled
  Lisp defun is reachable from the funcall dispatcher, so the reduction they achieve
  is the same with and without the dead defuns present. That extension needs a
  provenance marker from `LoadInliner`, a trie for the string-literal carve-out (the
  prunable set grows from ~230 to ~3,000 names) and a conservative
  CLOS/`defsetf`-stay-root line; it is filed separately.

## The constant-pool dedup

`am.ik.jvm.ConstantPool.add` keys every entry by its serialized bytes
(tag + payload) in a `HashMap` and returns the existing `Constant` on a hit.
Because a composite entry (Class/String/NameAndType/Field-/Methodref) embeds
the u2 indexes of its already-deduplicated components, structural sharing
cascades -- after Utf8 dedup, identical Methodrefs become byte-identical and
collapse too (that cascade is why the corpus dropped 91%, not just the 43%
that raw Utf8 duplicates accounted for). `addLong`/`addDouble` pass a
`twoSlots` flag into the shared `add` so a cache hit does not double-count the
second slot; doubles key by `doubleToLongBits` (so `-0.0` and `0.0` stay
distinct entries and NaNs share their canonical serialized pattern --
serialization itself is unchanged from before, only the sharing is new).
Duplicates are legal in the class format, so dedup is purely a size
optimization and needs no flag. Every compiled `.class` shrinks;
`JvmClassShaker` still works unchanged (its compaction is sharing-agnostic).

Nothing in `am.ik.jvm`/`codegen.jvm` predicts "the next index will be
`size()+1`" (grep-verified), and `Constant` is immutable, so returning a shared
instance is safe.
