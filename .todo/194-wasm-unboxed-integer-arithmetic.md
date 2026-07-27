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

1. **Expression-tree fusion** (medium): inside a nested arithmetic expression,
   keep the intermediate as a raw i64 on the wasm stack and box only at the
   root. `(logand (+ a b) mask)` becomes one box instead of three. Limited by
   function boundaries -- ironclad's `rol32`/`mod32+` are defuns, so their
   results still box. Estimated 1.5-2x on this benchmark.
2. **Unboxed i64 locals + typed integer arrays** (large, the real fix): a
   per-function dataflow pass that keeps provably-integer locals as raw i64
   (box only at escape points: calls, stores into refs, returns), plus
   `(unsigned-byte 8/16/32)` vectors stored as raw `(array (mut i8/i16/i32))`
   instead of ref arrays so `aref` stops boxing. This is what makes the SHA
   inner loop pure i64 arithmetic. Design questions a session must answer
   before coding: representation joins (an if-branch yielding boxed vs raw),
   bignum overflow escape (i64 arithmetic that overflows must promote, so the
   raw path needs the same overflow checks `_big_*` has), and `--dynamic` /
   eval interop (late-bound calls always take the boxed representation).
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
  left).
- [ ] `logand`/`logior`/`logxor`/`ash`/`+`/`*` on fixnum-range values allocate
  nothing in a hot loop (pin however the implementation allows -- e.g. a
  wasmtime `-O gc-zeal`-style counter run, or a profile assertion documented in
  the todo's close-out).
- [ ] All four backends still agree on `ci-spec.yaml` (bignum promotion at the
  i64 overflow boundary is the risky edge -- `.kb/wasm-bignum.md`'s
  narrowest-tier invariant must survive the raw path).
