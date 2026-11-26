# invoke-restart

`(invoke-restart restart arg...)`

リスタートを与えられた引数で起動します。`restart` はリスタート**名**(シンボルまたはキーワード — その名前を持つ最内のアクティブなリスタートが選ばれます)か、[`find-restart`](find-restart.md)/[`compute-restarts`](compute-restarts.md) が返したリスタート**オブジェクト**です。[`restart-case`](../macros/restart-case.md) のリスタートなら制御は確立元のフレームへ非局所的に移り、節本体が `arg...` を束縛して実行されます。[`restart-bind`](../macros/restart-bind.md) のリスタートなら束縛された関数が起動点で呼ばれ、その値が返ります。マッチするリスタートがアクティブでない場合はエラーを通知します。

```lisp
(handler-bind ((error (lambda (c) (invoke-restart :use-value 7))))
  (restart-case (error "no value")
    (:use-value (v) (list :used v)))) ; => (:USED 7)
```
