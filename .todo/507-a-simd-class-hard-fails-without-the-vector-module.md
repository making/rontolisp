# A `--simd` class hard-fails without `--add-modules jdk.incubator.vector`

Difficulty: Low

Filed 2026-08-24 from the `.todo/501` spike. Independent of the rest of that item, and
more urgent because of it: a library's consumer did not choose the flag the library was
built with.

## The asymmetry

The interpreter degrades, with a line telling you how to fix it:

```
$ java -jar rontolisp-exec.jar kernel.lisp --simd
rontolisp: warning: --simd: jdk.incubator.vector is unavailable, running the scalar
vec:/linalg: kernels; re-run with `java --add-modules jdk.incubator.vector -jar ...`,
or use the native binary.
5.0
```

A `--simd` compiled class does not:

```
$ java -cp . Kernel
Exception in thread "main" java.lang.NoClassDefFoundError: jdk/incubator/vector/Vector
	at java.base/java.lang.invoke.MethodHandles$Lookup.defineClass(MethodHandles.java:1753)
	at Kernel._simdInit(Unknown Source)
	at Kernel.NORM2(Unknown Source)
```

`_simdInit`'s `Lookup.defineClass` of the embedded `RontoLispSimdBridge` resolves the
template's `jdk.incubator.vector` references at definition time, and there is no fallback
behind it. Today that is a `java Prog` the same person compiled, so it is merely rude.
Once the class ships as a jar (`.todo/505`, `.todo/506`) the person who hits it did not
compile it and has no reason to know which flag was used.

## The decision to make

The scalar `vec:`/`linalg:` defuns are already in the class -- `JvmLinalgKernelCompiler`
emits a CHAIN of attempts ending at the scalar defun (`.kb/linalg-simd.md`), which is
exactly the fallback rung this needs. So the choice is what `_simdInit` does when the
define fails:

1. **Degrade** -- catch `NoClassDefFoundError`/`LinkageError`, leave the bridge unbound,
   let the chain fall through to the scalar defun, and print the interpreter's warning
   once to `*error-output*`. Matches the interpreter, matches `--blas`/`--gpu` (a machine
   with no library or no device "runs the same programs, unaccelerated" -- that is the
   documented contract for both), and is therefore the consistent answer.
2. **Fail with a message that names the flag**, if a silent 3x slowdown is judged worse
   than a stop.

(1) is recommended: `--simd` is the only one of the four accelerated flags that currently
hard-fails on an unequipped runtime, and the other three already state the opposite
contract in `--help`.

Check `--parallel` at the same time (it is a `--simd` modifier, so it degrades with it).
`--gpu` is already CLEAR on both counts and needs nothing here -- verified 2026-08-24 on a
machine with no CUDA and no Metal: a `--gpu` class answers correctly (unaccelerated) both
with and without `--enable-native-access=ALL-UNNAMED`, so the missing device and the
missing FFM permission are both already degradations rather than failures. `--blas` is
clear too (it binds a real OpenBLAS when there is one and runs unaccelerated when there is
not). **`--simd` is the only one of the four that hard-fails**, which is the whole reason
this item exists.

## Acceptance

A `--simd` class runs correctly on a JVM without the incubator module, at scalar speed,
having said so once; a test pinning it (run the compiled class in a fresh JVM without the
flag and assert the answer plus the warning). `--help`'s `--simd` block and
`doc/{en,ja}/guides/simd-acceleration.md` say what happens, the way the `--blas` and
`--gpu` blocks already do. `.kb/linalg-simd.md`'s per-backend table gains the row.
