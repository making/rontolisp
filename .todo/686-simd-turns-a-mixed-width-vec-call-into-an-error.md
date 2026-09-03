# 686. `--simd` turns a mixed-width `vec:` call into an error the scalar path answers

Difficulty: Low

Found 2026-09-03 while working `.todo/484`, which had to answer the same question for a
new width and found this beside it.

`vec.lisp`'s `vec::%map2` reads both operands through `aref`, and `aref` on a packed
float array widens to `double` whatever the storage width is. So the scalar path
**computes a mixed-width call and returns an answer**:

```lisp
(vec:add #f(1.0 2.0) #d(3.0 4.0))   ; scalar: answers
(vec:add #f(1.0 2.0) #d(3.0 4.0))   ; under --simd: signals, from mixedWidth(...)
```

`VecSimd` has 16 `throw mixedWidth(...)` sites and no way to hand the call back, so the
flag decides whether the program runs. **`--simd` is a speed flag; it must not be a
correctness flag.** That invariant is what every cross-backend bit-identity pin in
`.kb/vec.md` exists to protect, and this is a hole in it that predates any of the widths
now being added.

## Do

`.todo/484` installs the mechanism this needs: `VecSimd.defineFn` captures the scalar
defun before overriding it (`globalEnv.lookupFunctionOrNull`) and passes the call through
when the kernel answers `null`, the shape `LinalgSimd.define()` has always had. With that
in place this item is small:

- Turn the 16 `throw mixedWidth(...)` into `return null`, so the scalar defun answers.
- Delete `mixedWidth` if nothing else calls it.

The scalar defun is then the single definition of what a mixed-width call means, and
`--simd` only ever changes how long it takes.

## Verify

- For every `vec:` operator that has a mixed-width site, the result under `--simd` is
  bit-identical to the result without it -- for `#f`/`#d`, and (once `.todo/484` lands)
  for `#bf16` against both. Add the rows to whatever table already pins flag-invariance
  rather than starting a new test class: a fourth width should extend one table, not add
  a fourth place to look.
- No number moves for same-width operands: this touches only the arms that used to throw.
- `./mvnw -Pweb compile` after the suite (with a `clean` in between), because
  `src/web/java/.../Target_VecSimd.java` substitutes `VecSimd.install` and a signature
  drift there is caught only by the Pages workflow's Web Image build.

## Not in scope

Whether `linalg:` has the same hole. It uses the declined-input protocol already, so its
mixed-width behaviour is whatever the scalar defun does -- but that is an assumption
written from the outside, not a measurement. Check it, and if it holds, say so here.
