# The spread dispatcher is the price of an eleven-argument funcall

Difficulty: Medium

`_dispatch_spread` is **12,156 B** of the zlib size-report artifact (7.3% of the
`--optimize=size` build). It is not an over-approximating scan -- that was checked and
ruled out, `.kb/sequence-op-runtimes.md` records the finding. The cause is one
constant:

```java
static final int MAX_CALLABLE_ARITY = 10;   // WasmLispCompiler
```

`WasmArityBundler.spreadOverArityFuncalls` rewrites a `funcall` with more arguments
than that into `(apply f (list ...))`, because the per-arity dispatchers take one wasm
parameter per Lisp argument and stop at the ceiling. chipz's

```lisp
(funcall fun state input output :input-start s :input-end e
                                :output-start s :output-end e)
```

is eleven arguments through a variable designator, so the rewritten `apply` reaches
`_apply` and the spread dispatcher for real. A keyword lambda list is the shape that
gets there in practice: the arguments are passed verbatim for the callee's own
dispatcher to parse, so a seven-parameter function is called with eleven.

## Measured

Raising the constant to 12 and rebuilding (nothing else changed) removes
`_dispatch_spread` from the module entirely:

| zlib `--optimize=size` | bytes |
| --- | ---: |
| today | 165,645 |
| `MAX_CALLABLE_ARITY = 12` | **154,022 (-7.0%)** |

`_dispatch_11` costs 975 B where the spread dispatcher cost 12,160. The other
per-arity ladders it pulls in (`_dispatch_3`/`4`/`5`/`7`/`9`) are 747-903 B each.

## Why it is not a one-line change

The constant is an INDEX ORIGIN, not just a limit:

- `FUNC_DISPATCH_SPREAD = FUNC_DISPATCH_BASE + MAX_CALLABLE_ARITY + 1`, so every
  `FUNC_*` after it shifts (`FUNC_PLIST_GET`, `FUNC_HASH`, `FUNC_HASH_RESIZE`, ...,
  `FUNC_START`, `FUNC_USER_BASE`). The comment above the modulo runtime says helpers
  were deliberately appended late "so no import/`FUNC_START` index shifts and the
  component blobs are unaffected" -- this change is exactly the shift that comment
  guards against.
- The callable type indices (`types 11..11+MAX_CALLABLE_ARITY`) shift with it, and so
  does every later type index.

So the work is: check every consumer of those two index families (hand-written WAT
adapters, the component blobs, `WasmEvalRuntimeBuilder`, `WasmArityBundler`,
`WasmRuntimeBuilder.buildDispatchBody`, the tree-shaker's remap), pick the ceiling on
evidence rather than on chipz's eleven, and re-verify all four backends. Consider
whether the ceiling should be DERIVED from the program's widest `funcall` instead of
fixed -- the spread dispatcher then stays as the fallback for a genuinely wide call
rather than being the answer to an eleven-argument one.

## Deliverable

Measured reductions in the `zlib` rows of `size-report/results/wasm-flags.md` with the
row's check still gunzipping byte for byte, `./mvnw test` + native `CiSpecE2eTest`
green, byte-identical output for every program whose widest `funcall` is inside the
old ceiling, and the ceiling's new value justified in `.kb` with the index-shift audit
written down.
