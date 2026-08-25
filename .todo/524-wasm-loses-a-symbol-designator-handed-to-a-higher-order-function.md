# 524. wasm loses a symbol designator handed to a higher-order function

Difficulty: Medium (the fix is one gate; finding every site it must cover is the work)

## The defect

Every operator that CALLS a function argument accepts a symbol designator at run
time -- CL says so, and the interpreter and the JVM both do it. The WASM backend
traps instead, because the name registry (`_lookup`) is gated on a scan
(`LispMacroExpander.usesRuntimeFunctionDesignator`) that reads `funcall` and
`apply` and nothing else. Measured 2026-08-25, one source shape per row:

```lisp
(defun pred (x) (evenp x))
(print (<op> (car (list 'pred)) (list 2 3 4)))
```

| op | interpreter | wasm |
| --- | --- | --- |
| `mapcar` | `(T NIL T)` | `wasm trap: unreachable` |
| `every` | `NIL` | `wasm trap: unreachable` |
| `remove-if` | `(3)` | `wasm trap: unreachable` |
| `count-if` | `2` | `wasm trap: unreachable` |
| `find-if` | `2` | `wasm trap: unreachable` |
| `position-if` | `0` | `wasm trap: unreachable` |
| `sort` (2-arg) | `(1 2 3)` | `wasm trap: unreachable` |

`(car (list 'pred))` is only the smallest spelling; anything the compiler cannot
read as `#'name` / `'name` / a literal `lambda` has the same fate -- a designator
out of a list, a struct slot, a function parameter.

The JVM had the same hole and never showed it: its eval gate was forced on for
every program, so the registry was always emitted. `.todo/519` stopped that and
gave the JVM an explicit clause instead -- `needsLookup` is now
`usesEval || usesRuntimeFunctionDesignator || !indirectCallArities.isEmpty()`,
where the last clause says a dispatcher IS a call site a symbol can arrive at.
WASM has no such clause.

## What to build

The wasm twin of that clause. `WasmLispCompiler` decides `usesRuntimeDesignator`
the same way and feeds the registry-live argument of `dispatchableFuncIds` and
the registry blob gate (`.kb/eval-runtime.md`); the wasm equivalent of "Pass 2
dispatched through this arity" is what it is missing. `WasmDesignatorCall` is the
one seam every such operator goes through, so the fact is available there.

Check the JVM's clause is not itself over-broad while doing it: the injected
wrapper bodies take their designator as a PARAMETER, so they dispatch in every
program and the clause is effectively always true. Narrowing it needs the
wrappers to stop being compiled into programs that cannot reach them, which is
the same demand-driven injection `.todo/519` started.

## Acceptance

- Every row of the table above answers the interpreter's value on both wasm
  backends, pinned by a `WasmLispCompilerIntegrationTest` case per operator
  family (map, sequence-predicate, sort, maphash).
- A program with no computed designator keeps its current module bytes.
- `ci-spec.yaml` and `ExamplesE2eTest` byte-identical on all four backends.
