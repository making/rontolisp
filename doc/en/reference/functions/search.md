# search

`(search sequence-1 sequence-2 &key start1 end1 start2 end2 test key from-end)`

Returns the position in `sequence-2` where `sequence-1` first occurs as a subsequence, or nil when it does not. With `:from-end` the LAST occurrence's start position is returned. `:start1`/`:end1` bound the pattern, `:start2`/`:end2` bound the searched sequence, elements compare with `:test` (default `eql`) after `:key`. Works on strings and lists (a simple O(n*m) scan over `elt`).

```lisp
(search "bc" "abcd") ; => 1
```

```lisp
(search "x" "abcd") ; => nil
```

```lisp
(search "ab" "ab-ab" :from-end t) ; => 3
```
