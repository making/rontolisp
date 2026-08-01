# Runtime intern -> funcall/apply needs a function name table on the compile backends

Difficulty: 高 (a new runtime dispatch surface on both compile backends, with
tree-shaker interaction; the design decision — full table vs registration on
reference — is the hard part, not the emitting)

Part of the Clack milestone `.todo/223`, ON THE CRITICAL PATH: the milestone
targets all backends except WASM Preview 1, so the JVM and component legs of
`.todo/228` cannot land without this. Also the known jzon residue ("a
symbol-valued `:key-fn` errors at call time", `.kb/asdf.md`).

## Why clack forces it

Clack's whole handler-backend protocol is late-bound by NAME:

    (apply (intern #.(string '#:run) handler-package) app ...)   ; handler.lisp
    (funcall (intern (string :wrap) :clack.middleware) ...)      ; builder.lisp
    (symbol-value (intern (format nil "*~A*" ...) package))      ; find-middleware

On the interpreter this just works (the evaluator resolves function designators
against the live environment). On the JVM/WASM backends a runtime-interned
symbol has no route to the compiled function: there is no name table, so
`funcall`/`apply` of a non-literal symbol errors at call time. Any Clack app
compiled to a class/component dies inside `clackup`.

## Direction (to be decided at implementation time)

- A compiled-in name->function table over the SAME set the tree-shaker keeps
  (a shaken-out function is honestly "undefined at runtime" — same policy as
  today, but the kept set becomes callable). The `BuiltinFunctionWrappers`
  and `%find-class`/`%typep-runtime` table-scan precedents show the shape:
  bounded generated dispatch, not reflection.
- `symbol-value` of a runtime-interned special (find-middleware's third line)
  is the same problem for VARIABLES; scope it in or split it out explicitly.
- Cost control: emitting the table only when the program contains a non-literal
  `funcall`/`apply`/`symbol-function`/`symbol-value` reference (the
  reference-gated wrapper precedent), so ordinary programs stay byte-identical.
- WASM function-body/table size limits: `.kb/wasm-function-body-size.md`,
  `.kb/jvm-method-size-limits.md` — chunk like the defun chains.

## Test

ci-spec case: `(funcall (intern "..." :pkg) ...)` + `(apply (intern ...) ...)`
+ symbol `:key`-style designator, identical on all four backends; a shaken-out
name errors with the undefined-function message.
