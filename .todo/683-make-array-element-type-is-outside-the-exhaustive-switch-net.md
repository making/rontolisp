# 683. `make-array :element-type` dispatches on a string, outside 483's net

Difficulty: Low

Found 2026-09-03 while closing `.todo/483` (the packed-float width test assumes exactly
two widths). 483 converted every `instanceof LispSingleFloatArray` width test to an
exhaustive `switch` over the sealed `LispFloatArray`, so that adding a permit reports
each site as a compile error. The probe that proved it -- a throwaway third permit plus
`./mvnw compile` -- reported 86 sites across five files and nothing else.

**One width test is outside that net, and the compiler cannot be made to report it.**
`Environment.packedFloatElementType` resolves a `make-array :element-type` designator by
comparing the symbol's local name against two string constants and returning a `String`:

```java
if (local.equals(LispNames.DOUBLE_FLOAT))  return LispNames.DOUBLE_FLOAT;
if (local.equals(LispNames.SINGLE_FLOAT))  return LispNames.SINGLE_FLOAT;
return null;                                 // <- a third width lands here
```

and the allocation site then branches `packedType.equals(LispNames.SINGLE_FLOAT)` with
the double array as the `else`. A third width returns `null` from the resolver, so
`make-array ... :element-type 'bfloat16` **silently builds a boxed general array**: no
exception, no compile error, and `array-element-type` answers `t`. That is the exact
failure mode sealed types exist to prevent, in the one place 483 could not reach.

`.todo/484` step 5 adds the `bfloat16` case here by hand, which closes it for that width
and leaves the mechanism unchanged: the fourth width (fp8, or `.todo/672`'s Q8_0 if it
ever names an `:element-type`) repeats the bug.

## Do

The name -> width direction genuinely cannot be a compile error -- a new name is new
source text, and no switch can demand it. What *can* be bought is that the two halves can
never disagree, and that a missing wiring fails loudly:

1. **Derive the name table from the arrays themselves.** Each permit already answers
   `elementType()`. Resolve the designator by matching against those answers rather than
   against a private pair of string constants, so the name `make-array` accepts is by
   construction the name `array-element-type` reports back.
2. **Make the width -> allocation direction an exhaustive `switch`** over `LispFloatArray`
   with no `default`, the way the other 86 sites now are, so a permit added without an
   allocation arm is a compile error. The natural shape is a zero-length prototype per
   permit and a `switch` on it; keep the emitted allocation identical.
3. **Pin the reachability with a reflective test.** `LispFloatArray.class.
   getPermittedSubclasses()` enumerates the permits exactly and automatically; assert for
   each that `(make-array '(2) :element-type '<its elementType()>)` yields that class and
   that `array-element-type` answers the same name. A permit added without wiring
   `make-array` turns this test red -- which is the loud failure the compiler cannot give.

**There is a third of these, and it is in Lisp.** Found 2026-09-03 while working
`.todo/484`: `vec.lisp`'s `vec::%make` / `vec::%make-like` choose the packed
representation with `(eq element-type 'single-float)` and treat everything else as
double. No compiler helps there at all -- not even the one that would have caught the
Java sites -- so `vec:zeros` / `ones` / `arange` and the width-preserving element-wise
kernels silently miss a new width until someone notices the type they got back. The
reflective test in step 3 should cover the `vec:` constructors too, not only
`make-array`: it is the same assertion (every permit is reachable, and answers its own
element type) through a different door.

Do the same audit for `packedIntegerElementType` next to it (`(unsigned-byte 8|16|32)`):
it has the same shape and the same silent `null`, and `LispIntVector` may or may not be
sealed. Report what is there rather than assuming.

## Verify

- Every permitted subclass of `LispFloatArray` is reachable through `make-array`
  `:element-type`, by the reflective test above -- with `.todo/484` landed, that is three.
- `array-element-type` on each answers the name that built it.
- No number moves in `LinalgSimdTest` / `VecSimdTest` / the BLAS and GPU tests: this is a
  refactor plus a test, not a behavior change.
- Adding a throwaway fourth permit makes `./mvnw compile` report the allocation site
  (repeat 483's probe), and the reflective test fails rather than passing silently.

## Order

After `.todo/484` (which supplies the third permit this is worth testing against) and
after `.todo/485`. Not a prerequisite for anything in `.todo/482` or `.todo/670`.
