# c06. Special-named parameters bind dynamically on JVM and WASM

Difficulty: High

CL binds a lambda/defun parameter whose name is proclaimed special dynamically (CLHS 3.1.2.1.1.2).
The interpreter does (`LispEvaluator.apply` pushes `DynamicBindings`); the compile paths keep it lexical.

Measured 2026-10-03 at `77c04a2d2`:

```lisp
(defvar *x* 1)
(defun show () *x*)
(defun f (*x*) (show))
(print (f 2))
(print ((lambda (*x*) (show)) 3))
```

Interpreter `2 3`; JVM, WASM preview 1 and component `1 1`.
`.kb/dynamic-special-variables.md`, "Compile-path limitations" item 3.

Make the JVM and both WASM backends bind such parameters through the same shallow save/restore
a special `let` uses, restored on every exit channel the `let` already covers.

Cover:

- required, `&optional`, `&key`, `&rest` and `&aux` parameters, and their default forms seeing earlier dynamic bindings;
- lambdas, `defun`, `labels`/`flet`, and macro lambda lists if the compile path expands them at run time;
- the JVM self-tail-call jump (`.kb/jvm-self-tail-calls.md`): a self tail call inside a function that binds a special parameter must restore before re-entry, or not be compiled as a jump;
- a closure capturing such a parameter;
- the Clojure and Scheme frontends must not regress (they lower to CL lambdas).

Pin it in the cross-backend spec. Remove item 3 from the kb when done.
Measure the size and speed cost on programs without special parameters; it should be zero.
