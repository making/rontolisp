# i04. Missing standard operators: `character`, `(compile 'name)`

Difficulty: Low

Measured 2026-11-04 on the interpreter, JVM and WASM preview 1.

- **`character` is not defined.** `(character "a")`, `(character #\b)`, `(funcall 'character 'c)`:
  `The function CHARACTER is undefined`; the JVM throws it at the call, wasm traps. CLHS: a character
  designator (character, one-character string, one-character symbol) to the character. Consumer:
  cl-emb's `emb.lisp:259`, at load, so caveman2 does not load.
- **`(compile 'name)` with one argument is refused.** `COMPILE expects 2 arguments, got 1`; the
  compile paths warn and compile a call-time program error. CLHS: `definition` is optional, and with
  a name the current definition is compiled; the answers are the function, plus the two failure
  values. Consumer: cl-jpeg's `jpeg.lisp:350-351` (`(compile 'qat1)`), at load, so opticl does not
  load. `.todo/042` lists `compile` only for its two-argument form.

Follow `.kb/adding-primitives.md`. A ci-spec case with `character` on all three designators and the
`compile` values. `(character "ab")` signals a `type-error` as `(character 1)` does.
