# j58. JVM: `vector-push-extend` in statement position boxes the index nobody reads

Difficulty: Low

`.kb/starlark-frontend.md`, "Against CPython (2026-11-16)": in `comps.star` on the JVM
13% of the samples are `Long.valueOf` under `_vectorPushExtend` -- the index it answers,
which a comprehension's `(vector-push-extend elt acc)` drops. Past 127 that is an
allocation per element (the profile: JFR, `jdk.ExecutionSample` every 1 ms).

## Do

- Confirm it is the answered index (not the pushed element's box) by reading
  `_vectorPushExtend`'s emitter.
- A for-effect arm: where the value is dropped, push without boxing the index. The wasm
  backend has the statement-position overloads (`WasmExprCompiler.compileForEffect`).
- Measure `comps.star` with `.todo/artefacts/j54-starlark-faster-than-cpython/time.sh`
  before and after, and any Common Lisp program that pushes in a loop.
