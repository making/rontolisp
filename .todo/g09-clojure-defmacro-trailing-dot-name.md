# g09: defmacro accepts a trailing-dot name with no class base (`?.`)

shcloj4's `examples.utils` defines `(defmacro ?. ...)` (line 37 of the book file). The oracle accepts the
definition (single-arity use answers, `macroexpand-1` spells `(. hi toString)`; the
multi-arity recursion `(.? ...)` fails on the oracle too, out of scope). rontolisp
refuses the definition (`?. cannot name a macro: it names an interop or qualified
form`, `ClojureMacroLowering.defmacroForms`), which blocks the whole
`examples.sequences` namespace and its 6 test namespaces.

Root cause: the definition check rejects every trailing-dot name, while call sites
(`ClojureInteropLowering.interopCall`) treat `X.` as a construction only when `X`
is a class spelling (`isClassSpelling`). `?` is none, so a `(?. ...)` call already
falls through to the macro table (`ClojureLowering.lowerInner` tries the program
macro first) -- only the definition check over-refuses.

Fix: share one construction-head predicate (`X.` with a class-spelling `X`) between
the call site and the definition check. Leading-dot and `/` names stay refused
(conservative: no accepted program changes shape, this was a hard refusal).

Acceptance:
- `(defmacro ?. ...)` + single-arity use + `macroexpand-1` oracle-identical on all
  four backends (new `clojure-spec.yaml` case + `ClojureLoweringTest` pin).
- shcloj4 `examples.test.sequences` (`Ran 6 tests containing 14 assertions. 0
  failures, 0 errors.`, run from `code/` as in the g09/g10 probes) green on the
  interpreter and the JVM.
- Full suite green (merge `origin/develop` right before the run, one maven run).
