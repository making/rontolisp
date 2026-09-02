# `make-array :displaced-to` refuses `:fill-pointer` / `:adjustable`

Difficulty: Medium

Found 2026-09-02 while closing `.todo/464` (the array-info readers over a string).

```lisp
(make-array 4 :element-type 'character :displaced-to "abcdef" :fill-pointer 2)
;; rontolisp, all four backends:
;;   MAKE-ARRAY: :displaced-to cannot be combined with
;;   :fill-pointer/:adjustable/:initial-element
;; SBCL 2.2.9: #<(AND (VECTOR CHARACTER 4) (NOT SIMPLE-ARRAY)) "ab">
```

CLHS forbids only `:initial-element` / `:initial-contents` with `:displaced-to`;
`:fill-pointer` and `:adjustable` are explicitly allowed, and both are ordinary
CL idioms (an output buffer that is a growing view over a bigger backing store).
The message lumps all three together, so two thirds of it is not a CL rule.

Where the refusal lives: grep the message text -- it is one check per backend
(`Environment` `make-array`, `LispMacroExpander`'s make-array lowering / the
`Jvm`/`Wasm` make-array compilers), all reached before the shape is chosen.

The work is representation, not validation: today the DISPLACED shape and the
HEADER shape are alternatives per backend (`.kb/adjustable-arrays.md` -- JVM: a
length-5 (general view) / length-7 (string view) slot-0 header vs. the length-4
character-vector header; WASM: the data slot holding a target CELL vs. the
buckets array, with the meta offset i31 as the character-vector marker). A
displaced array WITH a fill pointer needs both at once, so the header layout has
to carry the fill-pointer and `:adjustable` slots alongside the displacement
target on every backend, and every reader that decides "displaced?" by header
LENGTH (`emitResolveDisplacement` / `_arrayDispTarget` / `_arrayDispOffset`, and
`emitDataSlotIsTarget` on wasm) has to keep agreeing. `%simple-array-p` reads all
three conditions and must stay total.

Check `adjust-array` on the result and `vector-push-extend` over it while there:
CL says pushing past the displaced span is an error unless the array is
adjustable, and `adjust-array` on a displaced array un-displaces it.

Pin one case per backend plus a `ci-spec.yaml` line, and diff the answers against
SBCL 2.2.9 (`/usr/bin/sbcl`) rather than inventing them.

Adjacent, deliberately NOT part of this item: `adjustable-array-p` answers `T` in
SBCL for ANY non-simple array (a displaced view, a plain fill-pointered vector)
while rontolisp reports the `:adjustable` argument verbatim and answers `NIL`.
CLHS leaves "actually adjustable" implementation-defined, so both conform; the
divergence is recorded in `.kb/adjustable-arrays.md` and pinned by the
`string-array-shape-readers-cross-backend` ci-spec case.
