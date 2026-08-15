# `symbol-value` is read-only: no `setf` place, and no `cl:set`

Difficulty: Medium

`(symbol-value sym)` READS a global through `_genv` on every backend
(`.kb/symbol-runtime-api.md`), but there is no way to WRITE one by a name held in
a variable:

```lisp
(setf (symbol-value 'x) 1)   ; error: setf does not support place: SYMBOL-VALUE
(set 'x 1)                   ; The function SET is undefined
```

Both are Common Lisp (`set` is deprecated but standard, and
`(setf (symbol-value ...))` is the modern spelling), and together they are the
only way to assign to a variable whose name is computed. `setq` needs the name
literally.

The pieces are all in place: `JvmSymbolApiCompiler.compileSymbolValue` already
does `_envLookup(name, _genv)` and reads the binding's cdr, so the setter is the
same lookup plus a store (and a define when the binding is absent);
`WasmSymbolApiCompiler` mirrors it through `FUNC_SYMBOL_VALUE`; the interpreter
assigns into `globalEnv`. What is missing is the `setf` place in
`LispMacroExpander` and the three emitters.

Scope: the place, `cl:set` over the same primitive, `PackageRegistry.CL_SYMBOLS`,
`BuiltinFunctionWrappers`, per-backend tests, a `ci-spec.yaml` case, doc pages
for `set` and a `symbol-value` note.

**The caller that named it** (2026-08-14, `.todo/354`): `uiop:register-hook-function`
is `(pushnew hook (symbol-value variable) :test 'equal)` and nothing more, so it
is the one member of `uiop/utility` that signals `not-implemented-error` rather
than working. When this lands, that body becomes three lines -- the
re-evaluation trigger is written into `.kb/uiop.md`.

`uiop/image`'s hook family (`.todo/362`, landed 2026-08-15) is the same shape and
route around it: `register-image-dump-hook` / `register-image-restore-hook` name
their variable LITERALLY instead of calling `register-hook-function`, which is
the same registration without the primitive. When this lands, both bodies become
the one `register-hook-function` call upstream writes.
