# The interpreter expands a built-in macro once per call site

**Invariant: the interpreter's built-in macro arms expand a form ONCE per source
occurrence (memoized by cons identity), exactly like `defmacro` calls
(`userMacroExpansions`) and like the three compile backends, which expand once at
compile time. The macros whose expansion reads evaluator state are enumerated below
and stay re-expanded on every evaluation; an arm not on that list may only join the
memo if its expander takes nothing but the form (plus compile-time-constant flags).**

Measured on a JFR profile of a 50k-vertex `geom:read-obj` + `geom:mesh` (2026-08-31,
Apple M4 Max): 15% of execution samples were inside `macro.LispMacroExpander` at run
time -- a `do` loop body re-expanded its `when`/`incf`/`1+` forms every iteration,
allocating the whole expansion each round.

## The safety rule, and why it is structural

`LispMacroExpander` holds NO mutable static state (every field is a `static final`
constant; the generated variables are fixed names like `__cond`, not gensyms), so an
expander whose only inputs are the form and constant flags is a pure function of the
form's syntax. Those are the memoized arms. Everything the three compile backends
already evaluate repeatedly over a single expansion -- every defun body -- exercises
the same discipline, so an expansion that secretly needed per-evaluation freshness
would already be broken on three of four backends.

## The arms that MUST re-expand per evaluation (and why)

| operator(s) | state the expansion reads |
| --- | --- |
| `error`, `cerror`, `warn`, `signal` | `restartRuntimeLoaded`: the re-expansion is what makes a signal AFTER the restart runtime loads see the handler hook (comment at the `error` arm); also `closRegistry` |
| `make-instance`, `change-class`, `make-condition`, `define-condition` | `closRegistry` (a class defined between two evaluations changes the expansion); `change-class` also resolves its designator in the live env |
| `handler-bind` | `closRegistry` |
| `setf` | user `defsetf`/`define-setf-expander` expanders, user macro places, `(setf (macro-function ...))` aliasing, lazy prelude setf places |
| `print`, `princ`, `prin1`, `princ-to-string`, `prin1-to-string`, `write-to-string` | per-call `print-object` routing (`closRegistry`) and the live `*print-case*` gate |
| `typep`, `typecase`, `etypecase`, `ctypecase`, `streamp`, `coerce` (packed lowering) | `closRegistry` |
| `flet`, `labels` | `preExpandLocalMacros` consults the live user-macro table |
| `symbol-macrolet` | the user-macro hook consults the live table |
| `read`, `floor`/`ceiling`/`round`/`truncate`, `reduce`, `sort` | partial (nullable) lowerings that fall through to the ordinary call; the probe is one cheap shape check, left alone |

Place-writing macros (`push`, `pop`, `incf`, `decf`, `pushnew`, `remf`, `psetf`,
`rotatef`, `shiftf`) ARE memoized: they lower to a `(setf place ...)` form, and the
`setf` arm re-expands per evaluation, so a user setf expander defined later is still
seen.

## The semantic change, stated

The memo is keyed on the `LispCons`'s identity, and a `LispCons` is mutable
(`rplaca`/`rplacd`/`(setf (car x) ...)`). A program that rewrites a macro FORM ITSELF
between evaluations now keeps evaluating the first expansion: the form's shape is
frozen at first evaluation. What is NOT frozen: a mutation reaching a subform's
VALUE through shared structure behaves as before, because the expansion splices the
original subform objects (`.kb/quoted-data.md` has the same sharing stance for
quoted data). The compile backends never supported self-rewriting source -- they
expand once at compile time -- and `defmacro` call sites were already memoized, so
nothing is given up that exists anywhere else. Grep of the whole test suite,
`ci-spec.yaml` and `examples/` (2026-08-31): every `rplaca`/`rplacd`/`(setf (car`
site mutates runtime DATA lists; none rewrites a live form between evaluations.
Pinned by `LispEvaluatorTest.aRewrittenBuiltinMacroFormKeepsItsFirstExpansion`.

Threading follows `userMacroExpansions`' stance verbatim: guarded by its own
monitor, never held across an expansion; two threads racing on one call site both
expand and the last write wins -- a wasted expansion, not a wrong answer. The map is
bounded by `EXPANSION_MEMO_LIMIT` like the other three identity memos; past the
bound the arm simply recomputes, which is the pre-memo behavior.

## Measurement

Interpreted loops (2026-08-31, Apple M4 Max, exec jar, medians of repeated
`(time ...)` rows after a warm-up pass; one defun per row; `bench-control` --
`while`/`setq`/`mapcar`/`lambda`, no memoized arm in its loop -- ran untouched
in every run):

| row | before | after | |
| --- | --- | --- | --- |
| control | 224 ms | 220 ms | unchanged |
| `case` of 10 clauses in a `dotimes` body, 500k iterations | 232 ms | 101 ms | **2.3x** |
| `loop for/below/collect` of 8, 50k evaluations | 213 ms | 130 ms | **1.6x** |
| `cond` of 12 clauses, 200k iterations | 237 ms | 203 ms | 1.17x |
| `dotimes` + `when`/`evenp`/`incf`/`1+`, 200k iterations | 95 ms | 92 ms | within noise |

The win scales with the SIZE of the expansion -- `case` and `loop` build big
trees per re-expansion, a `when` builds four cells -- which matches the profile's
shape: the 15% of samples came from geom.lisp's big `loop`/`do` defuns.
(`geom:read-obj`/`geom:mesh` themselves went interpreter-native the same day --
`eval/GeomKernels`, `.kb/geom.md` -- so the profiled workload no longer reaches
the interpreted reader that was measured; on the natives the load is ~110 ms for
a 50k-vertex OBJ and the memo trims ~10%.)

## Pinning tests

- `LispEvaluatorTest.aRewrittenBuiltinMacroFormKeepsItsFirstExpansion` -- the stated
  semantic change, deliberately.
- `LispEvaluatorHotMethodSizeTest` -- the arms' conversion must not push either
  `evalCons` half over the 8000-bytecode cliff (`.kb/hot-path-method-size.md`).
