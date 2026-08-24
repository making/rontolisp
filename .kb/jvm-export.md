# `rontolisp:jvm-export` (typed Java-callable methods) + `--no-main` library mode

`(rontolisp:jvm-export 'name :params '(T...) :returns T :as "javaName")` is the JVM
twin of `rontolisp:wasm-export` ([wasm-export-no-wasi.md](wasm-export-no-wasi.md)):
it declares the Java-boundary types of a top-level `defun` so `JvmLispCompiler`
emits a thin `public static` wrapper with a primitive/`String`/`byte[]` signature
next to the untyped `(Object...)Object` method. Directive parsing + the Java-name
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
