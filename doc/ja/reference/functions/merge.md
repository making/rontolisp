# merge

`(merge result-type sequence-1 sequence-2 predicate &key key)`

ソート済みの 2 つのシーケンスをマージし、`result-type` のソート済みシーケンスを 1 つ返します。`predicate` は `sort` と同じ順序比較の述語で、`:key` は述語に渡す値を選びます。マージは安定です。どちらの要素も他方に先行しないときは `sequence-1` 側が先になります。

`result-type` は実行時の値でも構いませんが、`coerce` が構築できるシーケンスの族 -- `list`・`vector`・`string` -- に限られます。Common Lisp の `merge` と違い、この `merge` は引数を破壊しません。

```lisp
(merge 'list (list 1 3 5) (list 2 4 6) #'<) ; => (1 2 3 4 5 6)
```

```lisp
(merge 'string "ac" "bd" #'char<) ; => "abcd"
```

```lisp
(merge 'list (list '(1 a)) (list '(1 b) '(2 c)) #'< :key #'car) ; => ((1 A) (1 B) (2 C))
```
