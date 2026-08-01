# substitute-if

`(substitute-if new predicate sequence &key key)`

`predicate` を満たすすべての要素を `new` に置き換えた新しいシーケンスを返します。その他の要素は変更されません。[`substitute`](substitute.md) の `eql` 比較を述語呼び出しに置き換えたものなので、`:test` は取りません (述語そのものが判定です)。省略可能な `:key` キーワードに渡したセレクタ関数は、述語が見る前に各要素へ適用されます (置き換える値は `new` そのものです)。シーケンスにはリスト・文字列・ベクタを渡せ、結果は同じ種類になります。元のシーケンスは変更されません。破壊的な操作にはリスト専用の [`nsubstitute-if`](nsubstitute-if.md) を使います。

```lisp
(substitute-if 0 #'oddp '(1 2 3 4 5)) ; => (0 2 0 4 0)
```

```lisp
(substitute-if #\- (lambda (c) (member c '(#\. #\/) :test 'char=)) "lack/mw.backtrace") ; => "lack-mw-backtrace"
```

```lisp
(substitute-if 0 #'oddp '((1) (2) (3)) :key #'car) ; => (0 (2) 0)
```
