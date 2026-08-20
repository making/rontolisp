# `defstruct` is not prunable in a bundled library

Difficulty: High

Found 2026-08-20 reviewing the `torch` package's record layout (`.kb/torch.md`).
`torch` is defun-only BY DESIGN because `LibraryDefunPruner` cannot prune a
`defstruct` a bundled library defines, so every record in it is a hand-rolled
fixed-layout general vector (`(make-array 6)` + a tag symbol in slot 0). That
costs printing (`print` on a tensor shows the raw vector, one of whose slots is a
closure), a `torch:tensorp` that allocates a list per call
(`(equal (array-dimensions x) '(6))`), and a `torch::%m-fields-slot` that
switches on the record kind to recover a slot NUMBER. `.todo/466` is the
consumer; this item removes the reason.

## Where the gate is

The pruner has two disjoint definition rules:

- **bundled** (rontolisp's own `*.lisp`, collected by `prunableNames()`,
  `LibraryDefunPruner.java:907`): keyed by `definitionName()`
  (`LibraryDefunPruner.java:~957`), which returns non-null ONLY for
  `defun`/`defparameter`/`defvar`/`defconstant`. A `defstruct` is not a
  definition to this rule, so it is a root -- and being a root it also anchors
  every name its body spells.
- **third-party** (inside a `(%begin-system "NAME")` bracket):
  `Candidates.collect` (`LibraryDefunPruner.java:613`) already handles
  `defstruct` as a keyed candidate -- struct name, every constructor (BOA ones
  by their own names), predicate, copier and accessors including `:include`d
  ones -- via `LispMacroExpander.defstructDefinedNames`. Its first statement is
  `if (!provenance.isPrunableSystem(i)) continue;`.

So the machinery exists and the gate is simply on the other side.

## The design choice, and why the obvious widening is not the answer

**(a) widen `Candidates.collect` to bundled forms.** Small: drop the provenance
guard and add the generated names to `prunableNames()`. But a `defstruct` is ONE
keyed unit -- kept iff ANY of its defined names is referenced -- so a program
that touches a single accessor keeps the constructor, the predicate, the copier,
all accessors and all their `setf` writers. Today `torch`'s record costs ZERO
definitions (the slots are inline `aref`s), so for `.todo/466` this is a size
REGRESSION, not a win. (a) is the measured fallback, not the goal.

**(b) expand the bundled `defstruct` into its defuns BEFORE the pruner runs.**
`.kb/defstruct.md`: a defstruct has no backend codegen at all -- it becomes
ordinary defuns (`point-x` = `(%obj-ref obj 0)`). If the expansion happens before
`LibraryDefunPruner.prune` (`RontoLispCli.java:776`), each accessor is an
ordinary defun that prunes individually, bundled and third-party ride the ONE
mechanism, and the keyed-candidate special case for defstruct can eventually
retire. This is the target.

The obstacle is that the expansion is not free-standing. `expandDefstruct` is
called from `LispMacroExpander.expandTopLevelDefinitions`
(`LispMacroExpander.java:18203`), which the compilers run at
`JvmLispCompiler.java:343` -- long after the CLI's pruner -- and it populates two
pieces of COMPILER-owned state as a side effect:

- `structAccessors` (accessor name -> slot index), which `expandSetf` needs so
  `(setf (point-x p) v)` compiles at all, and whose `TYPED_VECTOR_SLOT_BASE`
  encoding carries the `:type (vector (unsigned-byte N))` store path;
- `ClosRegistry`, which needs the struct type registered for `typep`/`subtypep`,
  for `#S(...)` literal folding (`StructLiteralFolder`, order-sensitive: the
  defstruct must precede the literal), and for the synthesized `print-object`
  defmethod a `(:print-object fn)` struct generates.

An early splice therefore has to hand that registration forward rather than
destroy it. Candidate shapes to evaluate, cheapest first:

1. an explicit bookkeeping form left in the stream by the early splice, consumed
   by `expandTopLevelDefinitions` (the `%begin-system` marker idiom -- provenance
   that has to ride IN the form list, for the same reason: five passes rebuild
   conses between the two points, so an identity or index side table dies);
2. re-deriving `structAccessors` from the generated accessor defun bodies -- a
   pattern match on `(%obj-ref obj <i>)`, which the typed-vector arm does not
   emit, so it is not general;
3. moving the defstruct arm ONLY (not the whole CLOS pass) into a standalone
   `LispMacroExpander` entry point that both the CLI and the compilers call, with
   the compilers' call becoming a no-op when the CLI already ran it.

Restrict the early splice to what it can do soundly and leave the rest as roots:
a defstruct whose `:include` parent is outside the candidate set, and the
`--no-gc` path (`NoGcWasmCompiler` rejects defstruct outright), are already
carved out on the third-party side and stay carved out here.

## What must not move

- The interpreter expands at evaluation time (`LispEvaluator.evalDefstruct`,
  `LispEvaluator.java:5267`) and never sees the pruner -- it must keep working
  unchanged, so this is a compile-path-only rearrangement.
- The `defmethod` specializer gate reads struct INSTANTIATOR names (the struct
  name and its constructors) to decide applicability. If a third-party defstruct
  is expanded before `Candidates.collect` runs, that gate loses the form it reads
  from. Either keep third-party defstructs unexpanded at pruner time (bundled
  only for now, which is all `.todo/466` needs) or teach the gate to read the
  generated constructor defuns -- decide explicitly, do not let it fall out.
- The `--dynamic` / `--no-prune` escape hatches must emit what they emit today.

## Acceptance

- `LibraryDefunPrunerTest` fixtures: a bundled library defstruct whose accessors
  prune INDIVIDUALLY (one accessor referenced -> the other five and the copier
  gone), the `:include` chain, the `setf` writer path, and the `--no-prune`
  identity.
- All four backends, since the change is in the shared front end: the ci-spec
  `ironclad-residue-features` case already pins struct behavior end-to-end.
- Size: bundled has NO defstruct today (`json.lisp` only mentions the word in a
  comment), so this item has no size movement of its own -- the proof is
  `.todo/466` landing without a size regression against the current
  `size-report/results/*.md`. Measure before and after there, not here.
