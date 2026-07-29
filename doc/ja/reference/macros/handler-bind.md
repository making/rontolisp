# handler-bind

`(handler-bind ((type handler)...) body...)`

ハンドラを確立して `body...` を評価します。本体の中でコンディションが通知されると — [`error`](error.md)、[`signal`](signal.md)、[`warn`](warn.md)、[`cerror`](cerror.md) によって — マッチする各ハンドラが**シグナル点で、巻き戻しの前に**、コンディションオブジェクトを唯一の引数として呼ばれます。ここが [`handler-case`](handler-case.md) との違いです: シグナル元とハンドラの間のスタックはまだ生きているため、ハンドラは本体の*内側*の [`restart-case`](restart-case.md) が確立したリスタートを [`invoke-restart`](../functions/invoke-restart.md) してそこへ制御を移せます。正常にリターンしたハンドラは**辞退**したことになり、探索は外側のハンドラへ続きます。誰も処理しなかった `error` は `handler-bind` がなかった場合とまったく同様に中断します(あるいは外側の `handler-case` に捕捉されます)。`type` には `handler-case` の節の型がすべて使えます([`define-condition`](define-condition.md) のクラスを含む)。ハンドラ式は `handler-bind` に入るときに評価されます。

`--no-gc` を除くすべてのバックエンドでサポートされます。リスタートシステムを使うプログラムは wasm-GC バックエンドでは EH モードでコンパイルされるため、`wasmtime run`/`wasmtime serve` に `-W exceptions=y` を追加してください。ハンドラが実行されるのは Lisp コード(`error`/`signal`/`warn`/`cerror` とその上に組まれたライブラリコード)が通知したコンディションです。組み込み内部の実行時エラー(`(car 5)` のような型エラー)は `handler-bind` ハンドラを実行せずに巻き戻ります — それらはインタプリタと JVM では従来どおり `handler-case` が捕捉します。

```lisp
(handler-bind ((error (lambda (c) (invoke-restart :use-value 42))))
  (restart-case (error "boom")
    (:use-value (v) (list :recovered v)))) ; => (:RECOVERED 42)
```

リターンしたハンドラは辞退し、エラーは伝播を続けます:

```lisp
(let ((log nil))
  (handler-case
      (handler-bind ((error (lambda (c) (setq log :seen))))
        (error "boom"))
    (error (e) (list :caught log)))) ; => (:CAUGHT :SEEN)
```
