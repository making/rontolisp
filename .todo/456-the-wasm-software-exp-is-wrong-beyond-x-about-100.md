# 456. The WASM software `exp` is wrong beyond |x| ~ 100 and explodes beyond ~300

Difficulty: Medium

`WasmExpCompiler.emitExpCore` (and its mirrors: `WasmVecSimdRuntimeBuilder.emitExpF64`
/ the f32x4 lane kernels of `vec:exp`, the `--no-gc` exp, and `tanh`/`sinh`/`cosh`,
which build on it) computes `e^x = (P5(x/256))^256`, a degree-5 Taylor polynomial
squared eight times. The squarings multiply the polynomial's relative error by 256, so
the documented "~1e-6 relative" holds only for |x| below ~20:

```
                 wasm                      JVM / interpreter
(exp -20.0)      2.0611534442579177e-9     2.061153622438558e-9      ; 1e-7
(exp -100.0)     3.7135062819054375e-44    3.720075976020836e-44     ; 2e-3
(exp 100.0)      2.685685907248722e43      2.6881171418161356e43     ; 1e-3
(exp -1000.0)    2.4349856998741507e125    0.0                       ; WRONG
(exp -1e30)      Infinity                  0.0                       ; WRONG
```

Beyond |x/256| ~ 2 the truncated polynomial goes NEGATIVE, the squarings turn that into
a huge positive number, and `exp` of a large negative argument answers `Infinity`
instead of `0.0`. Found 2026-08-19 by an attention softmax written over whole-vector
kernels with a `-1e30` causal mask (`examples/llm/`): the masked weights became
`Infinity`, every logit NaN, every token `<unk>` -- on WASM only. A softmax over
32000 logits routinely sees `logit - max` around -30..-60, where the error is already
1e-5..1e-4; a real model will hit the cliff.

## Shape of a fix

The standard range reduction: `x = k*ln2 + r` with `k = nearest(x / ln2)`, `|r| <=
ln2/2` (a two-part ln2 split, like the Cody-Waite reduction `WasmSinCosCompiler`
already does for pi/2), `e^r` by a degree-11 Taylor / minimax polynomial (~1e-16
relative on that interval), then scale by `2^k` through the exponent bits
(`i64.reinterpret` / shift / `f64.reinterpret`, the trick `WasmLogCompiler` already
uses to take the exponent OUT), with the edges `x < -745.2 -> 0.0`, `x > 709.8 ->
+inf`, NaN -> NaN. Every step vectorizes (`i64x2.shl`, `f64x2` arithmetic), so the
`--simd` lane kernels keep mirroring the scalar bit for bit -- the invariant in
`.kb/vec.md` ("Element-wise unary ufuncs"): change the scalar and the mirrors
TOGETHER, and `tanh`/`sinh`/`cosh` (which reuse the core) improve with it; drop the
`+-40` tanh clamp only if the new core handles it.

Expect pinned digits to move: the wasm integration tests and doc pages that print a
wasm-specific `exp`/`tanh` value, `doc/{en,ja}/guides/math-backends.md` ("~1e-6
relative error"), and the `.kb/vec.md` accuracy notes. ci-spec prints no raw `exp`
digits (it rounds), which is what kept it cross-backend -- keep it that way.
