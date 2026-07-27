# WASM integer arithmetic pays a box/unbox round trip per operation

Absolute-performance follow-up to `.todo/188` (which closed the *environment*
tax -- the two engine-level mechanisms in `.kb/wasm-gc-final-types.md` and
`.kb/wasm-gc-heap-pregrow.md`). With those fixed, the 4096-round
PBKDF2-HMAC-SHA256 benchmark sits at **2.9 s on both WASM backends** vs
**0.69 s on the JVM** (2026-07-27, linux/x86-64, wasmtime 47.0.2). The goal is
to close most of that 4x: **under ~1 s is the target**, and given wasmtime's
codegen is generally 1.5-2x behind HotSpot C2, ~1.0-1.4 s is the realistic
floor for a fully unboxed inner loop.

## What the profile says (perf + perfmap, after the 188 fixes)

No engine frames left; the time is boxing traffic and generic helper calls:

| function | share | role |
| --- | --- | --- |
| `_int_val` | 12.7% | unbox (ref eq) -> i64 (i31 test, `{i64}` cast, bignum path) |
| `_int_new` | 11.2% | box i64 -> i31 or ALLOCATE a `{i64}` struct |
| `_rat_add` / `_rat_sub` | 12.1% | generic `+`/`-` entry (ratio/float/bignum dispatch) |
| `_big_and`/`_add`/`_xor`/`_ash` | 18.5% | bitwise helpers (each calls the two above) |
| ironclad's own SHA-256 fns | ~45% | user code (which itself re-enters the helpers) |

Every `(logand a b)` is: call helper -> unbox both operands -> compute -> re-box.
SHA-256's 32-bit words land outside the i31 fixnum range (+-2^30) ~75% of the
time, so nearly every result allocates a `{i64}` (TYPE_BIGNUM) struct. The JVM
backend has the same shape but HotSpot's inlining + escape analysis deletes the
boxes; Cranelift does neither.

## Candidate stages (independent, in effort order)

1. **Expression-tree fusion** (medium): DONE 2026-07-27 (`WasmIntFusionCompiler`
   + `WasmFxRuntimeBuilder`, `.kb/wasm-int-fusion.md`). Measured: 2.9 s ->
   **~2.0 s on both wasm backends** (~1.45x; the estimate's 2x needed defun
   inlining that wasmtime 47 does not do -- helper calls for the checked ops
   and the per-leaf guard remain). Post-stage-1 profile (perf + perfmap):
   `_int_new` root boxing 9.2%, `_charvec_to_str` + `_str_build` 11% (hmac
   re-keying runs `replace`/`subseq`/`copy-seq` through the sequence
   normalizers every iteration), `_rat_add`/`_rat_cmp` singles ~6% (loop
   counters/bounds -- single ops, correctly unfused), dispatch helpers ~5%,
   rest = user defuns whose leaves are still boxed `aref` results and params.
   The remaining 2x is exactly stage 2: unboxed locals/arrays would erase the
   leaf guards, the root re-box and the aref boxing at once.
