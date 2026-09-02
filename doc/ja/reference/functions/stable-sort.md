# stable-sort

`(stable-sort sequence predicate &key key)`

[`sort`](sort.md) と同様に `sequence` をソートしますが、`predicate` が等しいとみなす要素 (`(predicate a b)` も `(predicate b a)` も真でない要素) の相対順序を保持します。省略可能な `:key` 関数は比較前に各要素へ適用されます。リスト・ベクタ・文字列は `sort` と同じ方法でソートされます -- リストのコンスセルはその場で並べ替えられ、ベクタやリテラルでない文字列はその場でソートされて同じオブジェクトとして返ります (フィルポインタやアジャスタブルフラグがあれば保持されます) -- プログラムテキストのリテラル文字列は代わりに新しい文字列として返ります。元の変数ではなく戻り値を使用してください。

```lisp
(stable-sort '((1 . b) (0 . a) (1 . a)) #'< :key #'car) ; => ((0 . A) (1 . B) (1 . A))
```

```lisp
(stable-sort '(3 1 2) #'<) ; => (1 2 3)
```

```lisp
(let ((v (vector 3 1 2))) (let ((s (stable-sort v #'<))) (list s (eq v s)))) ; => (#(1 2 3) T)
```
