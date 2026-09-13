# `--no-gc` indexes and measures strings in BYTES, so `length`/`char`/`subseq` diverge on non-ASCII

Difficulty: High

**Status:** open, found and measured 2026-09-14 against `c972efa5d`.

## The divergence

A `--no-gc` string is `[len:i32 LE][UTF-8 bytes]` and every string primitive
works on that byte array directly. The interpreter, the JVM backend and the
wasm-GC backend all work in CHARACTERS. For ASCII text the two agree, which is
why this has gone unnoticed.

`s = "日本語"` (3 characters, 9 UTF-8 bytes):

| | interpreter | JVM | wasm GC | `--no-gc` |
| --- | ---: | ---: | ---: | ---: |
| `(length s)` | 3 | 3 | 3 | **9** |
| `(char-code (char s 0))` | 26085 | 26085 | 26085 | **230** |
| `(char-code (char s 1))` | 26412 | 26412 | 26412 | **151** |
| `(length (subseq s 1))` | 2 | 2 | 2 | **8** |

230 and 151 are the first two bytes of 日's UTF-8 encoding. `(char s 1)` does
not answer a character at all; it answers a continuation byte.

Reproduce with a `:string` export parameter (a literal behaves the same, and
`--no-gc` has no `format`):

```lisp
(rontolisp:wasm-export 'c0 :as "C0" :params '(:string) :returns :s32)
(defun c0 (s) (char-code (char s 0)))
```

drive it from a Node host through `__ronto_alloc`, and compare against
`java -jar ... prog.lisp`.

## The `.kb` file currently says the opposite

`.kb/no-gc-scalar-wasm.md`, "Strings":

> **A character IS its i64 code point**: `char-code`/`code-char` are identities,
> `char=` is numeric `=`, so `(char= (char s i) #\x)` matches the other backends.

The first two clauses are true. The conclusion is true only for ASCII `s`: for
anything else `(char s i)` is not a character. **Correct that line as part of
this item** whether or not the behaviour is changed -- a premise recorded in
`.kb/` is a measurement, and this one is now known to be wrong.

The same section lists `length`, `subseq`, `char` among the primitives with no
caveat, and `.todo/023` records the representation without mentioning the
consequence.

## What a fix costs, and why this is not obviously worth doing

Making `--no-gc` agree means code-point indexing over UTF-8: `length` counts
code points (a decode loop instead of an `i32.load`), `char` decodes forward to
the i-th code point (O(n) instead of O(1)), `subseq` converts code-point indices
to byte offsets. Every one of those is a size AND speed regression in the
backend whose entire purpose is to be small, and this is the backend where a
`--no-gc` reactor's whole job is usually to move ASCII text across the boundary.

The alternatives, in rough order of cost:

1. **Document it and stop.** Add the divergence to the `.kb` "Strings" section
   and to the README's "Non-GC Output" list of documented divergences (which
   already names two: no rational type, and `0` is false). Cheapest, honest, and
   leaves a trap for anyone who compiles a program with non-ASCII literals.
2. **Refuse what cannot be answered.** Keep byte semantics, but make the
   compiler reject a program that combines a non-ASCII string with `char`,
   `subseq` or `length`. Catches the literal case at compile time and does
   nothing for a runtime `:string` parameter, so it is only half a fence.
3. **Pay for code points.** Correct everywhere, and the size cost has to be
   measured before it is chosen -- `.kb/no-gc-scalar-wasm.md` exists because this
   backend's numbers are its reason to exist.
4. **A tier.** ASCII-only fast path with a decode path behind a flag. The worst
   of both unless the measurement in 3 comes out badly.

**Measure 3 before choosing.** If code-point `length`/`char`/`subseq` cost
single-digit bytes on an ASCII-only module (the decode loop is only emitted when
those primitives are used, the way the other helpers are gated by `Mem.used`),
then correctness is cheap and the choice is easy. If it is tens of bytes on
every string-using module, 1 is the answer and the finding is the deliverable.

## Related

- `.todo/269` is the other character-semantics divergence and is explicitly NOT
  this one: it is the wasm-GC backend's ASCII-only `alpha-char-p` /
  `char-equal` family. `--no-gc` rejects those operations at `collectCalls`
  rather than answering them, so the two items do not overlap.
- `.kb/characters-code-points.md` is where the cross-backend character contract
  lives and should name whichever way this is resolved.
