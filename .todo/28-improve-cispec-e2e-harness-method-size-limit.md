# 28 - Improve CiSpecE2eTest so it no longer hits the JVM 64 KB method ceiling

## Goal

Let new cross-backend cases be added to `src/test/resources/ci-spec.yaml`
without overflowing the JVM per-method bytecode limit. Today the harness is at
the ceiling, so **any** new case can fail the JVM backend -- the suite cannot
grow.

## Problem

`CiSpecE2eTest` (package `am.ik.rontolisp.e2e`) concatenates **every** case into
one program and compiles it to a single `Test` class, so all of it lands in one
JVM `main` method. The baseline already sits at ~64,800 bytes of code; the JVM
hard limit is 65,535. Adding even one small case can overflow it:

```
java.lang.ClassFormatError: Invalid method Code length 65550 in class file Test
```

This surfaced while implementing `map` (`.todo/25`): a single
`(print (map 'string #'char-upcase "abc"))` case produced 65,550 bytes (two
forms ~66,100; six ~67,600), so no dedicated `map` case could be added. The
overflow is a pre-existing scaling problem in the harness, independent of `map`.

## Proposed fix

Stop bounding the whole suite by one method's size. Options, roughly in order of
preference:

1. **Split the generated program across methods/classes.** Emit each ci-spec
   case (or each chunk of N cases) as its own method called from `main`, or as
   its own top-level file compiled to its own class, then run them in order. The
   per-method 64 KB limit then bounds a single case, not the entire suite. This
   unblocks every future case at once. Needs care to preserve the current
   semantics: cases share global state and run **in order** (the driver
   concatenates them and slices stdout back per case), so the split must keep a
   single shared runtime/global environment and the same execution order.
2. **Shrink the per-case bytecode footprint** of the verbose lowerings. E.g. a
   dedicated `JvmMapCompiler` / `WasmMapCompiler` (like `mapcar`) instead of the
   `do*`/`let*`/`while` macro expansion `map` uses now -- a tight inline loop is
   far smaller per call site. This only buys headroom, it does not remove the
   ceiling, so it is a complement to (1), not a substitute.

After the harness is fixed, add the deferred `map` E2E case (e.g.
`(map 'list #'+ '(1 2 3) '(10 20 30))` and `(map 'string #'char-upcase "abc")`),
which is currently only covered by per-backend unit tests plus the
`rontolisp-package-introspection` function-count assertion.

Related: `.todo/17-jvm-baked-constant-limit`, `.todo/09-wasm-function-arity-cap`,
`.todo/25-generic-map-over-sequences`.
