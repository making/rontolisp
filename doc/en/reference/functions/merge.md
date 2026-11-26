# merge

`(merge result-type sequence-1 sequence-2 predicate &key key)`

Merges two already-sorted sequences into one sorted sequence of `result-type`. `predicate` is the same ordering predicate `sort` takes, and `:key` selects what it sees. The merge is stable: when neither element precedes the other, the one from `sequence-1` comes first.

`result-type` may be a run-time value, but it is limited to the sequence families `coerce` builds -- `list`, `vector` and `string`. Unlike Common Lisp's, this `merge` does not destroy its arguments.

```lisp
(merge 'list (list 1 3 5) (list 2 4 6) #'<) ; => (1 2 3 4 5 6)
```

```lisp
(merge 'string "ac" "bd" #'char<) ; => "abcd"
```

```lisp
(merge 'list (list '(1 a)) (list '(1 b) '(2 c)) #'< :key #'car) ; => ((1 A) (1 B) (2 C))
```
