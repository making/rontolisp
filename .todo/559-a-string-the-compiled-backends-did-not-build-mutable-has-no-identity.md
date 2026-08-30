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

## Measured 2026-08-28: the proposed lever costs 100x, and why

"Make `copy-seq` / `subseq` / ... answer a character vector and every later op
on the result re-renders it, O(n) per op" was written above as the obvious
approach with an unknown price. It was measured, and the price is not O(n) per
op -- it is **O(n^2) per scan**, because the ops a string scan is made of are
per-CHARACTER ops. Reading one character out of a character vector renders the
WHOLE vector into a fresh runtime string and then indexes it.

`(dotimes (j (length s)) (char-code (char s j)))`, 200 repetitions, ms:

| n | JVM immutable | JVM char vector | WASM immutable | WASM char vector |
| ---: | ---: | ---: | ---: | ---: |
| 256 | 36 | 166 | 3 | 134 |
| 512 | 18 | 436 | 3 | 497 |
| 1024 | 1 | 1663 | 7 | 2417 |
| 2048 | 1 | 5289 | 29 | 11293 |

The character-vector column quadruples per doubling on both backends -- exactly
quadratic -- while the immutable column is linear and, once JIT-warm on the JVM,
below the clock's resolution. At n=2048 the same scan is **5289 ms vs 1 ms** on
the JVM and **11.3 s vs 29 ms** on wasmtime. Flipping the producers today would
put that multiplier on every string-heavy program in the corpus (jzon, ironclad,
trivial-utf-8, the JSON parse path), so the answer is not "measure and choose"
any more: **559 cannot land on the current character-vector read path**, whatever
the producers do.

## The prerequisite is NOT `.todo/343`, and 343's framing needs correcting

`.todo/343` (memoize the rendered form) reads like the fix for the table above.
It is not the right one, and the measurement says so directly. Compare the three
index spellings against the SAME character vector on the JVM (200 reps, ms):

| n | `(char s j)` | `(aref s j)` | `(elt s j)` |
| ---: | ---: | ---: | ---: |
| 512 | 387 | **30** | 408 |
| 2048 | 4330 | **92** | 3770 |

`aref` is already O(1) on a character vector -- it reads the element out of the
general array and never builds a string -- and is **47x faster than `char` on the
identical object**. So the rendered string is not something to cache: on this
path it is something never to build. `char` / `schar` / `elt` normalize to a
string first only because that is how the immutable arm works, and the mutable
arm inherited it.

That also means `.kb/string-index-cost.md`'s stated invariant ("`(char s i)` /
`(elt s i)` ... are O(1) ... on ALL FOUR backends. A left-to-right scan of one
string is LINEAR in its length, never quadratic") is **false today for a mutable
character vector** -- i.e. for every `make-string` buffer. The file's whole
argument is about translating a character index into a UTF-16 / UTF-8 offset,
which is the immutable representation's problem; the character vector, whose
elements ARE code points, has no such problem and pays a rendering instead. The
invariant is right; a representation escaped it.

## Revised plan

1. **Make an index into a character vector read the element** (this is the
   prerequisite, and it stands on its own as a live O(n^2) bug against
   `.kb/string-index-cost.md`; it is not `.todo/343`, which should be re-scoped
   to the callers that genuinely want the whole string -- `string=`, `intern`,
   `write-string`, `_equal`/`_hash`/`_print_val`).
   - JVM: `JvmCharCompiler.compileChar` (`JvmCharCompiler.java:38`) opens with an
     unconditional `JvmArrayCompiler.emitStrvNormalize` at line 41, then
     `CHECKCAST String` + `_cpoff` + `codePointAt`. Replace the site with ONE
     call to a new `_charRef(Object, int)` in `JvmStringIndexRuntimeBuilder`
     that tests the character-vector shape (the length-4 slot-0 header,
     `JvmArrayRuntimeBuilder.java:38-45`) and does the `_rmGet` element read,
     falling back to today's `_strv` path otherwise. One call per site is FEWER
     bytes than the current two-call sequence, so `.kb/string-write-runtime.md`'s
     size argument points the same way.
   - WASM: the twin already has its shape test -- `_charvec_p`
     (`FUNC_CHARVEC_P`, 213 bytes, O(1), built for `.todo/342`). A
     `_str_char_ref(str, i)` calling `_charvec_p` -> `_rmGet` or
     `_str_char_at` replaces the `emitCharvecToStrCall` + `FUNC_STR_CHAR_AT`
     pair at each `char`/`schar`/`aref`-on-string site.
   - `elt` on a string is the same site through a different head and must move
     with it (its 3770 ms above is the same rendering).
   - The interpreter is already correct (its character vector IS a mutable
     `LispString`) and must not change.
2. **Then** flip the producers, re-measuring the table above first: it should
   have collapsed to the immutable column before any producer changes.
   `copy-seq` needs no work of its own -- `LispMacroExpander.expandCopySeq`
   (`LispMacroExpander.java:2858`) is `(subseq seq 0)` on every backend, so
   `subseq`'s string lane is the single producer that decides both of the todo's
   first two programs: `JvmSubseqCompiler` (`substring` + re-quote) and
   `WasmSubseqCompiler.java:37-38` (which calls `_charvec_to_str` FIRST, so it
   currently launders a mutable vector into an immutable string).
