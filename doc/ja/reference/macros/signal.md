# signal

`(signal datum args...)`

[`error`](error.md) と同じ designator サーフェスで**非致命的**なコンディションを通知します: リテラルの制御文字列(`simple-condition` を構築)、クォートされたコンディション型シンボル + initarg、またはコンディションオブジェクト。現在の制御スレッドに [`handler-case`](handler-case.md) ハンドラが確立されていればコンディションはそこへ送出され、なければ `signal` は nil を返して実行を継続します(Common Lisp のフォールスルー)。WASM バックエンドにはハンドラが存在しないため、`signal` は常に引数を評価して nil を返します。lite: 送出されたシグナルがどの確立済み `handler-case` の節にもマッチしない場合は、nil にフォールスルーせずエラーと同様に中断します。

```lisp
(signal "nothing is listening") ; => nil
```

```lisp
(handler-case (progn (signal "caught mid-flight") :not-raised)
  (condition (c) :raised)) ; => :raised
```
