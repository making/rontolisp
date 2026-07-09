# 102 — `--simd` on the interpreter (opt-in Vector API), backed by Native Image SIMD

**Goal:** make `rontolisp prog.lisp --simd` (interpret, no `-o`) actually accelerate the
`vec:` reduction kernels with `jdk.incubator.vector`, so the `--simd` flag stops being a
no-op on the interpreter and becomes a real feature — most valuably on the **native binary**,
where the Vector API is baked in and needs no `--add-modules` fuss. This turns the "`--simd`
has no effect without `-o`" warning added in todo-100 into an opt-in capability.

Confirmed design (user, 2026-07-09): **`--simd` ONLY.** The DEFAULT interpreter
(`rontolisp prog.lisp`) stays the scalar `vec.lisp` reference — it is the cross-backend
byte-identity oracle and MUST NOT change. Acceleration is opt-in per invocation.

## Why now — Native Image DOES intrinsify the Vector API (measured 2026-07-09)

My earlier belief that GraalVM Native Image can't do the Vector API was WRONG (superseded).
Per oracle/graal#10285, Oracle GraalVM JDK 24+ (GraalVM 25) intrinsifies `jdk.incubator.vector`
in Native Image behind **`-H:+VectorAPISupport`** (experimental), on AArch64 + AMD64.

Benchmark (`VecBench.java`, Apple M4 Max, arm64 / NEON 128-bit, double=2 lanes, float=4 lanes;
scalar-loop-relative speedup over N=8192, dot=reduction, add=element-wise):

| kernel | HotSpot (JIT) | Native `+VectorAPISupport` | Native, flag OFF |
|---|---|---|---|
| dot **float** | 3.27x | **3.69x** | 0.16x (≈6x SLOWER) |
| dot **double** | 1.58x | **1.85x** | 0.10x (≈10x slower) |
| add double | 0.95x | 0.99x | 0.03x (≈32x slower) |

Findings that shape the scope:
1. **Native Image + the flag gives real SIMD**, matching/beating HotSpot (dot float 3.7x).
2. **The flag is mandatory.** Without it the Vector API falls back to per-lane emulation that
   is 6–32x SLOWER than scalar — a cliff. If we ship this, the native build MUST set it.
3. **Only reductions benefit** (dot/sum → and `vec:matvec` = a dot per row). **Element-wise
   (add/sub/mul/scale) is memory-bandwidth-bound → ~0.9x, no gain**, on both runtimes. So the
   payoff kernels are `dot`/`sum`/`mean`/`norm`/`matvec`; element-wise is accelerated only for
   result-parity with the compiled `--simd` path, not for speed.
4. Native Image does NOT auto-vectorize scalar loops (a stated non-goal), so on native the
   Vector API is the *only* path to SIMD — it matters more there than on HotSpot.

Bench artifact is in the session scratchpad (not committed); re-create from the table above.

## Design

- **Default (no `--simd`)**: unchanged — `VecLibrary` splices/loads the scalar `vec.lisp`.
  Oracle + ci-spec byte-identity intact (ci-spec never passes `--simd`).
- **`--simd` interpret mode**: `LispEvaluator` gains a `simd` flag; when set, the vectorizable
  `vec:` names resolve to native `LispFunction`s (registered in `Environment`) that call a new
  interpreter-side Vector helper instead of the `vec.lisp` defuns.
