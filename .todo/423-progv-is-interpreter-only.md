# 423. `progv` is interpreter-only, so a program loading cl-json cannot be compiled

Difficulty: High

`JvmExprCompiler` / `WasmExprCompiler` refuse `progv` outright:

```
PROGV is not supported on the JVM backend (interpreter only)
```

Recorded as compile-path limitation 1 in `.kb/dynamic-special-variables.md` with
the reason: the bound symbols are runtime-computed, so the compiler cannot name
the static fields / wasm globals to save and restore.

## Why it is now worth paying for

It is no longer a hole only a hand-written program falls into. **cl-json's
decoder is built on it** -- `aggregate-scope-progv` re-binds a list of scope
variables around every array, object and string it decodes:

```lisp
(defmacro aggregate-scope-progv (variables &body body)
  `(progv ,variables (mapcar #'symbol-value ,variables) ,@body))
```

So any program that loads cl-json fails to compile whole, on all three compiled
backends, whether or not it ever decodes anything. That is what makes
`jose:decode` interpreter-only (`.todo/419`), and cl-json is a dependency far
beyond jose.

The variable lists here are `defparameter`s holding literal symbol lists, so a
constant-folding shortcut looks tempting. Do not take it: it works for exactly
this file's spelling and silently does the wrong thing for the next caller.

## The shape of a real fix

The names are runtime values, but **the set of candidate specials in a program
is static**, which is the asymmetry to exploit:

- When a program contains `progv` at all, `SpecialVarCollector` must treat every
  special in it as dynamically bound (today `collectDynamicallyBound` walks
  binding forms for names it can see). Over-collection is already a small read
  cost by design; under-collection here would be a silent process-global bind.
- Emit two shared helpers per module, `_progvBind(name, value)` /
  `_progvUnbind(name, saved)`, dispatching on the interned name over the
  statically known special set -- a `switch` on the JVM, a name-index compare
  chain / `br_table` on wasm -- each arm doing the same save-and-set the
  existing `let` path does for that one special (JVM: the `_d$*` ThreadLocal
  cell; wasm: the global).
- A name that is in NO arm (CL lets `progv` bind an undeclared symbol) falls
  back to the `_genv` / `GLOBAL_ENV` eval mirror, which is also what
  `symbol-value` reads on the compile path.
- Restore on EVERY exit, through the SAME cleanup emitter `unwind-protect` uses,
  so the copies a `return-from` / `go` inlines on the way out are covered --
  and so this does not re-open limitation 2's holes on a new construct.
- Update the mirror alongside the slot inside the binding, or `symbol-value` of
  a progv-bound name answers the global default (limitation 3). cl-json reads
  its scope variables by NAME, but it also computes them via `symbol-value` in
  the macro above, so both halves are on the live path here.

`.kb/compile-time-boundp.md` line 90 records `progv` as interpreter-only in the
soundness gate for the literal-`boundp` fold; that gate must gain the case in
the same pass.

## Definition of done

`progv` compiles on the JVM and both WASM backends with interpreter parity:
nested binds, a name bound that is not declared special anywhere, extra symbols
bound to unbound-ness, restore after a normal return, after `return-from`/`go`
out of the body, and after an error caught outside. Pinned in
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` (replacing the
`progvIsRejectedOn*` tests) plus a `ci-spec.yaml` case, with limitation 1 in
`.kb/dynamic-special-variables.md` retired and the gate note in
`.kb/compile-time-boundp.md` updated. The consumer check: a program doing
`(json:decode-json-from-string "{\"a\":[1,{\"b\":2}]}")` answers the same nested
alist on all four backends.