3. A literal still must not become mutable. On WASM the discriminator is free
   and already exists: `_str_build` ids are interned offsets `< heapBase`,
   `_str_fresh` ids are the counter `>= heapBase` (`.kb/wasm-gc-strings.md`), so
   "was this string built at run time" is an `i32.lt_u`.

## The BULK writes belong here too (measured 2026-08-30, from `.todo/581`)

`.todo/581` was opened over `replace` / `fill` / `(setf (subseq ...))` diverging on a
string LITERAL. Measured on all four backends, that split cleanly in two and only one
half was 581's:

- The LITERAL half is closed. A bulk write into a source constant lands on a fresh copy
  and comes back as the return value, on all four (`.kb/string-write-runtime.md`, "The
  BULK writes, settled 2026-08-30").
- The remaining half is **this item's**, and it is the same sentence as the alias case
  at the top of this file with a different operator:

```lisp
(let ((s (copy-seq "abc"))) (replace s "Z") s)
;; SBCL / interpreter: "Zbc"
;; JVM / WASM P1 / WASM component: "abc"   ; the write is DISCARDED
```

The mechanism is the one already described here: `expandReplace`/`expandFill` see
`%arrayp` false for an immutable string, take their functional branch, build the right
string, and drop it -- `replace` is a FUNCTION call whose result the caller usually
ignores, so unlike `expandScharSetFunctional` there is no `setq` to catch it. Step 2 of
the revised plan above (flip the producers so `copy-seq`/`subseq` answer a character
vector) fixes it as a side effect, because the destructive branch then applies. **Do not
try to fix it separately by making the functional branch write further** -- that is the
same "the setq is a symptom" the section above already rejects, one operator over.

Add these two programs to the definition of done:

```lisp
(let ((s (copy-seq "abc"))) (replace s "Z") s)          ; want "Zbc" on all four
(let ((s (copy-seq "abc"))) (fill s #\Q) s)             ; want "QQQ" on all four
```

A literal target must keep answering what it answers today (the copy, not a write) --
the two rules do not collide, for exactly the reason the paragraph above about `subseq`
gives: a literal's sharing is made by the READER.

## Step 1 DONE (2026-08-31); step 2 not started

Step 1 of the revised plan landed: `char`/`schar`/`elt` read a character
vector's ELEMENT (`_charRef` on the JVM, `_str_char_ref` on WASM) and the
O(n^2) table collapsed onto the aref column -- JVM n=2048 scan 1153 ms -> 8 ms,
WASM 2224 ms -> 9 ms, controls unmoved (`.kb/string-index-cost.md`, "The
character vector escaped the invariant", pinned by the
`character-vector-index-reads-the-element` ci-spec case). The producer flip
(step 2) is now unblocked by cost.

The identity programs still answer as the table at the top of this file says,
and the two `compileDisplacedStringViewOverAnImmutableStringPromotesOnWrite`
tests still pin the promote-on-write behavior; they are still the tests to
rewrite when step 2 lands.

## A shipped-library casualty, and the working pattern (2026-08-31, from the PLY/glTF readers)

`geom::%utf8-string` (geom.lisp, the glTF reader's byte->string decode) hit BOTH
quadratic halves this file describes, live: built through `make-string` +
`(setf (char s k))` a 138 KB embedded-base64 glTF decoded in 1.1 s interpreted
and **30.5 s** compiled (the per-write rebuild); rebuilt over a fill-pointered
character vector alone it would have moved the same quadratic onto every
`(char s j)` of the JSON scanner that reads the result (the table above). The
shape that escapes both, now in geom.lisp and recorded in `.kb/geom.md`
("Reading a model file"): fill-pointered character array to BUILD, then ONE
`subseq` on the way out so every scan reads an ordinary immutable string --
82 ms compiled. When this item lands, that workaround becomes unnecessary (not
wrong); the readers' `geom-read-ply-gltf-cross-backend` ci-spec case is an
end-to-end canary for any producer flip, since it feeds json-parse a
runtime-built string on all four backends.

## The scan half, re-measured against a sequence operator (2026-08-31)

Working `.todo/595` (the `format` renderer and `map`'s `'string` accumulator) put
a number on what the `(char v i)` render costs a whole OPERATOR rather than a
microbenchmark, and it is the largest one in that round.
`(map 'string #'char-upcase <4000-character source>)`, ms per call, Apple M4 Max,
each row its own `defun`, before and after 595's accumulator fix in one locked
acquisition:

| source | interpreter | JVM | WASM p1 |
| --- | --- | --- | --- |
| an ORDINARY string, before / after | 9.80 / **6.01** | 0.85 / 0.21 | 11.95 / **0.475** |
| a `make-string` CHARACTER VECTOR, before / after | 9.75 / 6.44 | 22.15 / **20.63** | 55.1 / **45.9** |

The two sources differ only in representation and the operator is identical, so
the gap IS the render: at n = 4000 a character-vector source costs **97x** an
ordinary string's on the JVM and **97x** on wasm-GC, and 595's fix -- which
removed a genuine O(n^2) from the accumulator, 25x on wasm over an ordinary
string -- moves the character-vector row by only 1.2x because the READ is what
is left. The interpreter, whose character vector IS a `LispString`, shows no gap
at all, which is the control.

This is step 1's payoff measured on something a program would actually write:
`(map 'string ...)`, `(coerce v 'string)`, `remove`, `substitute`, `sort` and
every other generic sequence operator over a `make-string` buffer pays it, not
just a hand-written `(dotimes (i n) (char v i))`.
