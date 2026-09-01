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

## A caller, found 2026-09-01 (`.todo/620`)

This is not only a conformance gap -- it is what stops the cl-ppcre bundled with
_Practical Common Lisp_ (`libraries/cl-ppcre-1.2.3/`, which SBCL loads
unchanged). `parser.lisp` accumulates a run of literal characters into an
adjustable string as it parses, and `parse-string` finishes with
`(reverse-strings (reg-expr lexer))`, whose string arm is `(nreverse parse-tree)`.
So every multi-character literal in a regex becomes `NIL`:

```lisp
(cl-ppcre::parse-string "aQ")   ; rontolisp: NIL     SBCL: "aQ"
(cl-ppcre::parse-string "\\\\Q"); rontolisp: NIL     SBCL: "\\Q"
```

and the first `create-scanner` at the top of `api.lisp` dies with
`Unknown token NIL in parse-tree` while the file is still loading -- an error
seven files away from the operator that caused it. The quicklisp cl-ppcre does
not use this idiom and works today, which is why it took a 2005 library to find.
Worth a regression row of its own shape: `nreverse` of a fill-pointered,
adjustable string.

`.todo/623` is the same dispatch being present but lossy for `sort` and absent
for `delete`/`nsubstitute`; fix them together.
