# `mapc`/`mapcan`/`maplist`/`mapcon` over more than one list diverge per backend

Found while making alexandria a first-class loadable system (2026-07-30), next
door to the `#'mapcar` wrapper fix that shipped in the same pass. Pre-existing:
`mapcar` is the only member of the family that ever accepted N lists.

## Symptom

Measured on `9cd78488` + the `#'mapcar` wrapper fix, three different answers for
one form:

```lisp
(mapc (lambda (a b) (print (list a b))) '(1 2) '(3 4))
;; interpreter : LispEvalException: MAPC expects 2 arguments, got 3
;; JVM         : prints nothing, returns (1 2)     <- silently wrong
;; WASM        : `unreachable` trap

(mapcan #'list '(1 2) '(3 4))
;; interpreter : MAPCAN expects 2 arguments, got 3
;; JVM         : (1 2)                             <- CL says (1 3 2 4)
(maplist #'list '(1 2) '(3 4))
;; JVM         : (((1 2)) ((2)))                   <- second list ignored
(mapcon #'list '(1 2) '(3 4))
;; JVM         : ((1 2) (2))                       <- second list ignored
```

`mapcar` (and, since this pass, `#'mapcar` as a value) is correct on all four
backends; every other member takes the function plus exactly one list.

## Why it matters

The JVM leg is the bad one: a wrong list with no diagnostic. That is the same
class of bug the `#'mapcar` wrapper had -- and the reason it was found at all is
that `alexandria:mappend` walked into it. Nothing loadable calls multi-list
`mapcan`/`maplist` today, but the failure mode is invisible when something does.

## Two decisions to take, in order

1. **Make the wrong answers loud first** (cheap, and independent of 2): the
   compilers' `compileCons` cases for `MAPC`/`MAPCAN`/`MAPLIST`/`MAPCON`/`MAPL`
   should reject an argument count they do not implement instead of compiling the
   first two arguments and dropping the rest. The interpreter's message
   (`MAPCAN expects 2 arguments, got 3`) is the model; a compile error naming the
   operator is better still, since the count is static in call position.
2. **Then implement N lists** where CL specifies it (all of them). `mapcar`'s
   call-position lowering already walks a fixed list of sequences, so the shape
   is known; `mapcan`/`mapcon` additionally `nconc` the results and
   `maplist`/`mapl`/`mapc` pass the successive CDRs rather than the elements.
   The interpreter's built-ins (`LispEvaluator`/`Environment`) need the same
   widening -- they are the reference the compile backends are diffed against.

Do the interpreter first (workflow order), then JVM, then WASM, then a ci-spec
case per operator. `#'mapcar`'s wrapper
(`BuiltinFunctionWrappers.mapcarWrapper`, a `do` walk whose exit test is
`(member nil ls)`) is the template for the first-class values of the widened
operators.

## Non-goals

- `map`/`map-into` (`map-into` already takes N sequences).
- The `#'mapcar` value path: fixed in the alexandria pass, pinned by the
  `mapcar-as-a-first-class-value-over-many-lists` ci-spec case.

## Verification

- One ci-spec case per widened operator (all four backends), plus the
  interpreter/JVM/WASM unit cases next to `mapcarAsValueOverMultipleLists` /
  `compileAndRunMapcarAsValueOverMultipleLists`.
- `.kb/asdf.md`'s alexandria entry names this item; update it when the gap
  closes.
