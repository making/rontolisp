# Export a rontolisp library the JVM ecosystem can consume

Difficulty: High

Parent item. Filed 2026-08-24 after a spike (below) that took a `vec:` kernel library all
the way from `.lisp` to `Vec.norm2(double[])` called out of a plain Maven project -- and
measured 3x FASTER than the equivalent hand-written Java loop. Every step of that path
works today; not one of them is a supported path. This item is the umbrella, the children
are the work:

| item | what |
|---|---|
| `.todo/502` | the embedded bridges hardcode the default package, so `--simd`/`--blas`/`--gpu`/`java:` die in a packaged class -- **the prerequisite blocker** |
| `.todo/503` | `rontolisp:jvm-export`: a declared, typed, Java-callable entry point (the `wasm-export` twin) |
| `.todo/504` | the packed float array as a Java boundary type -- a copying boundary costs 8x the kernel |
| `.todo/505` | `-o out.jar`: a consumable jar carrying its own Maven coordinates |
| `.todo/506` | `rontolisp-maven-plugin`: compile `src/main/lisp` into `target/classes` |
| `.todo/507` | a `--simd` class hard-fails without `--add-modules jdk.incubator.vector` instead of degrading |

## Why

`-o Prog.class` today has exactly one consumer: `java Prog`. That was the whole intent
when it landed. What changed is what is now IN those classes: `--simd` routes `vec:` /
`linalg:` at `jdk.incubator.vector`, `--blas` at a tuned CBLAS, `--gpu` at CUDA or Metal
-- and writing those kernels in rontolisp is markedly less work than writing them in Java
against the Vector API or the FFM API by hand. The kernels are the asset; the `.class` is
a jail. A Java caller cannot get at them, and a `.class` in the default package cannot be
consumed by a Maven project even if it could.

## The spike (2026-08-24, all of it verified, none of it kept)

Library file, no top-level call, compiled `-o com/acme/Kernels.class --simd`:

```lisp
(defvar *eps* 1.0d-12)
(defun norm2 (x) (sqrt (vec:dot x x)))
(defun axpy (alpha x y) (vec:add (vec:scale x alpha) y))
(defun normalize (x) (let ((n (norm2 x))) (if (< n *eps*) x (vec:scale x (/ 1.0 n)))))
```

**What already works, for free.** `-o com/acme/Kernels.class` already produces
`public class com.acme.Kernels` -- `RontoLispCli` passes `outputFile.replace(".class","")`
to `JvmLispCompiler` verbatim, so a path separator in the `-o` argument IS a package. It
runs (`java -cp . com.acme.Kernels`), it jars, and a `defun` is already
`public static Object NAME(Object, ...)`, so `Kernels.NORM2(x)` is a plain `invokestatic`
from Java source with no reflection. The packed float array is already a bare `double[]`
with a `[rank, dim..., data...]` header (`.kb/vec.md`), so the numeric boundary is nearly
zero-copy by construction. A hand-written 30-line facade + `jar` + `mvn install:install-file`
gave a plain Maven project `Vec.norm2(new double[]{3,4}) == 5.0` with a `--simd` kernel
underneath.

**What the measurement says.** 2^20 doubles, 200 iterations, Oracle GraalVM 25.0.4:

| | ms/call |
|---|---|
| plain Java loop (C2 auto-vectorized) | 0.90 |
| `Kernels.NORM2` on a pre-packed `double[]` | **0.30** |
| the same through a facade that copies `double[]` in | 2.5-2.9 |

The kernel is 3x the hand-written Java loop. A boundary that copies is 8x the kernel and
turns that 3x win into a 3x loss. **The API shape is therefore not a taste question** --
`.todo/504`.

**The six things that make it unsupported.** Each is a child item above except the last
two, which are notes for whoever takes `.todo/503`:

1. `--simd` / `--blas` / `--gpu` / `java:` all die the moment the class is in a package:
   `IllegalArgumentException: RontoLispSimdBridge not in same package as lookup class`.
   `.todo/502`.
2. Under `--optimize` (ON by default) every defun unreachable from `main` is shaken away:
   all three kernels above are GONE. `--optimize=off` keeps them at **316,207 bytes**
   against **35,939**, for three methods. `--no-prune` does not help (it is the AST-level
   library splice pruner, a different mechanism). `.todo/503` -- exports must be shaker
   roots.
3. The method name is the raw Lisp name: `SCALED-SUM` is a legal JVM method name and NOT a
   Java-source-callable one, so most real names are reflection-only. `mangleMethodName`
   maps `/ < > <= >= : . %` and leaves `-` alone. `.todo/503` -- `:as`.
4. `defvar` initialization lives in `_top$0`, which only `main` calls, so a static call
   that arrives first sees `null`: `SCALED-SUM(1,2)` throws
   `NPE: ... because the return value of "_big(Object)" is null`. `.todo/503` -- the
   reactor component "runs its top level at instantiation"
   (`.kb/wasm-export-no-wasi.md`), and `<clinit>` is that, so cross-backend consistency
   picks the answer.
5. **The value representation leaks, and it leaks SILENTLY.** A string carries its frame
   quotes as storage (`.kb/core-representation.md`), so `GREET("ron")` -- a plain Java
   `String` -- answered `"hello, o"`: it read the `r` and `n` as the frame and took the
   middle. Not an exception, a wrong answer. `nil` is `null`, `t` is `"t"`, an integer is
   `Long`, a general array is an `ArrayList` whose slot 0 is an `Object[]` header. A
   typed boundary is not a convenience over this; it is the only safe shape.
6. `-o com/acme/Kernels.class` does not create `com/acme/`
   (`NoSuchFileException`). One `Files.createDirectories` in `RontoLispCli`.

## Acceptance

A rontolisp `.lisp` library, declaring its exports, compiled under `--optimize` with
`--simd`, delivered as a jar carrying Maven coordinates, consumed by a Maven project that
calls a typed method and gets the kernel's speed -- with none of the six workarounds
above, and every step covered by a test rather than by this file.
