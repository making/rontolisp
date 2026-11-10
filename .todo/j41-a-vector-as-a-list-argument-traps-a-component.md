# A vector as a `list<T>` argument: a component traps where the interpreter passes it on

Difficulty: Medium

Since `2db646c` a component lowers a `list<T>` argument from a proper list and traps on anything
else, while the interpreter and the JVM hand the argument to the provider as it is. One call, two
behaviours, on `d198df6` with `wasi:keyvalue/batch@0.2.0-draft2`'s `get-many` (the component served
by a sluice wasi wicket over its sqlite store):

| `(kvb:get-many bucket keys)`, `keys` = | interpreter, a provider bound | `--component` |
| --- | --- | --- |
| `(list "a" "b")` | the provider gets the list | `(("a" NIL) ("b" NIL))` |
| `(vector "a" "b")` | the provider gets a `(SIMPLE-VECTOR 2)` | `wasm trap`: the wicket answers 500, and a `handler-case` around the call never runs |

A vector is what a program often has: `rontolisp:json-parse` reads a JSON array as a simple-vector,
so passing a request's array straight to `get-many` traps. A test on the interpreter over a
provider that maps any sequence passes, and only the component fails.

## Goal

A `list<T>` argument behaves the same on every backend: a vector crosses as a proper list does, and
anything else (a dotted list, an atom) signals a `type-error` the program can handle instead of
trapping. An element that is itself a `list<T>` (a tuple's, a record's) alike.

## Direction

- `WasmComponentImportCompiler`: the `ListOf` arms `2db646c` added to `emitLowerParam` and
  `emitLowerAt` take the length and the elements of a simple-vector as well, and branch to a signal
  where they trap now.
- Interpreter / JVM: the binding hands the provider a list whichever the program passed (a vector
  coerced), so a provider sees one shape, and signals the same `type-error` for anything else.
- `doc/*/guides/wit-contracts.md` and `doc/*/reference/functions/rontolisp-wit-import.md`: "a proper
  list" becomes "a list or a vector".
- A test over `HostBoundaryRuns` (the interpreter, the JVM, `wasmtime -S keyvalue`) passing a
  vector, and a dotted list caught by `handler-case`.
