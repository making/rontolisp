# A methoded built-in's compile-path fallback drops the optional arguments

Difficulty: Medium

```lisp
(defclass bx () ())
(defmethod floor ((b bx) &optional d) (declare (ignore d)) 44)
(print (multiple-value-list (floor 7 2)))
```

| interpreter | JVM / wasm (2026-09-26) |
|---|---|
| `(3 1)` | `(7 0)` |

`ShadowedBuiltins` binds `%<name>--builtin` to `LispMacroExpander.builtinForwarderDefun`, whose
body calls the built-in with the generic's REQUIRED parameters only: for a variadic generic the
`&rest %gf-rest` tail is accepted and dropped (`.kb/clos.md`, "A user method on a BUILT-IN
name"). So once a program methods `floor`, every non-instance call with a divisor loses it; the
same holds for any shadowed name called with optional or keyword arguments (`close :abort`,
`write-line` with a stream, ...). The interpreter's dispatcher applies the stashed built-in to
the whole tail.

Goal: the forwarder passes the tail. `(apply #'<name> params... %gf-rest)` reaches the
function-object wrapper for `BuiltinFunctionWrappers` names; the `LOWERED_WITHOUT_WRAPPER` names
(`close` first) have no wrapper and need another spelling (a case over the tail length into
direct lowered calls, bounded by `BuiltinCallArity`). Pin with a fixture on all three
backends, beside `MethodedBuiltinFixture`.
