# A string the compiled backends did not build mutable has no identity, so a write to it is invisible to every alias

Difficulty: High

Found 2026-08-28 while doing `.todo/544` (the displaced string view), which is
blocked by exactly this and nothing else.

A Common Lisp string is a MUTABLE sequence with identity: two variables holding
the same string see each other's writes. The interpreter has that (`LispString`
is one object with an `int[]` buffer). The compiled backends have it only for
the mutable CHARACTER VECTOR shape -- what `make-string` and
`(make-array n :element-type 'character ...)` build. Every other string is an
immutable VALUE: a Java `String` on the JVM, a `TYPE_STRING` whose bytes never
change on WASM (`.kb/wasm-gc-strings.md`, `.kb/string-write-runtime.md`). A
write to one cannot happen, so `%schar-set-runtime` rebuilds the string and the
expansion `setq`s it back into the variable the place named
(`LispMacroExpander.expandScharSetFunctional`).

Measured on all four backends (2026-08-28):

```lisp
(let* ((s (copy-seq "abcdef")) (a s))
  (setf (char s 0) #\X)
  (list s a))
;; SBCL / interpreter: ("Xbcdef" "Xbcdef")
;; JVM / WASM P1 / WASM component: ("Xbcdef" "abcdef")

(let ((s (make-string 3 :initial-element #\a))) ...)   ; aliases correctly everywhere

(defun f (x) (setf (char x 0) #\Z) x)
(let ((s (copy-seq "hello"))) (list (f s) s))
;; SBCL / interpreter: ("Zello" "Zello")
;; the three compiled backends: ("Zello" "hello")   ; a callee cannot write a string at all
```

The third case is the sharpest: a function that mutates a string argument is a
NO-OP for its caller on every compiled backend, silently.

## What this blocks

- `.todo/544`'s definition of done, verbatim: a string view over
  `(copy-seq "abcdef")` writes through to `s` on the interpreter and cannot on
  the compiled backends, where the view PROMOTES its target to a private
  character vector instead (`.kb/adjustable-arrays.md`, "Displacing a STRING").
  That promotion is the best answer available under an immutable target, and the
  two pinning tests
  (`compileDisplacedStringViewOverAnImmutableStringPromotesOnWrite`, JVM +
  WASM) exist to FAIL when this todo lands, so nobody has to remember.
- Every library that mutates a string it did not allocate itself.

## What to implement

Give every string on the compiled backends a mutable identity. The obvious
lever is that the character-vector representation ALREADY has one and every
string op already normalizes through `_strv` / `_charvec_to_str`, so the
question is not "can it" but "what does it cost": make `copy-seq` / `subseq` /
`concatenate` / `string-upcase` / ... answer a character vector and every later
op on the result re-renders it, O(n) per op (`.todo/343` is the memoization
that would pay for this, and is probably a prerequisite rather than a
follow-up). Measure before choosing; a literal must stay immutable either way
(mutating one is undefined in CL and the compiled literal is shared).

Do NOT "fix" it by making `expandScharSetFunctional` write further. The setq is
a symptom.

## Definition of done

The three programs above answer identically on all four backends, matching
SBCL, with a ci-spec case and the two promote-on-write tests updated (or
deleted) to say so.
