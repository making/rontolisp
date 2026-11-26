# map-into

`(map-into result-sequence function &rest sequences)`

与えられた `sequences` の要素に順に `function` を適用し、その結果を `result-sequence`(リストまたはベクタ)に破壊的に格納して、`result-sequence` を返します。関数は各シーケンスから 1 つずつ要素を受け取り、`result-sequence` を含めて最も短いシーケンスの末尾で反復を終了します。`result-sequence` の残りの要素は変更されません。ソースシーケンスがない場合、関数は引数なしで呼び出されて各要素を埋めます。フィルポインタを持つ結果は、そのフィルポインタまで埋められます。

```lisp
(map-into (list 0 0 0 0) #'+ '(1 2 3) '(10 20 30 40)) ; => (11 22 33 0)
```

```lisp
(map-into (make-array 3) #'* #(2 3 4) #(5 6 7)) ; => #(10 18 28)
```
