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
| `.todo/503` | `rontolisp:jvm-export` + `--no-main`: a declared, typed, Java-callable entry point, and the library mode it implies (the `wasm-export` / `--no-wasi` twin) |
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

## The consumption strategy: one story, two entry points

Not "two alternatives" -- they are the same artifact reached from the two situations a
Java project is actually in, and `.todo/506` is the primary one.

**In-project (the default, `.todo/506`).** The Lisp is another source set. Nothing about
packaging changes, because Maven already knows how to package `target/classes`:

```
pom.xml                                  <- one <plugin> block
src/main/lisp/com/acme/Kernels.lisp      <- kernels + (rontolisp:jvm-export 'norm2 ...)
src/main/java/app/App.java               <- Kernels.norm2(vec)
$ mvn package                            <- one jar, Java and Lisp classes together
```

This is what every JVM-language plugin does (kotlin-maven-plugin, scala-maven-plugin) and
it is the shape a Java developer expects. `mvn install` / `deploy` / a Gradle consumer /
an IDE all work with no further concepts, and the Lisp source ships in the same repo as
the code that calls it -- which is the case that makes the kernels worth writing in
rontolisp at all.

**Shipped-library (`.todo/505`).** When the kernel library is built separately from its
consumers -- a team publishing kernels to other teams, a non-Maven consumer, anything
being pushed to a repository -- the CLI produces the jar directly and the coordinates ride
inside it:

```
$ rontolisp kernel.lisp -o acme-kernels-1.0.0.jar \
      --class-name com.acme.Kernels --maven-coordinates com.acme:acme-kernels:1.0.0 \
      --no-main --simd
$ mvn install:install-file -Dfile=acme-kernels-1.0.0.jar     # no -DgroupId, no -DpomFile
```

Then it is an ordinary `<dependency>`. Verified end to end in the spike.

**What a consumer needs beyond the jar**, and it is short: nothing for a scalar/string
export; `rontolisp-runtime` for an array export (`.todo/504` -- the handle types have to
be shared or two libraries cannot chain); `--add-modules jdk.incubator.vector` for a
`--simd` build until `.todo/507` makes that degrade instead of fail. `--blas` and `--gpu`
need nothing at build time and degrade on their own at run time already.

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

**What the measurement says.** 2^20 doubles, 300 iterations after 3000 warm-up calls,
Oracle GraalVM 25.0.4:

| | ms/call | vs plain Java |
|---|---|---|
| plain Java loop (C2 auto-vectorized) | 0.90 | 1.0x |
| `Kernels.NORM2` on a pre-packed `double[]` | **0.27** | **3.3x** |
| the same behind an opaque handle (`.todo/504`'s design) | **0.27** | **3.3x** |
| the same behind a facade that copies `double[]` per call | 2.67 | 0.34x |

The kernel is 3.3x the hand-written Java loop, and **the handle design keeps all of it** --
the extra `getfield` is below measuring noise. The last row is the naive API measured in
order to RULE IT OUT: a per-call copy is ~10x the kernel and turns the 3.3x win into a 3x
loss. So the target of this whole item is the 3.3x, and `.todo/504` is how it is kept.

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
   picks the answer. **The JVM has the same two shapes wasm does -- a command and a
   library -- and only the first one today**, so `--no-main` names the second exactly as
   `--no-wasi` names the reactor; `.todo/503` carries it.
5. **The value representation leaks, and it leaks SILENTLY.** A string carries its frame
   quotes as storage (`.kb/core-representation.md`), so `GREET("ron")` -- a plain Java
   `String` -- answered `"hello, o"`: it read the `r` and `n` as the frame and took the
   middle. Not an exception, a wrong answer. `nil` is `null`, `t` is `"t"`, an integer is
   `Long`, a general array is an `ArrayList` whose slot 0 is an `Object[]` header. A
   typed boundary is not a convenience over this; it is the only safe shape.
6. `-o com/acme/Kernels.class` does not create `com/acme/`
   (`NoSuchFileException`). One `Files.createDirectories` in `RontoLispCli`.

## Where this can be built

**No GPU needed for any of it except one paragraph.** Verified 2026-08-24 on Linux x86_64
with no CUDA and no Metal (OpenBLAS present, Vector API available):

- `.todo/502` is fully reproducible and verifiable here for ALL FOUR bridges. The failure
  is at `Lookup.defineClass`, which happens before any device probe -- a packaged `--gpu`
  class dies with `RontoLispGpuGpuDevice not in same package as lookup class` on a
  device-less machine, and the same program in the default package answers correctly
  (unaccelerated). So the fix and its test need no device.
- `.todo/503`, `.todo/505`, `.todo/506`, `.todo/507`, `.todo/508` touch no device path at
  all. `--simd` and `--blas` both run for real here, which covers the acceleration story
  end to end.
- **The one exception**: `.todo/504`'s "`--gpu` residency" paragraph -- whether a handle a
  `--gpu` kernel returns forces a materialization the next call would only re-upload.
  Designing and implementing the handle needs nothing; confirming it does not defeat the
  resident/lazy tier (`.todo/492`/`494`) needs a device. Build it here, verify that one
  interaction on the GB10.

## Acceptance

A rontolisp `.lisp` library, declaring its exports, compiled under `--optimize` with
`--simd`, delivered as a jar carrying Maven coordinates, consumed by a Maven project that
calls a typed method and gets the kernel's speed -- with none of the six workarounds
above, and every step covered by a test rather than by this file.