- **Result semantics = the compiled `--simd` path.** The helper mirrors
  `JvmSimdVectorTemplate`'s lane logic (`SPECIES_PREFERRED`, `fma`, `reduceLanes`, f32 computed
  in-lane then the reduction widened as that class does) so **interpreter `--simd` ≡ compiled
  `.class --simd`** bit-for-bit, and both ≈ the scalar oracle on exact inputs (the documented
  reduction-associativity caveat, identical to today's compiled `--simd`).

### The package-cycle constraint (do NOT reuse JvmSimdVectorTemplate directly)

`eval` may depend only on `rontolisp` (AST types), NOT on `codegen.jvm` (CLAUDE.md dependency
rule). So the interpreter CANNOT call `codegen.jvm.JvmSimdVectorTemplate`. Also that template
operates on the JVM's **header-in-array** repr (`w[0]=rank, w[1..]=dims, then data`), whereas
the interpreter's arrays are **bare `(data, dims)`** records (`LispDoubleFloatArray(double[]
data, int[] dims)` / `LispSingleFloatArray(float[] data, int[] dims)`), so reuse would need a
per-call header copy anyway. → Write a NEW small helper `am.ik.rontolisp.eval.VecSimd` (or in
`rontolisp`) with `static` kernels over bare `double[]`/`float[]` + dims:
`dot`/`sum`/`matvec` (Vector API) and, for parity, `add`/`sub`/`mul`/`scale` (Vector API too,
so results match the compiled path). ~JvmSimdVectorTemplate's private helpers, re-expressed on
bare arrays — the loop bodies are ~10 lines each (see VecBench). Minor duplication is justified
by the package rule + JvmSimdVectorTemplate's `Lookup.defineClass` self-containment.

### Graceful fallback when the module is absent (java -jar)

The Vector API classes fail to load without `jdk.incubator.vector` in the module graph
(`java -jar` without `--add-modules`). The native binary bakes it in; plain `java -jar` does
not (user accepts this — "JVMインタプリタはVector APIなしでもいい"). So GUARD it: on the first
`--simd` vec: use, probe availability (`Class.forName("jdk.incubator.vector.DoubleVector")`
once, cached); if absent, fall back to the scalar `vec.lisp` and print a one-line note ("add
--add-modules jdk.incubator.vector, or use the native binary"). Nicer than the compiled
`.class`'s hard `NoClassDefFoundError` (the todo-100 / dead-flag behavior). This makes `--simd`
interpret always correct, fast where the module is present.

## Change-set (sketch — re-ground line numbers before starting)

1. **`VecSimd` helper** (`eval` or `rontolisp` pkg): static Vector-API kernels over bare
   `double[]`/`float[]` (+ dims for matvec), mirroring `JvmSimdVectorTemplate` lane-for-lane.
   Behind the `Class.forName` availability probe.
2. **`LispEvaluator`**: add a `simd` flag (+ setter, like `setLoadBaseDir`/`setSystemPath`).
   At the `vec:`-resolution hook (where `VecLibrary.forms()` is lazy-loaded today), when
   `simd` && available, bind the vectorizable `vec:` names to native `LispFunction`s over
   `VecSimd` INSTEAD of loading the scalar defun for those names. `from-list`/`to-list`/`aref`/
   `aset`/`length` stay on `vec.lisp` (unaffected). Keep mean/norm as their `vec.lisp`
   expansions over the (now-native) sum/dot, OR native directly — pick whichever keeps
   result-parity with compiled `--simd` (compiled expands mean/norm over sum/dot, so match).
3. **`RontoLispCli`**: in `interpret(...)`, thread `options.contains("--simd")` → the evaluator
   flag. **Remove** the todo-100 "`--simd` has no effect without -o" warning for the interpret
   case (keep it only for the REPL if desired, or drop it — `--simd` now does something with
   `-o`-less interpret). Update `printUsage` (`--simd` line: add "interpreter: opt-in Vector
   API on the native binary; on `java -jar` add --add-modules jdk.incubator.vector").
4. **Native build (`pom.xml` `native` profile)**: add `--add-modules jdk.incubator.vector`
   and `-H:+UnlockExperimentalVMOptions -H:+VectorAPISupport` to the `native-image` args. NOTE
   `-H:+VectorAPISupport` is EXPERIMENTAL (GraalVM warns "must be unlocked / re-evaluate") —
   decide whether shipping an experimental flag in the release binary is acceptable (it is the
   only way to get native SIMD; the alternative is the perf cliff, so effectively required).
   Both flags are BUILD-TIME only — the produced native binary needs NO runtime flag (verified
   2026-07-09: a self-contained Mach-O runs `--simd` at 3.5x with zero flags). `--add-modules`
   is needed at RUNTIME only for `java -jar` (HotSpot), never for the native binary.
   Also ensure the interpreter path + `VecSimd` are reachable in the Web image build WITHOUT
   pulling `jdk.incubator.vector` into the browser image (the availability probe + a
   `src/web/java` `Target_` substitution may be needed — see the "web-playground native-image
   gotcha" memory; the Vector API is not usable in the JS/WASM web image).
5. **Docs**: `doc/{en,ja}/guides/simd-acceleration.md` — flip the interpreter row of the
   orthogonality matrix from "scalar (no-op)" to "scalar by default; `--simd` = Vector API on
   the native binary (java -jar needs --add-modules)". `.kb/vec.md` "Acceleration layer 1"
   (note the interpreter now shares it, opt-in). `CLAUDE.md` if the `--simd` bullet needs it.

## Scope boundaries

- Reductions + matvec are the point; element-wise is Vector-API'd ONLY for compiled-path
  result-parity, not speed (document that it doesn't speed up).
- No new user surface beyond `--simd` already existing — just makes it do something on the
  interpreter. No REPL wiring unless trivial (the REPL has no per-form flag; skip).
- `--no-gc`/wasm/`.class` unaffected (todo-100 already covers those).

## Verify

- Unit: interpreter `--simd` vec:dot/sum/matvec == compiled `.class --simd` bit-for-bit on
  exact inputs (new test), and == scalar `vec.lisp` on exact inputs (oracle parity). Absent
  module → falls back to scalar (probe test).
- ci-spec: unchanged (never passes `--simd`) — confirms the default oracle is untouched; re-run
  native `CiSpecE2eTest`.
- Native E2E: build with the new flags; `rontolisp bench.lisp --simd` shows the dot/matvec
  speedup (a timing sanity, not a pinned test — hosts vary). Confirm the binary still builds
  and every existing case passes with the experimental flag on.
- Manual: `rontolisp prog.lisp --simd` (native) accelerates; `java -jar ... --simd` without
  `--add-modules` falls back gracefully with the one-line note.

## Pointers

- `codegen.jvm.JvmSimdVectorTemplate` (the algorithm to mirror; `simd{Add,Sub,Mul,Scale,Sum,
  Dot,Matvec}(Object)` over header-in-array `double[]`/`float[]`, private `dotF`/`matvecF`/etc).
  Do NOT depend on it from `eval`.
- `eval.VecLibrary` (`forms()` lazy-load, `isVecQualified`, the interpreter resolution hook).
- `LispEvaluator` (add `simd` flag + the vec:-resolution interception), `Environment` (native
  `LispFunction` registration).
- `cli.RontoLispCli.interpret` (thread `--simd`; drop the interpret no-op warning), `printUsage`.
- `LispDoubleFloatArray(double[] data,int[] dims)` / `LispSingleFloatArray(float[] data,int[]
  dims)` — bare data + dims (no header), so `VecSimd` reads `.data()` directly.
- Build: `pom.xml` `native` profile native-image args; the "web-playground native-image gotcha"
  memory (Web image must not pull in the Vector API).
- Lineage: todo-100 (this `--simd`-orthogonal flag) DONE; `[[single-float-and-matmul-plan]]`
  memory; the VecBench numbers above.
