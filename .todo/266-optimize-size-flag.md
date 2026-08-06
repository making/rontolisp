# An `--optimize-size` flag: trade the two documented speed/size deals back

Difficulty: High

Carved out of `.todo/261` (the funcall-dispatch gate's remaining half), which
measured the numbers below and deliberately did not take this on. Nothing here
depends on 261's fix; the two are independent.

## The measurement (2026-08-05, `examples/db/postgres-hello --component`)

8.2 MB, 2618 functions, 8,139,447 B of code, with the dispatch gate out of the
picture:

- WASM code is **3.1x** the JVM bytecode for the same program (2,014,123 vs
  6,201,390 over the 1060 functions both emit). Integer-heavy library code is far
  worse: `IRONCLAD::UPDATE-SHA256-BLOCK` is 6,746 B on the JVM and 191,320 B on
  WASM (**28.4x**), `MD5:UPDATE-MD5-BLOCK` 14.6x.
- The two causes are both documented speed/size trades, and turning them off
  measures the price: integer fusion emits the tree TWICE (fast + generic
  fallback, `.kb/wasm-int-fusion.md`) -- worth 6.9% of the module -- and unboxed
  dual-representation locals (`.kb/wasm-unboxed-locals.md`) are worth 16.3%.
  Both together: 8,519,343 -> 6,656,381 (**-21.9%**), with the ci-spec pinning
  tests green either way (they exist to assert the two paths agree).

## The shape of the fix

A `--optimize-size` / `-Os` flag, opt-in so no program's speed changes by
default, that switches off the two emitters above. Both already have a single
decision point each, and both already have ci-spec cases pinning that the fast
and the generic path answer the same thing -- which is what makes the flag
cheap to trust and what a new case must keep true.

Open questions to settle with numbers, not by argument:

- whether the two switch independently or as one flag (16.3% + 6.9% are measured
  separately, but their interaction is not);
- what it costs in run time on the hot loops the two exist for (`md5`,
  `ironclad`, the `vec:`/`linalg:` kernels) -- the flag is only honest with that
  number in the docs beside the size one;
- whether the JVM backend has an equivalent worth the same flag, or whether
  `-Os` stays WASM-only (the 3.1x above says the JVM does not have this problem).
