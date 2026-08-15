# `macro-function` and `special-operator-p` are real, not nil stubs

Difficulty: Medium

Part of `.todo/372` (rove); `.todo/038` lists `macro-function` as "Easy, expose
the macro table the expander already keeps" -- this is its consumer, and it
adds the compile-path half.

Both are lite prelude stubs answering nil on every backend (`LispNames` ~2719-2729:
"macros are fully expanded at compile time; no runtime macro table exists").
Rove's assertion macro decides how to evaluate the asserted form with them, at
MACRO-EXPANSION time:

```lisp
;; core/assertion.lisp
(defun form-steps (form)                       ; called from the %okng macro body
  (do ((step-form form (macroexpand-1 step-form)))
      ((or (eq (symbol-package (first step-form)) (find-package :cl))
           (special-operator-p (first step-form))
           (not (macro-function (first step-form)))
           (get (first step-form) 'assertion))
       (cons step-form steps))
    (push step-form steps)))
(defmacro form-inspect (form &environment env)
  (if (and (consp form) (symbolp (first form))
           (not (special-operator-p (first form)))
           (not (macro-function (first form) env)))
      `(let* (... (,result (list ,@(loop for f in (rest form) collect `(... (form-inspect ,f) ...))))
         (values (if (find *fail* ,result :test 'eq) *fail* (apply #',(first form) ,result)) ...))
      `(values ,form nil nil)))
```

With nil answers every macro form and every special form is taken for a
FUNCTION CALL whose arguments get evaluated first and applied to `#'op`:
`(ok (signals (error "boom")))` evaluates `(error "boom")` OUTSIDE `signals`
(assertion fails as an error), `(ok (signals (foo) 'type-error))` evaluates
`'type-error` as `(form-inspect type-error)` -> "The variable
MY-APP/TESTS::TYPE-ERROR is unbound" (`quote` is a special operator),
`(ok (if a b c))`, `(ok (and a b))`, `(ok (let ...))` all break the same way.
Only `(ok (fn arg...))` with atom arguments works today. Spike-verified on the
interpreter; the compile paths expand `%okng` in the macro-time evaluator, so
they inherit the same answers.

## Contract

- `special-operator-p`: T for the 25 ANSI special operators (`block catch
  eval-when flet function go if labels let let* load-time-value locally macrolet
  multiple-value-call multiple-value-prog1 progn progv quote return-from setq
  symbol-macrolet tagbody the throw unwind-protect`); nil for everything else,
  including names rontolisp implements as special forms but CL defines as
  macros (`defun`, `handler-case`, `dolist`...) -- those answer through
  `macro-function` instead. A caller only ever asks "can I `apply` this".
- `macro-function`: a FUNCTION (callable as `(funcall f form env)` -> the
  expansion, i.e. `macroexpand-1`) for a user `defmacro` (`userMacros`, the
  macro-time evaluator's table included), for every CL macro rontolisp expands
  in `LispMacroExpander` (`when unless cond and or case ecase typecase setf push
  pop incf decf dolist dotimes loop do do* prog1 prog2 return with-open-file
  with-output-to-string handler-case handler-bind restart-case ignore-errors
  defmacro defclass defmethod defstruct define-condition defvar defparameter
  ...` -- the table the expander already dispatches on) and for the
  special-form-implemented CL macros above; nil for functions and special
  operators. The 2-argument `(macro-function name env)` form takes the env
  (`macrolet` bodies see their local macros through the `&environment` the
  expander threads -- today `&environment` binds nil; a nil env keeps the
  global answer). `(setf macro-function)`: out of scope.
- `macroexpand-1`/`macroexpand` return the second value `expanded-p` (rove's
  `expands` compares primary values only, but `form-steps`' loop terminates on
  `macro-function`, so no infinite loop risk once it is real; the value is CL
  and cheap here since these are builtins on the interpreter -- on the compile
  paths honour `.todo/212`'s cross-function-boundary rule).
- Compile paths: a LITERAL name folds against Pass-1 knowledge (user macros +
  the built-in table + special operators, the `fboundp` precedent in
  `.kb/symbol-runtime-api.md`); a COMPUTED name reads a baked name table (the
  `_lookup` registry precedent) -- `(macro-function (intern ...))`; the returned
  expander function for a compiled program: the built-in and user macros are
  gone after expansion, so it is a stub that signals "cannot expand a macro at
  run time on the compiled backends" when CALLED, while the predicate use
  (non-nil) is exact. Rove only tests the value.

Acceptance: `form-inspect`'s three branches (function call / macro / special
form) pinned on all four backends via `RoveE2eTest` (`.todo/372`) and directly:
per-backend suites + a `ci-spec.yaml` case over `(macro-function 'when)`,
`(macro-function 'my-mac)`, `(macro-function 'car)`, `(special-operator-p 'if)`,
`(special-operator-p 'defun)`, `(multiple-value-list (macroexpand-1 '(my-mac 1)))`;
`.kb/symbol-runtime-api.md` + `.kb/defmacro-backquote.md` note; doc pages for
`macro-function`, `special-operator-p`, `macroexpand-1` (the "no second value"
sentence goes); `.todo/038` and `.todo/042` rows updated.
