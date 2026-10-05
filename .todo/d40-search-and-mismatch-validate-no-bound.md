# d40. `search` and `mismatch` validate no bound

Difficulty: Medium

SBCL signals a `type-error` for a negative, non-integer or out-of-range `:start1`/`:end1`/
`:start2`/`:end2` and for a start past its end; `search` and `mismatch` answer instead, on all four
backends alike (bounds read at run time):

| call | SBCL | rontolisp |
|---|---|---|
| `(search '(2) (list 1 2 3) :start1 9)` | `type-error` | `0` |
| `(search '(2) (list 1 2 3) :start2 3 :end2 1)` | `type-error` | `nil` |
| `(search #(2) (vector 1 2 3) :end2 9)` | `type-error` | `1` |
| `(mismatch (list 1 2 3) (list 1 2 3) :start1 9)` | `type-error` | `9` |
| `(mismatch (list 1 2 3) (list 1 2 3) :end1 9)` | `type-error` | `3` |
| `(mismatch (vector 1 2 3) (vector 1 2 3) :end2 9)` | `type-error` | `3` |

A negative bound is already a `type-error`. SBCL's `search` is lazy over a LIST `sequence-2`
(`:start2 9` answers nil); the other sequence operators refuse a list too short for its bound
(`.kb/sequence-bounding-keywords.md`, "Every bound is checked once").

Both are `LispPreludeLibrary` defuns, so `(%check-bounds seq start end)` after their
`%check-sequence` lines is the whole check -- but the defun is shared by every call, so every
program using `search` or `mismatch` pays it, bounded or not. Measure the corpus bytes and a hot
string `search` loop before choosing between that and a check only when a bound was supplied.
