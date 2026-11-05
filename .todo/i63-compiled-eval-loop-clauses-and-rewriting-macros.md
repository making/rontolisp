# i63. Compiled `eval` lacks `loop`'s keyword clauses and the macros that bind by rewriting

Difficulty: Medium

`macro/eval-macros.lisp` gave `_eval` the built-in macros that expand to forms it already runs
(`.kb/eval-runtime.md`, "The built-in macros"). Measured 2026-11-05 on the JVM, P1 and the component, what
the interpreter and SBCL 2.2.9 answer and these do not:

```lisp
(eval '(loop for i from 0 to 1 collect i))          ; row refuses: compile-time only
(eval '(macrolet ((m () 1)) (m)))                   ; undefined-function MACROLET
(eval '(symbol-macrolet ((m 1)) m))                 ; undefined-function SYMBOL-MACROLET
(eval '(with-slots (x) o x))                        ; undefined-function WITH-SLOTS
(eval '(restart-case (invoke-restart 'r) (r () 1))) ; undefined-function RESTART-CASE
```

## Plan

- `loop`: a row over `LispMacroExpander.LoopExpander`'s grammar, about 1700 lines of Java. The table is Lisp, so it is a
  port of the clause parser to Lisp source. Port the common clauses (`for` over numbers, lists and vectors, `repeat`,
  `while`/`until`, `do`, the accumulators, `with`, `finally`, `return`) first, with `.kb/loop-iteration-heads.md`'s
  ordering; a clause it does not parse keeps refusing by name.
- `macrolet`, `symbol-macrolet`, `define-symbol-macro`, `with-slots`, `with-accessors`: `_eval` has no macro environment.
  A row can keep one in `env` as `flet` does (`%eval-functions`), read at the unknown-operator path for a local macro
  and at the unbound-variable path for a symbol macro. That is the same shape as the local-function lookup.
- `restart-case`, `restart-bind`, `with-simple-restart`: a helper over `%restart-clusters%` as the compile-time expansion
  writes it, its restart names computed.
