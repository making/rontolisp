# The ci-spec corpus class is near the 65535 constant-pool ceiling

`JvmClassShakerCorpusTest` compiles the ENTIRE `ci-spec.yaml` into one JVM
class. During the deep-learning-from-scratch work (2026-07-12) that class
crossed the JVM class-format limit of 65535 constant-pool entries
(~65,900): the u2 count silently wrapped and produced a corrupt class that
`javap` could not even parse, surfacing as a misleading
"JvmClassShaker: unsupported field attribute" (the parser desynced long
before the fields section).

Two things landed then:

- `ConstantPool.toByteArray()` now throws a clear
  "constant pool overflow: N entries exceed the JVM class-format limit"
  instead of wrapping -- any future overflow fails loudly at compile time,
  in any program, not just the corpus.
- The two new ci-spec cases were slimmed (the wide per-function coverage
  lives in the unit tests), buying back a few hundred entries.

But the headroom is thin (<1% free) and shrinks every time `linalg.lisp`
or another spliced library grows -- the pool is dominated by the FULL
spliced library defuns (every defun costs a method name/descriptor/ref
plus its string and symbol constants), which the `--optimize` shaker only
removes AFTER the complete class has been serialized once.

Ideas, roughly in order of appeal:

1. **AST-level pruning of spliced libraries**: before Pass 1, drop spliced
   defuns unreachable from the user program (a call-graph walk over the
   qualified names; `linalg.lisp` alone is ~90 defuns of which a typical
   program uses a handful). Fixes the root cause for every large program,
   not just the corpus, and shrinks compiled classes generally. Needs care
   with indirect references (`#'linalg:foo`, `funcall` of computed names
   -- the `--dynamic` story) and with the interpreter/compiler behavior
   parity (the interpreter lazy-loads the whole library).
2. **Split the corpus test into two classes**: the ci-spec cases share
   global state IN ORDER, so a split must pick a boundary where no later
   case reads earlier globals -- fragile, and CiSpecE2eTest (the native
   driver) would still compile one program per backend... (it does today
   and works because WASM has no such pool; only the JVM class hits it).
3. **Constant-pool slimming**: dedupe descriptor strings more
   aggressively, shorten generated method names (`linalg$colon...`
   manglings are long utf8s). Diminishing returns.

Until one of these lands: keep new ci-spec cases LEAN (a handful of
prints; put breadth in unit tests), and if the overflow error fires on
`JvmClassShakerCorpusTest`, trim or consolidate recent cases.
