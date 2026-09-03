# 671. An IEEE f16 (or bf16) checkpoint is a load-time conversion, not a width

Difficulty: Low

Part of `.todo/670`. Depends on nothing; lands first, on every backend.

Measured 2026-09-03 (`.todo/482-bfloat16-a-narrow-width-that-pays/README.md`, round 2,
sections 1, 3, 4): a fused f16 GEMV loses to f32 on both JITs (0.30x C2, 0.58x Graal),
and converting 1.1B f16 elements to f32 costs 0.57 s under Graal and 0.11 s under C2,
single-threaded. So an f16 file is never held as f16: it is read as bits and widened
once. The same primitive, with the shift instead of the f16 decode, widens **bf16 bits
into `#f`** -- which is what lets a BF16 safetensors / GGUF load and run on the f32 path
today, before `.todo/484` gives bf16 an array of its own.

## Do

1. **The scalar pair**, in the `rontolisp` package: `float16-bits` (a real -> the 16-bit
   pattern, round-to-nearest-even as `Float.floatToFloat16` does) and `bits-float16`
   (the inverse, exact). Beside `single-float-bits` / `bits-single-float` in `LispNames`,
   the full "Adding a Built-in Function" checklist in `CLAUDE.md` INCLUDING the wasm
   backends: the JVM and the interpreter have the intrinsic, wasm needs the exact
   bit-trick (`Dec.java` variant D: magic multiply + one masked inf/NaN fixup, 0
   mismatches over all 65536 patterns) written once as a wasm function. `bfloat16-bits`
   / `bits-bfloat16` are `.todo/487`'s; if this item lands first, take them too -- same
   shape, `<< 16` and the round-to-nearest-even narrow.
2. **The bulk widening**, one primitive, destination-passing:
   `(rontolisp:widen-float-bits bits format dst &key (start 0))` -- `bits` a packed
   `(unsigned-byte 16)` vector, `format` `:float16` or `:bfloat16`, `dst` a packed float
   array of any rank (`#f`, `#d`, and `#bf16` once it exists, where `:bfloat16 -> #bf16`
   is a plain copy), filled row-major from element `start`; returns `dst`. The caller
   owns the allocation, so a 2.2 GB tensor is widened chunk by chunk through a staging
   vector of a few MB and never exists twice. The reverse, `narrow-float-bits`, is the
   same primitive the other way (for `.todo/487` step 4 and for writing a checkpoint).
   Names are proposals; the shape -- bits vector, format keyword, destination, offset --
   is the point.
3. **Every backend.** Interpreter, JVM (`JvmFloatArrayRuntimeBuilder`'s bare arrays),
   wasm-GC, `--component`; `--no-gc` if its `F32VEC` block makes it trivial, else a clear
   compile error. The interpreter and JVM arms vectorize (`Load.java`: 5.4 Gelem/s f16,
   16 Gelem/s bf16 with `convertShape(S2I)` + shift); wasm runs the scalar decode, which
   is still one pass.
4. **Why not a `:format` knob on `read-sequence`.** It was considered: the packed bulk
   read (`.kb/binary-sequence-io.md`) would decode as it fills. Rejected because the bits
   arrive from more than a file stream -- a GGUF held in memory, a socket, an
   `(unsigned-byte 16)` array a program already has -- and because Q8_0's f16 *scales*
   (`.todo/672`) want the same decoder over a slice of a byte buffer. A converter over a
   bits vector composes with all of them; a stream knob composes with one.

## Verify

- `bits-float16` of every one of the 65536 patterns equals `Float.float16ToFloat`, on
  all four backends (`ci-spec.yaml`), NaN payload aside.
- `float16-bits` round-trips the widened value, and rounds to nearest even on the ties:
  pin both directions.
- `widen-float-bits` over a vector larger than the staging chunk, checked at the first,
  last and a middle element, at `start` 0 and at a nonzero `start`, into a rank-2
  destination.
- The vectorized interpreter / JVM arms agree bit for bit with the scalar loop over all
  65536 patterns (`Load.java` does exactly this check; keep it as the test).
- Throughput recorded in `.kb/binary-sequence-io.md` beside the bulk-read numbers, with
  the JIT named.
