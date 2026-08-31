# The interpreter re-expands a built-in macro on every evaluation

Difficulty: High

Measured while profiling a big model load on the interpreter (2026-08-31, Apple
M4 Max, JFR, 1,313 execution samples over a 50k-vertex `geom:read-obj` plus
`geom:mesh`). **15% of the samples were inside `macro.LispMacroExpander`, at
RUN time.**

`LispEvaluator.evalCons` lowers `cond`, `and`, `or`, `when`, `unless`, `do`,
`do*`, `dotimes`, `loop`, `setf`, `case`, `1+`, `zerop`, `first`, `nth` and
thirty more by calling the shared expander and evaluating the result -- on every
single evaluation of the form. A `do` loop that runs a million times expands its
own body a million times, allocating the whole expansion each round. The three
compile backends expand ONCE, at compile time; the interpreter is the outlier.

Two smaller findings from the same profile, both on the fall-through path every
ordinary function call takes:

- `LispMacroExpander.expandUnimplementedUiopMacro` runs
  `PackageRegistry.splitQualified` -- string `indexOf`, two `substring`s and a
  map lookup -- for `(char s j)` and `(+ j 1)` alike. A name with no `:` in it
  cannot be a uiop macro; the early-out is one `indexOf`. Same for
  `expandUiopMacro`. Together 4% of samples.
- `LispCons.isProperList()` walks the whole form at the top of every `evalCons`
  (3%), and `evalArgs` builds an `ArrayList` with no capacity hint (the leaf
  frame `ArrayList.add` was 14% of samples, though allocation is attributed
  there too).

## Why this is High and not Low

A memo has to be keyed on the form, and a form is a `LispCons`, which is
MUTABLE (`rplaca` / `rplacd` / `(setf (car x) ...)`). Caching an expansion on
the cell -- a field, or an identity map -- is only sound if no program rewrites
its own source between evaluations. The compile backends cannot support that
either, so the semantics being given up are already unavailable everywhere else;
but it is a real semantic change and it should be stated in `.kb/`, not slipped
in.

Not every expansion may be cached, either. Some read evaluator state and MUST
re-expand: `error` / `cerror` / `warn` / `signal` (the `restartRuntimeLoaded`
gate -- there is already a comment in `evalCons` saying the re-expansion is what
makes a later signal see the handler hook), `make-instance` / `change-class`
(the `closRegistry`), `setf` (user expanders), and the `print` family (which
re-decides `print-object` routing per call). The safe set is the purely
syntactic rewrites, and it has to be enumerated deliberately rather than
assumed.

## Where to measure

The profile above is reproducible: any interpreted loop over a few hundred
thousand iterations. `.kb/hot-path-method-size.md` has the previous round of
`evalCons` work (the `HugeMethodLimit` split, worth 2.7x) and is the file this
belongs beside.
