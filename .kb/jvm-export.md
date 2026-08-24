# `rontolisp:jvm-export` (typed Java-callable methods) + `--no-main` library mode

`(rontolisp:jvm-export 'name :params '(T...) :returns T :as "javaName")` is the JVM
twin of `rontolisp:wasm-export` ([wasm-export-no-wasi.md](wasm-export-no-wasi.md)):
it declares the Java-boundary types of a top-level `defun` so `JvmLispCompiler`
emits a thin `public static` wrapper with a primitive/`String`/`byte[]`/handle
signature next to the untyped `(Object...)Object` method. Directive parsing + the Java-name
derivation live in `compiler.JvmExportDirective` (backend-free, the
`WasmImportDirective` arrangement); the bytecode emission in
`codegen.jvm.JvmExportRuntimeBuilder`. A no-op everywhere else, so one source runs
on all four backends: the interpreter defines it as a return-the-named-symbol
function (`Environment`, beside wasm-export's), and both WASM top-level collectors
skip the form (`WasmLispCompiler` / `NoGcWasmCompiler`, exactly where they collect
wasm-export). Cross-backend pins: `LispEvaluatorTest#jvmExportIsNoOpReturningTheNamedSymbol`,
`WasmLispCompilerIntegrationTest#jvmExportDirectiveIsANoOpOnWasm`. Everything else:
`JvmExportTest` + `JvmExportExampleTest` (compiles
`examples/jvm/kernels-library.lisp` and calls it from Java). User docs:
`doc/{en,ja}/guides/jvm-library.md` + `reference/functions/rontolisp-jvm-export.md`.

**A program with no jvm-export compiles byte-identically to before the feature** —
every mechanism below is gated on `!exportDecls.isEmpty()` (or on `--no-main`,
which requires it), so the flagless corpus (`ci-spec.yaml`,
`.kb/emitted-output-determinism.md`) is untouched.

## The Java type mapping, and the exact-or-throw rule

`BoundaryType` is the shared vocabulary (same designators as wasm-export;
`JvmExportRuntimeBuilder.javaDesc`):

| designator | Java type | guard |
| --- | --- | --- |
| `:s8` `:s16` `:s32` `:s64` | `byte` `short` `int` `long` | none inbound (ranges coincide); result guarded |
| `:u8` `:u16` | `int` | `_exArg` inbound, `_exRes` outbound |
| `:u32` `:u64` | `long` | same; `:u64` values >= 2^63 are unrepresentable in the signed-64 house integer and throw, the WASM boundary's trap |
| `:float` | `double` | result via `checkcast Number; doubleValue` (integer/ratio-as-Number accepted, like wasm's `castFloatGetF64` normalization) |
| `:bool` | `boolean` | `false`=nil (`null`), `true`=`"T"`; any non-nil result is `true` |
| `:string` | `String` | wrapper adds/strips the FRAME QUOTES storage carries |
| `:s-expr` | `String` | in: frame + `_readFromString`; out: `_lispToString` |
| `:bytes` | `byte[]` | copies to/from the packed octet vector `long[]{8, e0, ...}` |
| `:float-vector` `:float-matrix` | `RontoFloatArray` | JVM-ONLY (`jvmOnly()`); ALIASES the packed float array, rank checked -- see below |

The rule is wasm-export's verbatim: **the boundary carries the value exactly, or
it throws** — `IllegalArgumentException` for an argument outside its declared
range, `ArithmeticException` for a result outside it, `ClassCastException` for a
result of the wrong representation (including a BARE string, which is a SYMBOL —
`_exStr` refuses it rather than conflate the two `.kb/core-representation.md`
encodings). **`:string` framing is the invariant that must not regress**: a Lisp
string stores its frame quotes, so an unframed Java `String` passed to the untyped
method is silently mis-read (`GREET("ron")` answered `"hello, o"`) — the wrapper
frames on the way in and unframes on the way out, and
`JvmExportTest#aStringExportFramesOnTheWayInAndUnframesOnTheWayOut` pins it.

An `:s-expr` parameter forces the reader runtime on
(`usesRead |= JvmExportRuntimeBuilder.needsReader`), with read-from-string's full
consequences (`anyNameResolvable` -> every funcId dispatchable).

## The packed float array (`:float-vector` / `:float-matrix`)

The two designators the JVM boundary has and WASM does not: a packed float array
([vec.md](vec.md)) crossing as `am.ik.rontolisp.runtime.RontoFloatArray`, ONE handle class
at every rank and every width. `BoundaryType.FLOAT_VECTOR` / `FLOAT_MATRIX` carry
`jvmOnly()`, which is what makes `WasmExportCompiler.typeDesignator` refuse them by name
(pinned by `WasmLispCompilerIntegrationTest#theJvmOnlyHandleDesignatorsAreRefusedByNameOnWasm`)
instead of failing later in a component lift.

**The measurement is the design, and it is not a tradeoff.** `examples/jvm/bench/`, in the
repo because it is the number the whole of `.todo/501` rests on (2^20 doubles, 300
iterations after 3000 warm-ups, `--simd`, Oracle GraalVM 25.0.4, Linux x86_64):

| | ms/call | vs plain Java |
|---|---|---|
| plain Java loop, C2 auto-vectorized | 0.89 | 1.00x |
| `Kernels.NORM2(packed)` on a pre-packed array | 0.29 | 3.06x |
| **behind the handle** | **0.29** | **3.12x** |
| behind a facade that copies a `double[]` per call | 2.58 | 0.35x |

The copy is ~10x the kernel: **a `double[]`-in/`double[]`-out designator would hand a
caller a slower-than-Java result and call it acceleration.** So the wrapper ALIASES in both
directions -- `RontoBoundary.floatArrayArgument` hands over `handle.packed()`, and
`floatArrayResult` wraps the answered array -- and the only copies are the two the caller
asks for, `of(...)` in and `toArray()` out. `JvmExportTest#aHandleHeldAcrossCallsCopiesOnce`
pins it by object identity, which is the only way to say "no copy" without a profiler.

**Aliasing is therefore the contract, not an implementation detail.** `set(i, v)` through a
handle a kernel returned is visible to a Lisp closure over the same array and vice versa;
nothing is defensively copied, because that copy IS the last row of the table. Say it in
the docs rather than defend against it.

**Both widths, any rank.** `double[]` and `float[]` are disjoint representations and
`.todo/482`-`487` are adding a third, so `RontoFloatArray` dispatches width in ONE private
place (`widthOf`/`headerAt`) and reports it as `Width`, an enum a caller must not assume
has two members. Rank comes from the header, so a matrix is the same class with a rank-2
`dims()`; the designator only says which rank the boundary accepts, and a mismatch throws
there (`IllegalArgumentException` inbound, `ClassCastException` outbound -- the wrapper's
existing split). A declared handle also forces `usesFloatArray` on in `JvmLispCompiler`: a
library whose only contact with the representation is `aref`/`length` over its argument
builds no packed array of its own and would otherwise be emitted without the `_fv*`
accessors.

**Where the handle type comes from** -- the real fork, and the tension is worth naming.
Emitting the type per library the way the acceleration bridges travel
([template-class-embedding.md](template-class-embedding.md)) keeps the artifact
dependency-free, but the RENAME that makes a bridge private to one program is exactly wrong
for a boundary TYPE: two rontolisp libraries would then have two incompatible vector types
and a caller could not feed one's result to the other's kernel, which is the first thing
anyone will try. A shared `rontolisp-runtime` artifact fixes that and is what every JVM
language does -- at the cost of a dependency the artifact does not have today. What ships
takes both: the class files are copied VERBATIM at their canonical names next to the output
class (`JvmExportRuntimeBuilder.RUNTIME_CLASS_FILES` -> `JvmLispCompiler.runtimeClassFiles()`
-> `RontoLispCli`, and `resource-config.json` so the native binary carries them), so one
canonical name makes chaining work while the jar still has no dependency, and identical
bytes make the duplicate harmless. `am.ik.rontolisp.runtime` imports nothing outside
`java.base`, so lifting it into a published artifact later is a packaging change and not a
code motion. The travelling list is hand-kept (nothing can enumerate a package from a
classpath, still less from inside a native image) and pinned against the package's actual
class files by `JvmExportTest#theTravellingClassListIsEveryClassFileOfTheRuntimePackage`;
`package-info.class` deliberately stays behind, since it carries only the build's nullness
annotation.

**`--gpu` residency, and why the handle does not materialize.** The JVM class output has no
read seam and ENUMERATES its readers through `_gpuMaterialize` ([gpu.md](gpu.md), "The two
seams, and what must report through them"); a handle's `get`/`set`/`toArray` are new readers outside that
enumeration. Materializing at the boundary would be correct and would also defeat the lazy
tier -- a result the device still holds would come home only for the next call to re-upload
it. So the handle is wrapped WITHOUT materializing and carries the guard instead: it adopts
the generated class (the wrapper passes `ldc thisClass`), resolves that class's private
`_gpuMaterialize` / `_gpuWritten` once through `MethodHandles`, and reads/writes what the
guard ANSWERS -- the array, or a lazy result stub's backing, since a lazy result's host
array is the HEADER ALONE (`.todo/492`; hence `checkPacked` requires `1 + rank` elements
and not one more). A class with no guards resolves to a marker and costs one reference
comparison. `RontoFloatArrayTest#aHostReadGoesThroughTheOwnerClassResidencyGuard` pins the
whole seam with a stand-in owner class, so it is exercised with no device.
**Not yet confirmed on a device**: that this really keeps a `--gpu` result on the device
across a Java-side chain has to be measured on CUDA and on Metal (the tier was settled
separately, `.todo/492`/`493` and `.todo/494`).

## Exports are tree-shaker roots — the third liveness source

Each wrapper's Java name joins `JvmClassShaker`'s roots next to `main` and the
invisible-edge roots (`_apply`, `handle`, `run`, `call`): its caller is Java code
the bytecode cannot show. This is the directive's whole reason to exist under
`--optimize` (ON by default): without a root, a library's defuns are unreachable
from `main` and shaken away (`--optimize=off` kept the spike's 3 kernels at
316,207 bytes vs 35,939 default). It is the third liveness source beside `main`
and the dispatchable-funcId set of
[optimize-dead-code-elimination.md](optimize-dead-code-elimination.md); the
wrapper's `invokestatic` of the target defun keeps the defun itself and its graph.
Pinned by `JvmExportTest#anExportedDefunSurvivesTheDefaultOptimizeWhileAnUnexportedOneIsShaken`.
(`--no-prune` is the AST library-splice pruner, a different mechanism; a
jvm-export form's quoted symbol already counts as a mention there.)

## The top level runs in `<clinit>` (the reactor precedent)

`defvar`/`defparameter` initialization lives in `_top$0..N`, which only `main`
called — so a typed call arriving first read `null`. With any export, the chunk
calls (plus the raw-octet `System.out.flush()` epilogue and the
`JvmUncaughtHandler` exception table) move into a `_top$run` method that
`<clinit>` invokes LAST, after the ThreadLocal/stream/layout/struct seeding;
`main` (when kept) emits only `return` — invoking it triggers `<clinit>` first
(JVMS class initialization), and the JVM's own init locking is the idempotence, so
no `_inited` guard exists on either path. This is the cross-backend answer: the
`--no-wasi` reactor "runs its top level at instantiation", and `<clinit>` is the
JVM's instantiation. Two consequences are documented rather than designed around
(both true of the reactor too): a top-level form that signals surfaces as
`ExceptionInInitializerError` — after `_top$run`'s uncaught handler prints the
one-line report — and poisons the class permanently
(`JvmExportTest#aTopLevelThatSignalsSurfacesAsExceptionInInitializerErrorOnTheFirstTypedCall`),
and a top-level `uiop:quit` kills the caller's JVM.

## `--no-main`

Names the library mode on the `--no-wasi` precedent: drop `main`, the class is
entered through exports only. `JvmLispCompiler.noMain(boolean)`;
`CliOptions.noValueKeys` + `RontoLispCli` route it, refusing a non-`.class`
output and (in the compiler, so an embedder gets it too) a program with no
jvm-export — `main` is the only shake root such a program has, so a main-less
export-less class would shake to nothing. Kept ORTHOGONAL to the directive: a
program may want both a `main` and exports (a CLI tool that is also a library),
and only the flag says which. No `Main-Class` consequences yet (that is
`.todo/505`'s jar work). While here: `-o com/acme/Kernels.class` now creates the
missing parent directories (`RontoLispCli`), since the `-o` path IS the package.

## Validation (all at compile time, in `JvmLispCompiler` after Pass 1)

- unknown / non-defun target, arity mismatch — wasm-export's checks, same wording;
- a variadic target (`&optional`/`&rest`/`&key` desugars to variadic) is refused:
  it has no fixed Java signature;
- the wrapper name must be a Java identifier and non-keyword
  (`JvmExportDirective.isJavaMethodName`; the default derivation is the Lisp
  member lower-camel-cased, `:as` overrides), must not start with `_` (the
  generated runtime's namespace) or be `main`, must not equal another export's
  name or any MANGLED defun name — a duplicate method name is the
  `ClassFormatError`-at-load family `.kb/core-representation.md` records for
  redefined defuns.
