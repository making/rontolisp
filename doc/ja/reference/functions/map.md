# map

`(map result-type function &rest sequences)`

与えられた `sequences`(それぞれリストまたは文字列)の要素に順に `function` を適用し、要求された型の結果を構築します。複数のシーケンスが与えられた場合、関数は各シーケンスから 1 つずつ要素を受け取り、最も短いシーケンスの末尾で反復を終了します。`result-type` は(`concatenate` と同様に静的に解決される)リテラルの指定子として記述する必要があります。`'list` は結果をリストに集め、`'string` は文字の結果から文字列を構築し、`nil` は副作用のためだけに関数を呼び出して nil を返します。

```lisp
(map 'list #'+ '(1 2 3) '(10 20 30)) ; => (11 22 33)
```

```lisp
(map 'string #'char-upcase "abc") ; => "ABC"
```
