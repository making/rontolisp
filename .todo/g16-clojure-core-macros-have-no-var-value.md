# g16. `clojure.core` macros have no first-class var value

Difficulty: Medium

In the oracle, a macro is an ordinary var: its name reads as a value, and
`deref`/`@` of `(resolve 'when)` hands back the fn. Here the builtin core
macros live only in the lowering's head dispatch, so both fail while the
program lowers (probe, develop jar, 2026-10-14):

```clojure
(def x when)                        ;; unknown name: when
(def x (deref (resolve 'when)))     ;; Can't take value of a macro: #'clojure.core/when
(def x (var when))                  ;; OK -- the var reference itself lowers
```

About 155 of the 679 `clojure.core` publics are macros in this situation
(`when`, `when-let`, `cond->`, `->`, `doseq`, `future`, `with-open`, ... the
head-supported set minus the fns). A program that passes a macro var around --
`(map (resolve 'when) ...)`, `@(resolve 'when)`, `(resolve 'when)` stored and
derefed -- refuses today; the same program runs on `clj`.

`ClojureLowering.valueOf` is the single dispatch to extend: a core-macro name
with no value arm should answer the macro's expander as a value, the way the
program-defined macros already exist as `ctx.macros` entries
(`ClojureMacroLowering.macroCall` reads them) -- the expander is a real fn
value the lowering can hand out. `deref` of a macro var is the same answer
through `ClojureVarLowering`'s value path, which is where the
`Can't take value of a macro` refusal is thrown.

## Definition of done

- `(def x when)`, `@(resolve 'when)` and `(deref (resolve 'cond->))` lower and
  run on all four backends, pinned by tests.
- The value is the expander fn: calling it `(x '(when true 1))`-shaped (form
  in, expansion out) matches the oracle's macro fn contract as far as the
  existing `macroexpand` machinery observes it.
- Program-defined macros (user `defmacro`) keep today's behavior; if they
  already pass a value through, add the pinning test, do not regress it.
- The `.todo/g15` presence probe re-run reports no `unknown name` for any of
  the 679 in value position either.
