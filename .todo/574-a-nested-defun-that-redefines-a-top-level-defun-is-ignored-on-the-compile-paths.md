# 574. A nested defun that redefines a top-level defun is ignored on the compile paths

Difficulty: Medium (the store already exists; what is missing is the call site
preferring it, and only for the names where both spellings meet)

Found while closing todo-571. Every non-top-level `defun` now has a global
variable holding its closure, and a call to a name with no compiled function
dispatches through it. But the call site asks the compiled-function map FIRST
(`Jvm`/`WasmFunctionCallCompiler`), so when the SAME name also has a top-level
`defun`, the nested redefinition is written to a store nothing reads:

```lisp
(defun over () 'top)
(defun redefiner ()
  (defun over () 'nested)
  'done)
(print (over))       ; TOP everywhere
(print (redefiner))  ; DONE everywhere
(print (over))       ; interpreter and SBCL: NESTED -- all three compile backends: TOP
```

This is the whole-program static resolution `.kb/core-representation.md`
records ("A redefined defun binds every call to its LAST definition"), reaching
one spelling further than that section describes: there the two definitions are
both top-level and the LAST one wins for every call, which is at least one of
the two answers; here the losing definition is the one that runs LAST, so no
call ever observes it.

## What to settle

The names where both spellings meet are known at compile time --
`GlobalVarCollector.collectNestedInDefunBodies(program)` (and the nested-defun
branch of `collect`) intersected with the defun name map. For exactly those,
the options are:

1. Route every call site through the global variable, initialized at startup to
   the top-level definition's function value. Costs an indirect call for those
   names only, and matches the interpreter and SBCL. The initialization already
   has a shape to copy -- `BuiltinFunctionWrappers` mints `(setq name
   (lambda ...))` forms.
2. Refuse the shape at compile time, naming the function that is redefined and
   where -- today it is silent, which is the worst of the three.

## Acceptance

- The program above answers `TOP DONE NESTED` on all four backends, or the
  compile says what is unsupported and why.
- A `JvmLispCompilerTest` case and its `WasmLispCompilerIntegrationTest` twin.
- `.kb/core-representation.md`'s "The NAME half" section carries the closing
  note (it currently records the gap).
