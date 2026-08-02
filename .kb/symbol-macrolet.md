# `symbol-macrolet` — one shared shadow-aware substitution, all four backends

`(symbol-macrolet ((name expansion)...) body...)` is lowered by
`LispMacroExpander.expandSymbolMacrolet` into a `progn` of the body with every FREE
reference to a bound name replaced by its expansion — the same shared-normalizer shape as
`cond`/`with-slots`, so the interpreter, the JVM compiler, and both WASM backends get
identical semantics from one walk. Do NOT add per-backend symbol-macro handling.

Consumers (why this exists): trivia level1/level2 `match` expansions emit
`symbol-macrolet` for place patterns and multi-matchers; dbi's `driver.lisp` assigns
`(setf auto-commit ...)` through a `(slot-value conn 'auto-commit)` expansion; mito
`core/type.lisp`. Every trivia user (sxql, mito) executes these at runtime.

## The walk (`substituteSymbolMacros`)

- **Shadow-aware**, unlike the lite `substituteSymbols` behind `with-slots` (which
  deliberately keeps its inner-binding-still-substitutes behavior — do not merge them
  without owning that semantic change). `let`/`let*` (init scoping differs: parallel vs
  sequential), `do`/`do*`, lambda-likes (`lambda`/`defun`/`defmethod`/`async-*`, incl.
  `&optional`/`&key` defaults and supplied-p names), `flet`/`labels` definition lambda
  lists, `dolist`/`dotimes` (result form IS in var scope), `multiple-value-bind`,
  `destructuring-bind` (every pattern symbol shadows, conservative), `with-slots`/
  `with-accessors` entries, `handler-case`/`restart-case` clause variables, the
  `with-open-*`/`with-*-string` spec variable, and nested `symbol-macrolet` all remove
  their bound names from the active map for their scope.
- **Data positions kept verbatim**: quote, declare/declaim/proclaim, `#'name` (Lisp-2;
  only a literal `#'(lambda ...)` is walked), call heads, `case`/`typecase`-family keys
  (typecase is walked STRUCTURALLY because its expansion needs the class registry the
  shared walk does not have — same reason as `FreeVarAnalyzer`), `go` tags, `block`
  names, `tagbody` atom tags, `the`/`check-type`/`assert` type-ish tails, `eval-when`
  situations, definition bodies that never see the lexical environment (`defmacro`,
  `macrolet` definitions) and the class family (`defclass`/`defstruct`/`defgeneric`/
  `deftype`/`define-condition`/`defsetf`-family) — a `defun` inside the body DOES
  substitute (its params shadow).
- **Writes**: `(setq name v)` whose substituted target is a cons is rewritten to `setf`
  (mirrors the `with-slots` setq rewrite); a textual `setf` needs nothing — substituting
  the place position hands the real place to the ordinary setf machinery
  (`(setf (slot-value ...))` is a supported place, which is what dbi needs).
  `multiple-value-setq`/`psetq` with a symbol-macro target route through their lowering
  first; otherwise their targets are data.
- **Lowered-first forms**: `loop` and `prog`/`prog*` have no stable surface shape, so
  they are expanded one step (`expandLoop`/`expandProg`) and the expansion is walked —
  the SpecialVarCollector pattern. Everything else is walked structurally so the
  compilers' optimized shapes (`dotimes` unboxing etc.) survive.
- **Replacement re-substitution**: a substituted expansion is walked again with the
  just-replaced name removed — sibling chains (`((a b) (b 42))` reads 42) terminate, a
  self-referential expansion substitutes once instead of looping (CL would error; lite).
- **Declarations directly in the body are dropped**: trivia emits
  `(declare (type ...))`/`(declare (ignorable ...))` naming the macro symbols, which no
  longer exist after substitution. Deeper declares are kept verbatim.

## User macros: the hook seam

The walk must expand a user macro BEFORE substituting into it (macro arguments may be
data — trivia match patterns; the expansion is code). `expandSymbolMacrolet(cons, hook)`
takes a `LispMacroExpander.UserMacroHook`:

- **Interpreter**: `LispEvaluator.symbolMacroUserMacroHook` (one-step
  `expandUserMacro`), wired in the `evalConsRareOperator` half of the dispatch (the hot
  half sits near HotSpot's HugeMethodLimit — `LispEvaluatorHotMethodSizeTest`).
- **Compile path**: hook is null — `UserMacroExpander` has already expanded user macros
  everywhere, including inside the binding EXPANSION forms (its `expandAll` has a
  SYMBOL-MACROLET pattern-keeping case: names stay, expansions + body are walked; without
  it a macro call spliced in by the later substitution would reach the compilers, which
  have no macro table).

Wiring: evaluator case + `Jvm/WasmExprCompiler.compileCons` cases + `FreeVarAnalyzer`
(expand-before-walk, or binding names read as free vars) + `rewriteLocalCalls` (binding
names kept like `let`, or an flet-local name reused as a symbol-macro name would be
rewritten to a funcall) + `expandBuiltinMacro` (so `macroexpand-1` works and
`SpecialVarCollector` sees lets inside the body).

## Deliberate scope cuts (re-evaluation triggers)

- **`define-symbol-macro` (global) is NOT implemented** — grep found zero uses on the
  mito closure. If a library needs it, it cannot ride this walk as-is (there is no
  enclosing form to expand); it would need a program-wide pass on the compile path and an
  environment-level marker in the interpreter.
- The interpreter re-runs the substitution on every evaluation of the form (same
  re-expansion cost model as every built-in macro there, `.todo/182`); the walk is linear
  per evaluation, not per iteration of loops inside the body. Measured 2026-08-02: a
  defun whose body is a 2-form symbol-macrolet, called 100k times on the interpreter
  (JVM jar), 809 ms vs 514 ms for the equivalent let-based body — ~1.6x, no quadratic
  blowup. If trivia match loops make this the bottleneck, memoize per cons identity like
  the compiler-macro memo, or fold into a `.todo/182` fix.
- `defmethod` `&optional`/`&key` defaults are kept unwalked (specializer/default
  ambiguity before the first lambda-list keyword); a symbol-macro reference inside a
  defmethod default default is not substituted.
- A symbol-macro name that is also a special variable is substituted anyway (CL signals
  `program-error`; lite does not check).

Pinning tests: `LispEvaluatorTest#evalSymbolMacrolet*`,
`JvmLispCompilerTest#compileAndRunSymbolMacrolet*` (incl. the user-macro-emits-it shape),
`WasmLispCompilerIntegrationTest#symbolMacroletForms`, ci-spec
`symbol-macrolet-substitution-and-setf`.
