# make-list

`(make-list size &key initial-element)`

すべてが `initial-element`(既定は `nil`)である `size` 個の要素からなる、新たに割り当てられた真正リストを返します。`size` が `0` の場合は空リストになります。要素のフォームは **1 回だけ**評価され、すべてのセルがその 1 つの値を共有します(Common Lisp の規定どおり)。したがって可変な要素を指定した場合、全セルが同一のオブジェクトになります。それ以外のキーワードはエラーです。

`size` は `array-dimension-limit` 未満の整数でなければなりません (`make-array` の次元と同じ上限)。そうでない `size` (bignum、負の数、整数以外) は、`size` を datum、`(INTEGER 0 (limit))` を期待型とする `type-error` を通知します。通知はセルを割り当てる前、かつ `size` と `initial-element` のフォームをこの順に評価した後に行われます。上限はバックエンドごとの値で、インタプリタと JVM では 2147483639、WASM では 1073741823 です。

```lisp
(make-list 3) ; => (NIL NIL NIL)
```

```lisp
(make-list 3 :initial-element 0) ; => (0 0 0)
```

```lisp
(handler-case (make-list -1)
  (type-error (e) (type-error-datum e))) ; => -1
```
