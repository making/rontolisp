# `rontolisp:jvm-export`: a declared, typed, Java-callable entry point

Difficulty: High

Filed 2026-08-24 from the `.todo/501` spike (read its "six things" list -- items 2-5 are
this item). Depends on `.todo/502`. The array boundary is `.todo/504`; this item can land
with the scalar and string designators and be useful on its own.

## The shape

The twin of `rontolisp:wasm-export`, which already solves the identical problem on the
other backend: a `defun` whose arguments and result are internal representations no host
can construct, made callable by DECLARING its boundary types so the compiler can emit a
thin typed wrapper. Read `codegen/wasm/WasmExportCompiler` first -- the directive, the
`Decl` record, `:as`, the trapping-not-masking conversion rule and
`.kb/wasm-export-no-wasi.md` are all reusable, and `compiler/BoundaryType` is already
backend-free and lives in `compiler`.

```lisp
(rontolisp:jvm-export 'norm2  :params '(:float-vector) :returns :float)
(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float :as "scaledSum")
```

Emitted onto the SAME class, next to the untyped `NORM2(Object)`:

```java
public static double norm2(double[] x);
public static double scaledSum(double a, double b);
```

One class, not a generated facade class -- everything else in this backend emits exactly
one `.class`, and a second one would have to be carried the way the bridges are.

## The four things it has to do

**1. Be a tree-shaker root.** `JvmLispCompiler`'s shake roots are `Set.of("main")` plus
the four invisible-edge additions (`_apply`, `handle`, `run`, `call`) at
`JvmLispCompiler:3126`. Every export name joins them. This is the whole reason the
directive earns its keep: measured on the spike's three-kernel library, `--optimize`
(default, ON) keeps **0 of 3** defuns at 35,939 bytes and `--optimize=off` keeps 3 at
**316,207**. With export roots, a library gets the default's size AND its exports.
`--no-prune` is a different mechanism (the AST library-splice pruner) and does not help.
The WASM twin `dispatchableFuncIds` has the "the two backends must agree about which
designator still resolves" contract in `.kb/optimize-dead-code-elimination.md`; an
export root is a new, third source of liveness and that file has to say so.

**2. Give the method a Java-legal name.** `mangleMethodName` maps `/ < > <= >= : . %` and
leaves `-` alone, so `scaled-sum` becomes the method `SCALED-SUM` -- legal in a class file,
not nameable from Java source, reflection-only. `:as` is the answer (`wasm-export` already
has it, for the component-model label grammar). Default when `:as` is absent: the Lisp
name lower-camel-cased, which is what a Java caller expects and what the WIT side already
does in the other direction. **The directive must reject an `:as` that collides with a
mangled defun name** -- `(jvm-export 'norm2 :as "NORM2")` would be a duplicate method
name, which is a `ClassFormatError` at LOAD time, the same failure mode
`.kb/core-representation.md` records for redefined defuns.

**3. Run the top level before the first export call.** `defvar`/`defparameter`
initialization is in `_top$0..N`, which only `main` calls, so a static call arriving first
reads `null` -- the spike got
`NPE: ... because the return value of "_big(Object)" is null` out of `SCALED-SUM(1,2)`.
**Recommended answer: `<clinit>`**, on the cross-backend precedent -- the reactor
component "runs its top level at instantiation" (`.kb/wasm-export-no-wasi.md`), and
`<clinit>` is the JVM's instantiation. Emit the `_top$N` calls into `<clinit>` when the
program carries any `jvm-export` (a `<clinit>` already exists on some paths for the
stream-global and layout-pool seeding, so this is an append, not a new method), and stop
`main` from re-running them. Two consequences to state in the docs rather than design
around: a top-level form that throws becomes `ExceptionInInitializerError` and poisons the
class permanently, and a top-level `exit` kills the caller's JVM. Both are true of the
reactor too.

**4. Marshal, and never silently mis-marshal.** `BoundaryType`'s existing designators map
cleanly (`:s8`..`:u64` -> `byte`/`short`/`int`/`long`, `:float` -> `double`, `:bool` ->
`boolean`, `:bytes` -> `byte[]`, `:string`/`:s-expr` -> `String`); the packed-array
designator is `.todo/504`. **`:string` is the one that must not be got wrong.** A string
carries its frame quotes as STORAGE on this backend
(`.kb/core-representation.md`), so the wrapper frames on the way in and unframes on the
way out -- and the untyped path's silent failure is the argument for the whole item:
`GREET("ron")` answered `"hello, o"`, having read the `r` and `n` as the frame quotes.
Keep `wasm-export`'s rule for the numeric conversions: **the boundary carries the value
exactly or it throws**, nothing masked or wrapped.

Also fix, while here: `-o com/acme/Kernels.class` does not create `com/acme/`
(`NoSuchFileException` from `RontoLispCli`).

## Not in scope

A `defun` taking `&optional`/`&rest`/`&key`, a closure, a hash table, a CLOS instance or a
multiple-values result. `wasm-export` declines those too; the directive should say so with
a message naming the parameter, not emit a wrapper that lies.

## Acceptance

`JvmLispCompilerTest` cases per designator (round-trip and out-of-range-throws), one
pinning that an exported defun survives `--optimize` while an unexported one does not, one
pinning the `:as` collision refusal, and one pinning that a `defvar` an export reads is
initialized without `main` having run. A `ci-spec.yaml` case is NOT the vehicle here (the
harness runs one program per backend and slices stdout; there is no Java caller in it) --
an `examples/jvm/` program plus a test that compiles it and calls it from Java is. Docs:
a `reference/` page next to the `wasm-export` one, plus a `doc/{en,ja}/guides/` page that
is the story `.todo/501` tells. `.kb/` gets a `jvm-export.md` naming the shaker-root, the
`<clinit>` and the string-framing invariants, cross-linked from
`.kb/wasm-export-no-wasi.md` as its twin.
