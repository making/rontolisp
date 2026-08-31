# The last string producers still answer immutable values

Difficulty: High

Split out of `.todo/596` (2026-08-31). Round 3 landed the cheap half -- the
`string-trim` family, a program-written `(map 'string ...)` / `(coerce seq
'string)`, `uiop:getenv`, the first-class `#'concatenate` wrapper body and
`read-line`'s eof-value identity -- with its numbers, its boundary sweep and the
`%seq-string-result` internal designator that keeps the sequence operators out of
the flip (`.kb/string-write-runtime.md`, "The third round"). What is LEFT is
below, each with the measurement that says why it did not go with them.

## 1. `princ-to-string` / `prin1-to-string` / `write-to-string`

Wrapping the shared compiler case is measured, and it is not only format's tax:
the expander builds string pieces with `princ-to-string` at ~25 sites, including
`map 'string`'s per-ELEMENT accumulator, so the naive wrap converts one character
at a time. WASM, min of two passes, controls unmoved: `string-trim` +80%,
`coerce 'string` +54%, `map 'string` +35%, concatenate-of-a-list +47%, `reverse`
of a string +38%, `string-upcase` +50%, `format nil` +40%, `%fmt-render` +17%,
json-stringify +6%. JVM: `format nil` +17%, `%fmt-render` +12%, `string-upcase`
+24%. Sizes barely move (zlib +60 bytes of wasm); the cost is time.

**The shape that works**: one internal, print-object-DISPATCHING alias (the
existing `%princ-to-string-raw` / `%prin1-to-string-raw` are print-object-FREE
and cannot serve), used by every expander-generated piece; the public name keeps
the wrap. Sites to move: `opsToPieces` and the `~a`/`~s`/`~d`/`~r`/`~x`/`~f`
fallbacks, `printPiece`'s two-element shape test, `expandMap`'s STRING
accumulator, the condition-message and gensym-suffix sites.
`StringValuedForms.ALWAYS_STRING` moves its two public entries to the alias at
the same time, or the wrap is silently skipped in front of `princ`/`write-string`.

## 2. `reverse` / `remove` / `remove-if` / `remove-if-not` / `remove-duplicates` / `substitute` / `substitute-if` / `sort` over a string

They share `seqResultDispatchForm`'s string arm, which now carries
`%seq-string-result` so they stay immutable and CONSISTENT (not
program-content-dependent). Flipping them means their names in the producer gate,
which cannot tell a string sequence from a list one, so a list-only program pays
the JVM array runtime: `(print (reverse (list 1 2 3)))` 14,674 -> 21,664 bytes of
class (+47.6%), `examples/console/nqueens` 17,927 -> 24,662 (+37.6%); wasm +164
bytes (+0.7%) in both. **Re-evaluation trigger**: a JVM shape where a character
vector can exist without the general-array runtime, or a gate that can see the
sequence's type.

## 3. A COMPUTED `format` destination, and a COMPUTED `coerce` result type

Only the literal-`nil` `format` destination and the literal `'string` coerce/map
designator gate. The wrap itself would be CORRECT for both (a non-string, `nil`
included, passes `_toMutStr` through); it is the gate that costs -- every
`(format <expr> ...)` / `(coerce x <expr>)` program would join the array gate for
a rare run-time case, +2.4 to +3.3 KB of JVM class each (hello_world +2,357,
pi_approx +3,173, hanoi +3,011, contact-book +3,297; wasm +0).
`expandComputedCoerce` routes to the unwrapped shared `%seq-to-string`, so that
is the one line to change if the gate question is ever answered.

## 4. Recorded, not to be flipped

- `symbol-name` / `(string 'sym)` / `gensym` / `make-symbol` names: CLHS leaves
  symbol-name mutation undefined and SBCL shares the name object. Note the
  INTERPRETER does mutate the symbol's own name through such a write, which is a
  separate (undefined-behavior) wart.
- fetch / socket / gray-stream read results, including `%io-read-line`'s socket
  arm -- only the `%read-line-raw` fallback wraps.
- json-parse's multi-fragment string values (`%json-concat` merges through the
  unwrapped `%string-concat`); single-fragment values are subseq slices and
  already mutable. If value identity ever matters, wrap in `%json-string`'s
  return, not in the merge -- the merge re-conversion was a json-stringify
  112 -> 245 ms regression before 596 moved it.

## Definition of done

Per producer flipped: the alias/callee/replace/fill shapes match SBCL on all four
backends, `string-identity-cross-backend` extended, and the corpus rows
re-measured (each row its own defun, untouched control,
`.kb/string-write-runtime.md`'s table format). Landing a subset with numbers is
fine -- 559, 596 and 600's third round all did. The performance residue of the
ALREADY flipped producers belongs to `.todo/343`, not here.
