# search

`(search sequence-1 sequence-2 &key start1 end1 start2 end2 test key from-end)`

Returns the position in `sequence-2` where `sequence-1` first occurs as a subsequence, or nil when it does not. With `:from-end` the LAST occurrence's start position is returned. `:start1`/`:end1` bound the pattern, `:start2`/`:end2` bound the searched sequence, elements compare with `:test` (default `eql`) after `:key`. Works on strings, lists and vectors, in any combination. A nil `:end1`/`:end2` means the length. A bound that is negative, not an integer (a nil `:start1`/`:start2` included) or past its sequence's length, or a start past its end, signals the `type-error` [`subseq`](subseq.md) signals for the same range -- over a list too. The scan is a simple O(n*m) one; the interpreter runs it natively when the comparison is the default `eql` (or `#'eql`, or `#'char=` between two strings) and no `:key` is given, and falls back to the same portable scan otherwise.

```lisp
(search "bc" "abcd") ; => 1
```

```lisp
(search "x" "abcd") ; => NIL
```

```lisp
(search "ab" "ab-ab" :from-end t) ; => 3
```
