# nreverse answers nil and stable-sort answers a list when the sequence is a vector or a string

Difficulty: Low

Found while probing the string producers for `.todo/600`. Two sequence operators
drop their result's TYPE, identically on all four backends (so this is a
conformance gap, not a divergence):

```lisp
(nreverse (copy-seq "abcd"))        ; => NIL      (SBCL: "dcba")
(nreverse (vector 1 2 3))           ; => NIL      (SBCL: #(3 2 1))
(nreverse (list 1 2 3))             ; => (3 2 1)  correct
(stable-sort (copy-seq "dcba") #'char<) ; => (#\a #\b #\c #\d)   (SBCL: "abcd")
(stable-sort (vector 3 1 2) #'<)        ; => (1 2 3)             (SBCL: #(1 2 3))
(sort (copy-seq "dcba") #'char<)        ; => "abcd"              correct
```

`sort` is correct because `LispMacroExpander.wrapSortForStringSeq` wraps it in
`seqResultDispatchForm` (the string/vector/list dispatch whose arms convert the
result back); `stable-sort` never got the same wrap. `nreverse`'s vector/string
arm is the same shape of miss -- `reverse` goes through
`seqResultDispatchForm` and answers the right type.

The fix is the wrap both already have a place for; check whether the
destructive contract wants the IN-PLACE reversal for a vector (SBCL reverses a
vector in place and answers the same object) rather than the
coerce-out-and-back the dispatch form performs -- `.kb/string-write-runtime.md`
now says a character vector is mutable on every backend, so the in-place answer
is available. Cross-backend behavior is pinned by ci-spec; add rows there and to
`LispEvaluatorTest` / both compiler tests.
