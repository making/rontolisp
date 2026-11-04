# i31. The async top-level resume spills every local at every await

Difficulty: Medium

`_async_resume` of the concatenated ci-spec corpus on `--component` is the largest body left
after `_funcref` went constant: 194,297 bytes against the 262,144 bound (2026-11-04,
`-Drontolisp.wasm.debug-func-sizes`). 12,193 of its `array.set`s are spills: 442 locals, spilled
whole at each suspend site.

Measured growth: ten more top-level `(print (rontolisp:await N))` forms take it to 259,260, ~6.5 KB
an await. **About ten more top-level awaits in the corpus put both `--component` legs of
`CiSpecE2eTest` back over the bound**, the failure `_funcref` caused. It is quadratic: new
await-bearing top-level forms add both locals and spill sites.

Await-free runs are already outlined (`WasmAsyncEmit.compileTopLevelChunkedProgn`); this is what
stays inline. Top-level forms talk through globals, so a local from another top-level statement
should be dead at a suspend site. Ways to bound it:

- Spill only the locals of the statement holding the suspend site. Check a back-edge: a local
  allocated after the site in code order inside a loop.
- Compile each await-bearing top-level statement as its own resume, so `_start`'s state machine
  only sequences them.

Pin it by extending `WasmToplevelChunkingTest#aLongAsyncToplevelIsNotEmittedAsOneFunctionBody` with
a top level of many await-bearing forms that each bind locals. `.kb/wasm-function-body-size.md`.
