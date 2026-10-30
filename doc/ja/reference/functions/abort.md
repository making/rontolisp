# abort

`(abort [condition])`

最内のアクティブな `abort` リスタートを起動します。rontolisp が実行するプログラムには独自の `abort` リスタートがないため、これが届くのはプログラム側がその名前で確立したリスタートだけです。アクティブなものがなければ CL の契約どおりエラーを通知します。[`rontolisp --swank`](../../getting-started/swank.md) の下では、SWANK が確立するリスタートにも届きます。要求ごとの SLIME のトップレベルへ戻るリスタートと、デバッガの各レベルの一つ下のレベルへ戻るリスタートです。

```lisp
(restart-case (progn (abort) :not-reached)
  (abort () :aborted)) ; => :ABORTED
```
