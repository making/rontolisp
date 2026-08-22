# 485. The `short-float` packed array on the JVM backend: the header does not fit

Difficulty: High

Part of `.todo/482`. Depends on `.todo/484`.

The JVM backend does not carry a wrapper for a packed array: it compiles one to a **bare
primitive array with an embedded dimension header**, `[rank, dim_0, ..., dim_{rank-1},
e_0, ..., e_{total-1}]`, rank and dims stored *in the array's own element type*, data
offset `1 + rank` (`JvmFloatArrayRuntimeBuilder`). That is what lets `_fvAref1` /
`_fvAref2` / `_fvArefN` dispatch on `instanceof double[]` then `instanceof float[]` with
no allocation and no boxing, a `double[]`/`float[]` being disjoint from the `Object[]`
shape of a cons, a function ref and a ratio.

**A `short[]` cannot hold that header.** A dimension is an `int`; a `short` caps it at
32767. A rank-1 vector of a million elements -- an entirely ordinary LLM tensor, and the
exact case this width exists for -- overflows the header slot and silently reads the
wrong data offset. This is the crux of the item and it must be decided before any code.

## The options

- **(a) Two `short` slots per header value.** `[rank, hi_0, lo_0, ..., e_0, ...]`, data
  offset `1 + 2 * rank`. Keeps the bare `short[]`, keeps the `instanceof` dispatch, keeps
  every property the representation was chosen for; costs a per-width data offset and a
  reassemble on each header read. **Recommended.**
- **(b) A separate header object.** Loses "bare primitive array", so a packed array stops
  being disjoint from the boxed shapes and every accessor's dispatch changes. Rejected
  unless (a) hits something unforeseen.
- **(c) Keep `1 + rank` and range-check dims at construction.** Rejected: it makes the
  width unusable for the tensors it was added for.

Note `JvmIntArrayRuntimeBuilder` and the `#<width>@` packed integer vectors
(`.kb/packed-integer-vectors.md`) may already have faced this; read it before choosing.

## Do

1. Pick the header scheme, and write it into `.kb/vec.md` beside the existing
   "JVM a bare `double[]` with an embedded `[rank, dim..., data...]` header" sentence --
   that sentence becomes width-dependent and must say so.
2. `JvmFloatArrayRuntimeBuilder`: the emit helpers are already parameterised over
   `single=false|true` and emit each body twice; they become three, and the header
   offset stops being a shared constant. `_fvMake`/`_sfvMake` gain a `_hfvMake`;
   `_fvToGeneral`, `_fvAref1/2/N` and the aset/length/element-type paths gain the arm.
3. `JvmQuoteCompiler`: bake a `#h(...)` literal into the constant pool as a `short[]`
   (mind `.todo/017`'s baked-constant limit -- a f16 literal is half the bytes of the
   `#f` one, which helps).
4. `JvmFloatConvCompiler` / `JvmFloatpCompiler` / `JvmArraypCompiler`: the width answers
   for `floatp`, `array-element-type`, `type-of`.
5. The `--simd` interception (`JvmSimdCompiler`, `JvmSimdVectorTemplate`) must **not**
   grow a f16 kernel. Per `.todo/482` it widens once into an f32 scratch and calls the
   existing f32 kernel, or leaves the scalar `vec.lisp` defun in place. The measured
   reason is in `.todo/482-half-float-short-float-storage/README.md`: a fused per-element
   decode is 0.31x-0.67x of f32, never faster, at any size or thread count.

## Verify

- `JvmLispCompilerTest` cases for `#h` mirroring the `#f` ones.
- A rank-1 `#h` vector of >32767 elements, and a rank-2 with a dimension >32767:
  `aref` at the last index must answer the value stored there. This is the regression the
  header scheme exists for -- write it first, watch it fail against option (c).
- Interpreter and JVM must print the same text for the same `#h` array; the shared
  `LispArray.renderArrayData` path makes that automatic only if `elementText` agrees, so
  pin it.
- `-o Prog.class && java Prog` on a program that builds, writes, reads back and prints a
  `#h` array.
