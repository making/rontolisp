# d34. Sequence operators validate no bound but a nil `:start`

Difficulty: High

SBCL signals a `type-error` for a negative, non-integer or out-of-range bound and for
`:start` > `:end`; the count / remove / substitute family, `remove-duplicates`, `fill` and
`replace` answer instead, on all four backends alike (measured with the bound read at run time,
list `(1 2 3 2 1)`):

| call | SBCL | rontolisp |
|---|---|---|
| `(count 2 l :start -1)`, `(remove 2 l :start -1)`, `(fill l 0 :start -1)` | `type-error` -1 | answers as `:start 0` |
| `(count 2 l :end -1)` | `type-error` -1 | `0` |
| `(count 2 l :start 1.5)`, `(remove-duplicates l :start 1.5)` | `type-error` 1.5 | answers |
| `(count 2 l :start 9)`, `(fill (list 1 2 3) 0 :start 9)`, `(replace ... :start1 9)` | `type-error` | answers unchanged / 0 |
| `(count 2 l :start 3 :end 1)` | `type-error` | `0` |

`position` / `find` / `reduce` / `read-sequence` / `write-sequence` already refuse most of these
(`%check-sequence-bounds` for the last two). A nil `:start` is refused everywhere
(`.kb/sequence-bounding-keywords.md`): the scaffold's `(max start 0)` is where a real check would
replace the clamp. Measure first: `read-sequence`'s check cost a fixed +19-28 KB wherever no
other `type-error` signal existed (`.kb/read-load-streams.md`), and the scans are hot, so a
per-call check must stay out of the element loop and a call with no bound must stay
byte-identical.
