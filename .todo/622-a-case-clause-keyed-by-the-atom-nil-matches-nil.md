# A `case` clause whose key designator is the atom `nil` matches NIL

Difficulty: Low

CLHS 5.3 (`case`): a normal-clause is `(keys form*)` where `keys` is a
designator for a LIST of keys. The atom `nil` designates the EMPTY list, so
`(nil ...)` is a clause that can never be selected -- to match the object NIL
you write `((nil) ...)`. We treat the atom as the one-element list.

```lisp
(defun g (x) (case x (nil :was-nil) (t :other)))
(g nil)   ; rontolisp: :WAS-NIL     SBCL: :OTHER
(g 5)     ; both: :OTHER

(defun f (x) (case x ((nil) :was-nil) (t :other)))
(f nil)   ; both: :WAS-NIL   -- the list spelling is already right
```

`ecase` has the same hole (`(ecase nil (nil :matched))` answers `:MATCHED`
instead of signalling). Check `ccase`/`typecase`/`etypecase` in the same pass:
`typecase`'s clause head is a TYPE specifier, where `nil` is the empty type and
must likewise match nothing.

The distinction matters because the two spellings are not stylistic -- a
`(nil ...)` clause in real code is usually a deliberate dead branch left by a
macro that computed its key list, and silently selecting it changes the answer
rather than erroring.

Found by `.todo/620` while reading cl-ppcre 1.2.3's parser, whose `reg-expr`
dispatches on `(case (next-char lexer) ((nil) ...) ((#\|) ...) (otherwise ...))`
-- correct there, which is what made the wrong half visible.

One shared expansion in `LispMacroExpander` covers all four backends. Pin in
`LispEvaluatorTest` + both compiler tests + a ci-spec row.
