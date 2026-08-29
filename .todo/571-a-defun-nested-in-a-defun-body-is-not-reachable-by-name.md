# 571. A defun nested in a defun body is not reachable by name

Difficulty: Medium (the closure half already works -- what is missing is the
call-site resolution, and the same seam already exists for the top-level
closure-over-`let` spelling)

A non-top-level `defun` lowers to `(setq name (lambda ...))` and its call sites
are resolved through the variable (`LispMacroExpander.expandCallThroughVariable`
-> `(funcall name ...)`). That works when the enclosing form is a top-level
`let` -- the CL closure-over-`let` idiom, which cl-ppcre spells its scanner
caches with -- because the `setq` target is promoted to a global there
(`GlobalVarCollector`) and the call site finds a known global VARIABLE of that
name.

Inside a `defun` BODY the promotion does not happen, so nothing knows the name:

```lisp
(defun install (seed)
  (defun read-seed () seed)
  (lambda () (setq seed (+ seed 1)) seed))
(setq *step* (install 10))
(funcall *step*)
(funcall *step*)
(print (list (read-seed) (funcall *step*)))
```

- interpreter and SBCL: `(12 13)`
- `-o Prog.class`: `warning: the function READ-SEED is undefined; compiled as a
  call-time error`, then `The function READ-SEED is undefined` at run time.

The CAPTURE half is already right as of todo-561: `FreeVarAnalyzer`'s capture
walk descends a nested `defun` body, so `seed` gets one cell and the nested
definition and the sibling lambda share it. Only the name is missing.

Note the shape is legal but odd CL: the definition does not exist until
`install` is CALLED, and calling `install` twice rebinds it. SBCL compiles it
with a style warning and answers at run time.

## What to settle

1. Whether a `defun` in a function body promotes its name to a global the same
   way the top-level `let` spelling does (one seam, both spellings), or
2. whether it is refused loudly at compile time instead -- the call site already
   knows it cannot resolve the name, and "compiled as a call-time error" is the
   generic undefined-function path rather than a message about this shape.

Either is strictly better than today; (1) matches the interpreter and SBCL.

## Acceptance

- The program above answers `(12 13)` on all four backends, or the compile says
  what is unsupported and why.
- A `JvmLispCompilerTest` case and its `WasmLispCompilerIntegrationTest` twin.
- `.kb/core-representation.md`'s "One owner decides ... needs a cell" section
  carries the closing note (it currently records the gap).