2. **Unboxed i64 locals + typed integer arrays** (large, the real fix):
   PARTIALLY DONE 2026-07-27 -- the typed-array half plus fused-call defun
   inlining landed (`.kb/packed-integer-vectors.md`); measured 2.0 s ->
   **~1.45 s** on both wasm backends. What landed: packed
   `(unsigned-byte 8/16/32)` rank-1 vectors on interpreter + wasm
   (`LispIntVector` / bare `(array (mut i8|i16|i32))` types 57-59, `_iv_set`),
   `#N@(...)` reads packed, `%array-alike` keeps subseq/copy-seq
   type-preserving, fused `ArefLeaf` raw reads, raw `%aset` stores
   (statement-position stores allocate nothing), literal-`ldb` classification,
   and substitution of closed one-liner integer defuns (mod32+/rol32) into
   fused trees and at direct call sites (never under `--dynamic`).
   The design questions answered: representation joins avoided entirely (no
   raw locals yet -- dual-representation design sketched below), overflow
   escape = the existing fused-fallback recomputation, `--dynamic`/eval =
   inlining and raw paths disabled under `--dynamic`, boxed on-demand
   elsewhere.
   The JVM packed `long[]` half landed the same day (`JvmIntArrayRuntimeBuilder`
   `_iv*` helpers under the `usesIntArray` gate) -- all FOUR backends now share
   the mask-store/unsigned-read semantics, pinned by the `packedIntVector*`
   tests and the `packed-integer-vectors` ci-spec case.
   STILL OPEN (stage 3 candidates, in profile order at ~1.45 s):
   - **flet inlining** (~15%: dispatch_N 6% + sigma/ch/maj lambda bodies +
     their boxed boundaries): beta-substitute single-expression closed flet
     bodies with pure (symbol/constant) args at expansion time, or teach the
     fusion classifier the `(funcall __fletN_x ...)` shape.
   - **unboxed i64 locals** (the round temps x/d/h, ~8% `_int_new` residue):
     dual-representation design -- each eligible local gets an i64 slot + a
     boxed shadow slot with "shadow non-null => boxed (bigint promotion)"
     invariant; eligibility = never captured/special, every assignment a fused
     tree root. Needs typed local declarations (today every local is
     `(ref null eq)`).
   - `_str_build`/`_charvec_to_str` ~6% (hmac re-keying string traffic) and
     single-op loop-counter `+` through `_rat_add` ~4%.
3. NOT worth doing for this: SIMD (SHA-256 is serially dependent) and further
   wasmtime flags (nothing engine-level is left in the profile).

Warning from 188: profile before optimizing each stage; this file's estimates
are extrapolations, and 188's original guesses were all wrong.

## Repro

```bash
./mvnw -q clean package -DskipTests
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
cat > pb.lisp <<'LISP'
(ql:quickload :ironclad)
(let ((s (get-internal-real-time)))
  (let ((r (ironclad:pbkdf2-hash-password
             (ironclad:ascii-string-to-byte-array "pencil")
             :salt (ironclad:ascii-string-to-byte-array "salt")
             :digest :sha256 :iterations 4096)))
    (format t "~a~%" (ironclad:byte-array-to-hex-string r))
    (format t "elapsed ~a ms~%" (- (get-internal-real-time) s))))
LISP
java -jar $JAR pb.lisp -o pb.wasm && wasmtime run -W gc pb.wasm   # ~2.9 s
# profile (needs kernel.perf_event_paranoid <= 1):
perf record -g -- wasmtime run -W gc --profile=perfmap pb.wasm
perf report --no-children --stdio
```

## Acceptance

- [ ] The PBKDF2 benchmark above runs in under ~1 s on WASM Preview 1 and the
  component (or the session records why the wasmtime codegen floor makes a
  higher number the honest limit, with a profile showing no box/unbox traffic
  left). Stage 1 got to ~2.0 s; stage 2 (typed arrays + defun inlining) to
  **~1.45 s** on Preview 1 (2026-07-27). Remaining traffic is the flet
  boundary, the round-temp locals and hmac string re-keying (see the stage-2
  residue list above), not engine overhead.
- [ ] `logand`/`logior`/`logxor`/`ash`/`+`/`*` on fixnum-range values allocate
  nothing in a hot loop (pin however the implementation allows -- e.g. a
  wasmtime `-O gc-zeal`-style counter run, or a profile assertion documented in
  the todo's close-out). Stage 2 partial: fused-tree intermediates, packed
  `aref` leaf reads and statement-position packed stores allocate nothing;
  out-of-i31 roots that feed LOCALS (the round temps) and flet-boundary
  values still box.
- [x] All four backends still agree on `ci-spec.yaml` (bignum promotion at the
  i64 overflow boundary is the risky edge -- `.kb/wasm-bignum.md`'s
  narrowest-tier invariant must survive the raw path). Stage 1 pinned by the
  `fused-integer-expression-trees` case +
  `WasmLispCompilerIntegrationTest.fusedIntegerExpressionTreesMatchTheGenericPath`.
