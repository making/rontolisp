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
   Stage 3: DONE 2026-07-27 (~1.45 s -> **~0.93 s** on BOTH wasm backends,
   Preview 1 and `--component`; hash identical). What landed (all wasm-only,
   semantics-preserving; `.kb/wasm-int-fusion.md`, `.kb/wasm-unboxed-locals.md`):
   - **flet inlining** via the fusion classifier: `(funcall __FLETn_f ...)` of
     a let-bound closed-integer-lambda substitutes like an inlinable defun
     (`Ctx.localIntLambdas`, registered by `WasmLetCompiler`; the `(block name
     expr)` wrapper unwraps; `#'f`-as-value and labels untouched). Erased
     dispatch_1/dispatch_3 + the sigma/ch/maj lambda bodies from the profile.
   - **guard-once leaf hoisting**: every leaf unboxes ONCE into an i64 scratch
     local at the top of the bail block (new second locals run, padded-LEB
     index patching via `buildLocalsAndPatch`); previously the guard re-emitted
     per occurrence, which made inlined bodies pay more in guards than they
     saved in dispatch.
   - **unboxed i64 locals** (the dual-representation design as sketched): i64
     slot + boxed shadow, "shadow non-null => use shadow"; raw stores on the
     fused fast path, total boxed escape on bail (limb promotion, floats,
     lists); non-fused reads box on demand with INLINE `ref.i31` for the i31
     range. ~13% by A/B (`-Drontolisp.debug.norawlocals=true` re-measures).
   - **masked-wrap peephole**: under a literal `logand` mask / power-of-two
     `mod`, `+ - *` and left-`ash`-by-literal emit as UNCHECKED wrap-around
     i64 (low bits exact) -- mod32+/rol32 pay no `_fx_*` calls at all. This
     was the single biggest stage-3 item (~1.15 -> ~0.93 s).
   - **cached `t`** (`_t_sym` + always-last module global): every comparison's
     true result used to `_str_build` a fresh "T" (~8% of the profile -- loop
     termination tests allocated per iteration). Same id/bytes, eq unchanged.
   - Bugfix found on the way: a failed defun-body substitution left its
     already-registered leaves in the site (side-effecting args evaluated
     TWICE); `substituteCall` now rolls back. Pinned in the stage-3 test.
   Post-stage-3 profile (~0.93 s plain, ~1.18 s under perf): UPDATE-SHA256-BLOCK
   self ~20% (the fused rounds themselves), SHA256-EXPAND-BLOCK ~8.5%,
   %UPDATE-DIGEST--m1 ~7% (mdx buffer traffic), `_int_new` ~5.5% (out-of-i31
   boundary crossings), XOR-BLOCK ~3%, `_rat_add`/`_rat_sub`/`_rat_cmp` singles
   (loop index math `(- i 2)`, `(< i 64)`) ~8.5%, `_charvec_to_str` ~3%.
   Plausible next levers if anyone needs more: raw comparisons over unboxed
   locals (kills `_rat_cmp` + the boxed counter reads), fusing the single-op
   index math, and the mdx-updater `replace` traffic.
   Stage 4 (close-out): DONE 2026-07-27 (~0.93 s -> **~0.70 s** on BOTH wasm
   backends -- JVM parity, 0.69 s). All the "plausible next levers" above,
   plus what the re-profile actually showed (`.kb/wasm-int-fusion.md` stage-4
   note has the mechanics):
   - **fused raw comparisons** (`tryCompileCompare`): killed `_rat_cmp` /
     `_rat_cmp_bits` / `_big_cmp` (~7%) -- loop tests run as inline i64
     compares with the generic-fallback recompute.
   - **single-op fusion** for raw-reading leaves OR a literal operand: killed
     the `_rat_add`/`_rat_sub` singles (index math, incf-of-local).
   - **inline i31 root boxing** at fused sites (only out-of-i31 pays
     `_int_new`).
   - **raw leaf-root stores** (aset value = bare packed aref / raw local /
     constant) + `expandReplace` reading array sources with `aref`: the
     mdx/copy-digest `replace` loops move bytes raw (~7% -> ~5% self, no
     boxing).
   - **statement-position literals emit nothing** (defun/lambda bodies through
     `compileForEffect`): ironclad's DOCSTRINGS cost a `_str_build` per call.
   - **stringp without the unconditional `_charvec_to_str` call** (the
     setf-aref string dispatch runs it per store).
   Post-stage-4 profile: UPDATE-SHA256-BLOCK ~31% / SHA256-EXPAND-BLOCK ~11%
   self (the fused rounds themselves -- wasmtime codegen vs C2 is the floor),
   `_int_new` ~4.5% boundary crossings, `_iv_set` ~2.5%, residual `_rat_add`
   ~2% = incf of eqref PARAMETERS (params have no raw representation; a raw
   param copy is the documented follow-up trigger in
   `.kb/wasm-unboxed-locals.md`). Pinned by
   `fusedComparisonsAndRawLeafStoresMatchTheGenericPath` + the
   `fused-comparisons-and-raw-leaf-stores` ci-spec case.
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

- [x] The PBKDF2 benchmark above runs in under ~1 s on WASM Preview 1 and the
  component: **~0.93 s on both** (2026-07-27, stage 3; stage 1 ~2.0 s, stage 2
  ~1.45 s). JVM comparison point: 0.69 s -- the remaining gap is wasmtime
  codegen vs C2 plus the mdx/string traffic listed in the stage-3 profile
  above, not box/unbox traffic in the rounds.
- [x] `logand`/`logior`/`logxor`/`ash`/`+`/`*` on fixnum-range values allocate
  nothing in a hot loop: fused-tree intermediates stay raw i64, round temps
  live in unboxed locals (raw stores allocate nothing, statement-position
  stores materialize nothing), masked `+ - * ash` run unchecked, packed aref
  reads/stores are raw, and a comparison's `t` is the cached shared instance.
  Evidence: the stage-3 profile has no `_int_new`-dominated frame left
  (~5.5% residue = out-of-i31 boundary crossings such as struct stores and
  `ub32ref/be` composition), and the A/B toggles
  (`-Drontolisp.debug.norawlocals=true`, stage-2/3 jars) reproduce the step
  changes.
- [x] All four backends still agree on `ci-spec.yaml` (bignum promotion at the
  i64 overflow boundary is the risky edge -- `.kb/wasm-bignum.md`'s
  narrowest-tier invariant must survive the raw path). Stage 1 pinned by the
  `fused-integer-expression-trees` case +
  `WasmLispCompilerIntegrationTest.fusedIntegerExpressionTreesMatchTheGenericPath`;
  stage 3 by the `flet-fusion-and-unboxed-locals` case +
  `fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`.
