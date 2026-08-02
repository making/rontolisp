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

## Investigation result (2026-08-02): the table EXISTS, three gaps remain

The design decision this item feared was already made by the cl-postgres work
(`'list-row-reader` through `exec-query`): both compile backends carry a
name->function registry (`_lookup`, gated on
`LispMacroExpander.usesRuntimeFunctionDesignator`) and the funcall/apply
dispatchers resolve a SYMBOL funcval through it. Verified working today on all
four backends: `(funcall (intern "GREET") ...)`, `(apply (intern ...) ...)`,
computed names, builtin designators (`(intern "CAR")` through
funcall/mapcar/sort `:test`+`:key` — the jzon `:key-fn` residue is gone),
`(symbol-value (intern "*MW*"))` via the `_genv` mirror, and funcall of an
undefined name errors loudly. What does NOT work — the actual remaining scope:

1. **2-arg `intern` with any package but `:keyword`** errors at run time on the
   compile paths ("intern with a runtime package argument is not supported").
   This is THE clack blocker: handler.lisp `(apply (intern #.(string '#:run)
   handler-package) ...)` (computed package), builder.lisp `(intern (string
   :wrap) :clack.middleware)` (literal package), util.lisp find-middleware
   (computed name AND package, then boundp/symbol-value). Fix: retire the stub
   and lower exactly like 2-arg `find-symbol` already does
   (`expandFindSymbolInPackage` / `computedPackageFindSymbol` — the stub's
   "needs the resolver's package state" reason died when todo-198 built the
   canonical-spelling machinery). Divergence from find-symbol: an unknown
   literal package must ERROR (`No such package: X`, interpreter parity), not
   fold to nil; ditto a nil computed designator. NOT handler-case-catchable on
   the interpreter (LispPackageException), so pin per-backend, not in ci-spec.
2. **Internal (`::`) names miss the registry**: the runtime-built spelling is
   the single-colon external one, registry keys are canonical (`PKG::NAME` for
   unexported defuns) -> undefined where the interpreter succeeds. Fix: alias
   rows in both registries (JVM `lookupSegments` extra compare, WASM extra
   12-byte records) mapping `PKG:NAME` -> the same funcId; collision-free
   because one package cannot house two distinct symbols with one member name.
   VARIABLES (interned unexported specials via `_genv`) stay out of scope —
   exported ones work (lack's `*lack-middleware-backtrace*` is exported);
   re-evaluate if a library reads an unexported special through runtime intern.
3. **`_apply` symbol-miss returns nil SILENTLY** on both compile backends
   (funcall's dispatcher already errors): `(apply (intern "NOSUCH") ...)` is
   nil. Fix: throw "The function X is undefined" (JVM) / `unreachable` (WASM,
   the dispatcher's existing miss shape).

## Status (2026-08-02): implemented, all three gaps + two same-pass retirements

All three fixes above landed (`LispMacroExpander.expandInternInPackage` /
`computedPackageIntern` / `computedQualifiedSpelling`, the alias rows in
`JvmEvalRuntimeBuilder.lookupSegments` and the WASM registry blob,
`emitUndefinedFunctionThrow` / the WASM `unreachable` arm). Two divergences whose
"no runtime name table" reason died were retired in the same pass (the working
principle): computed `symbol-function`/`fdefinition` now lower to the IDENTITY
(the symbol is the designator — closes the jzon `:key-fn` residue), and
`uiop:symbol-call` is REAL on the compile paths (the `.kb/asdf.md` re-evaluation
trigger; its lowering happens post-gate-scan, so the pre-lowering spelling
counts in `containsRuntimeFunctionDesignator` and the WASM `usesIntern`).
Full mechanics: `.kb/symbol-runtime-api.md` "Runtime-interned symbols as
function designators". Tests: the four-group per-backend suites, the
`runtime-intern-funcall` ci-spec case; the clack handler.lisp / builder.lisp /
find-middleware shapes verified by hand on all four backends. Docs: intern /
symbol-function / fdefinition / uiop-symbol-call pages (en+ja).
