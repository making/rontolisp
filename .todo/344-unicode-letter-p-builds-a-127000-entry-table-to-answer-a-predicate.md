# `unicode-letter-p` builds a ~127,000-entry hash table to answer a predicate

Difficulty: Medium

Split out of `.todo/185`, which recorded it as a second, independent idea from the
same measurement and closed without it. 185 was about the COST OF AN INDEX, this is
about the COST OF A TABLE; nothing in the index fix decides this one.

`uax-15::*unicode-letters*` is ~127,000 hash entries (21,765 data-derived plus nine
hardcoded CJK/Hangul/Tangut range loops covering ~105,000 codepoints) built for ONE
consumer, `unicode-letter-p`, which only ever looks at truthiness. It is lazy since
`.todo/184`, so a program that never calls it pays nothing -- but `postmodern`'s
`util.lisp` calls it twice, and for that program the table was 132 ms (interpreter) /
148 ms (component) and ~127k live entries, which is exactly the wasm-GC live-set
penalty measured as Finding 2 of the item that preceded 184.

`Uax15Tables.ranges()` already produces sorted inclusive range pairs. Replacing the
table with a binary search over them inside `unicode-letter-p` -- the same
`replaceForm` shape `get-illegal-char-list` already uses, docstring kept verbatim --
would make the cost ~0 for callers AND non-callers.

## Re-measure before designing

The 132/148 ms baseline was taken when `(char s i)` still cost O(i) on every compile
path, and the table build reads its data through exactly that kind of scan. `.todo/185`
made a character index amortized O(1) everywhere (`.kb/string-index-cost.md`), so the
build is cheaper now by an unknown factor and the ranked reason to do this may have
moved from BUILD TIME to LIVE SET. Take the numbers again -- postmodern's load on the
interpreter and on a component -- before choosing between the binary search and leaving
it alone.

## Why it is its own item

The rewrite widens the behavior surface of a replaced form: today's table answers a
category symbol per character (`(gethash ch *unicode-letters*)` yields `Ll`/`Lu`/...),
and a range search answers only membership unless the ranges carry their category. Check
what upstream reads besides `unicode-letter-p` before narrowing it.
