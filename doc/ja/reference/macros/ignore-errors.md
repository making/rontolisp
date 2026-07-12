# ignore-errors

`(ignore-errors form...)`

フォームを評価して最後のフォームの値を返します。エラーが通知された場合は代わりに nil を返します(構文的多値ティアの第 2 値としてコンディションオブジェクトを伴います)。`(handler-case (progn form...) (error (c) (values nil c)))` の糖衣です — [`handler-case`](handler-case.md) を参照。

`handler-case` と同じく**インタプリタと JVM バックエンド**のみでサポートされ、WASM コンパイラは拒否します。

```lisp
(ignore-errors (error "boom")) ; => nil
```

```lisp
(ignore-errors (+ 1 2)) ; => 3
```
