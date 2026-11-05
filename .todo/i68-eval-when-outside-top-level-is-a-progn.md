# i68. `eval-when` outside top level is a `progn`

Difficulty: High

Measured 2026-11-05: SBCL 2.2.9 answers NIL for each of these, all four answer the number.

```lisp
(defun f () (eval-when (:compile-toplevel) 1))
(print (f))
(print (let () (eval-when (:load-toplevel) 2)))
```

CLHS 3.2.3.1: outside top level only `:execute` runs the body. Under the `eval` function all four already do that
(`.kb/eval-runtime.md`).

## Plan

Compile paths: `expandEvalWhen` is reached only past `flattenTopLevel`, so it can test `evalWhenExecutes`, provided every form that
keeps its body at top level (`macrolet`, `symbol-macrolet`, `locally`) is flattened or carries the fact. Interpreter: it has no
top-level position to ask, and a file run there has to honor every situation, since it stands for both the compile and the load; each
entry that runs a top-level form has to say so. Size the libraries first: a `(eval-when (:load-toplevel) ...)` inside a form that is
only top level in SBCL's eyes stops running.
