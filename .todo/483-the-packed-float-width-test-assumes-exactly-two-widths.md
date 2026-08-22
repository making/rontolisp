# 483. The packed-float width test assumes exactly two widths, by `instanceof`

Difficulty: Medium

Prerequisite for `.todo/482` (`bfloat16`), but a defect in its own right and landable
alone: it is a pure refactor that adds no type and changes no behavior.

`LispFloatArray` is a sealed umbrella, so the *right* way to ask a packed array its width
is an exhaustive `switch` -- which the compiler then re-checks when a permit is added.
28 sites do that. **70 do not**: they ask `instanceof LispSingleFloatArray` and treat the
negative answer as "double", or cast straight to one of the two records.

```
src/main/java/am/ik/rontolisp/eval/LinalgSimd.java   23
src/main/java/am/ik/rontolisp/eval/LinalgGpu.java    21
src/main/java/am/ik/rontolisp/eval/VecSimd.java      20
src/main/java/am/ik/rontolisp/eval/LinalgBlas.java    3
src/main/java/am/ik/rontolisp/eval/Environment.java   3
```

The shape is always the same, e.g. `LinalgBlas`:

```java
boolean single = a instanceof LispSingleFloatArray;
...
return new LispSingleFloatArray(c, dims);   // else the double branch
```

A third width therefore does not fail to compile -- it takes the `false` arm and reaches
`((LispDoubleFloatArray) a).data()`, which is a `ClassCastException` at best and a
misread buffer where the cast is on a `data()` result rather than the value. Nothing in
the type system objects, which is exactly the failure mode sealed types exist to prevent.

## Do

Convert every one of the 70 to an exhaustive `switch` over `LispFloatArray` with no
`default` (so the compiler reports each site when `.todo/484` adds the permit), or -- for
the several sites that genuinely only need "is this the same width as that one" -- to a
comparison of `elementType()`. Keep the emitted code shape identical; this must not move
a single number in `LinalgSimdTest` / `VecSimdTest` / the BLAS and GPU tests.

Where a site legitimately supports only some widths (BLAS has no bf16 GEMM, the device
kernels are f32/f64), the exhaustive switch is what makes the unsupported arm an explicit
`null`/decline rather than a fallthrough -- which is what `.todo/486` then relies on.

## Verify

`./mvnw spring-javaformat:apply test`, and the accelerated suites, must be unchanged.
The real check is the next commit's: after `.todo/484` adds `LispBFloat16Array` to the
`permits` clause, `./mvnw compile` must list every site that needs a decision, and no
site may be missing from that list.
