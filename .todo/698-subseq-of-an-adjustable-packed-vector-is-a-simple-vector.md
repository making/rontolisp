# 698. `subseq` of an adjustable `(unsigned-byte 8)` vector is a `simple-vector`, and only the interpreter notices

Difficulty: Low

Found 2026-09-05 running `examples/llm` on the interpreter with a Hugging Face
checkpoint (`.todo/489`): `java --add-modules jdk.incubator.vector -jar ... llm.lisp
--simd -- Qwen3-0.6B -m chat ...` dies in the file's byte-level JSON reader with

```
%OCTETS-TO-STRING expects an (unsigned-byte 8) vector, got: #(40 63 105 ...)
```

on the first `tokenizer.json` string that contains an escape (the pre-tokenizer regex).
The JVM class output of the same program runs it fine, which is why the Qwen3.5 rows in
the README are JVM-only and nobody saw it. The minimal program:

```lisp
(let ((v (make-array 4 :element-type '(unsigned-byte 8) :fill-pointer 0 :adjustable t)))
  (vector-push-extend 65 v)
  (vector-push-extend 66 v)
  (print (type-of v))                       ; (VECTOR (UNSIGNED-BYTE 8) 4)   everywhere
  (print (type-of (subseq v 0 2)))          ; (SIMPLE-VECTOR 2)              EVERYWHERE
  (print (rontolisp::%octets-to-string (subseq v 0 (fill-pointer v)))))
;; interpreter: the error above.  JVM class, wasm: "AB"
(let ((w (make-array 2 :element-type '(unsigned-byte 8) :initial-element 67)))
  (print (type-of (subseq w 0 2))))         ; (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (2)) everywhere
```

Two defects, and the second hides the first on three backends:

1. **`subseq` of an ADJUSTABLE packed vector drops the element type on every backend.**
   CLHS `subseq` returns a sequence of the same kind; the simple case keeps
   `(unsigned-byte 8)` and the adjustable case does not -- the copy goes through the
   generic element path (`.kb/adjustable-arrays.md` says how an adjustable packed vector is
   represented; whichever `subseq` arm handles it builds a `simple-vector`).
2. **`%octets-to-string` is strict on the interpreter and lenient on the compiled
   backends.** Cross-backend identity is the rule (`.kb/emitted-output-determinism.md`);
   here a program's fate depends on the backend. Once 1 is fixed the leniency is
   unreachable from this program, but the asymmetry itself should be pinned one way.

## Narrowed the same day

`.todo/691`'s three files do NOT carry the defect: the other orchestrator checked the
interpreter leg directly -- `tokenizer:decode` round-trips through a real
fill-pointer / adjustable buffer, and every `subseq`-into-`octets-to-string` site there
either takes a plain `make-array` buffer or goes through `coerce` / `%packed`. So **a
packed-vector copy is a known working fix**, and the question reduces from "is the
builtin wrong" (it is, item 1 above, on every backend) to **which callers outside those
three files still hand a bare `subseq` of an adjustable buffer to `octets-to-string`**
-- `examples/llm/llm.lisp`'s `json-string` WAS one, until `.todo/690` deleted that
byte-level reader the same day (its work is `rontolisp:json-parse` now), which put the
interpreter leg of `examples/llm` back without touching the builtin. Start from the
grep, not from this list.

## Do

1. A `ci-spec.yaml` case with the program above (it is exactly the cross-backend shape
   `CiSpecE2eTest` exists for), red on the interpreter today.
2. Fix `subseq` to keep the element type for adjustable packed vectors -- every packed
   width, not only `(unsigned-byte 8)`: `.kb/packed-integer-vectors.md` lists them, and the
   float widths are `.kb/vec.md`'s.
3. Decide `%octets-to-string`'s strictness once and make the four backends agree.
4. Re-run `examples/llm` on the interpreter with a `tokenizer.json` checkpoint
   (SmolLM2-135M-Instruct is the small one, 270 MB) and put the interpreter leg back into
   `.todo/489`'s table.
