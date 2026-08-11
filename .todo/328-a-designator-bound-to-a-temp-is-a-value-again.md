# A designator bound to a temp is a function value again

Difficulty: Medium

`(mapcar #'f lst)` now compiles to a direct call on both compile backends, so `f` never
becomes a first-class value and the arity ladder carries no case for it
(`.kb/optimize-dead-code-elimination.md`, "A designator the compiler can READ never enters
`valueFuncIds`"). An expander that BINDS the designator first undoes that: the binding
materializes the closure, the ladder gets its case back, and everything that case reaches
is pinned for `--optimize` again.

The binders, all in `LispMacroExpander`:

- `expandMap` -- `(let ((__map_fn #'identity)) ... (funcall __map_fn (elt s i)))`. The
  `coerce` lowering emits `(map 'list #'identity x)`, so EVERY program that coerces a
  string carries it.
- `expandMapFamily` -- `maplist` / `mapcon` / `mapl` bind `#fn` the same way (the three
  members without a per-backend inline emitter, `.kb/map-family.md`).
- `expandEverySomeFamily`, and any other expander that names a designator to avoid
  re-evaluating it.

## What it is worth, and what it is not

Leaving the literal AT the funcall site in `expandMap` alone was measured: **75 bytes** on
the `zlib` rows. Not nothing, but the shape it was measured on (`(map 'list #'make-crd
...)` in chipz, `#'identity` from `coerce`) has cheap callees; a program whose `map`
designator names something big, or whose ladder is otherwise dead, is where this pays.
Measure before choosing a size for it.

That per-expander rewrite was **declined** as the way to get it, for two reasons that are
the real design input here:

- the interpreter would evaluate the designator once per element instead of once;
- a designator naming an UNDEFINED function would stop signalling when the sequence is
  empty, because the loop body never runs -- an interpreter-only behavior change.

## The shape to build instead

Do it in the backends, where the use sites are already known: **a `let`/`let*` binding
whose init is a literal designator (`compiler.FunctionDesignators.literalName`), which is
never assigned, and whose every use is a `funcall`/map-family/`reduce`/`sort` function
position, is propagated to those uses and the binding dropped.** Then
`Wasm/JvmDesignatorCall` resolves them exactly as a written-out literal, one rule covers
every expander that binds (present and future), and neither interpreter cost above
appears -- the interpreter does not see the rewrite at all.

Watch:

- **every use.** One use as a plain VALUE (passed on, stored, returned) keeps the binding
  and must keep the ladder case, or the value stops resolving.
- **assignment.** A `setq` of the binding anywhere disqualifies it.
- **shadowing.** An inner binding of the same name, and the `flet`/`labels` rewrite that
  already turns local functions into variables (`.kb/flet-labels.md`) -- a `__FLET*`
  variable holds a LAMBDA, not a literal designator, so it is out of scope by construction.
- **`WasmLetCompiler`'s fusion registry** reads `__FLET*` bindings for integer-tree
  substitution (`.kb/wasm-int-fusion.md`); a second binding-level rewrite has to leave
  that one alone.
- byte identity is NOT expected -- this changes emitted code -- so the four-backend
  verification is the gate.

## Deliverable

A pin that a let-bound literal designator emits the same direct call the written-out
literal does and that a bound designator with a non-funcall use still dispatches, measured
`zlib` + Worker rows, `./mvnw test` and the native `CiSpecE2eTest` green.
