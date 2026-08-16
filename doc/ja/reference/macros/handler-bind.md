# handler-bind

`(handler-bind ((type handler)...) body...)`

ハンドラを確立して `body...` を評価します。本体の中でコンディションが通知されると — [`error`](error.md)、[`signal`](signal.md)、[`warn`](warn.md)、[`cerror`](cerror.md) によって — マッチする各ハンドラが**シグナル点で、巻き戻しの前に**、コンディションオブジェクトを唯一の引数として呼ばれます。ここが [`handler-case`](handler-case.md) との違いです: シグナル元とハンドラの間のスタックはまだ生きているため、ハンドラは本体の*内側*の [`restart-case`](restart-case.md) が確立したリスタートを [`invoke-restart`](../functions/invoke-restart.md) してそこへ制御を移せます。正常にリターンしたハンドラは**辞退**したことになり、探索は外側のハンドラへ続きます。誰も処理しなかった `error` は `handler-bind` がなかった場合とまったく同様に中断します(あるいは外側の `handler-case` に捕捉されます)。`type` には `handler-case` の節の型がすべて使えます([`define-condition`](define-condition.md) のクラスと、組み込みエラーが持つクラス(`type-error`、`division-by-zero` など。[`handler-case`](handler-case.md) を参照)を含む)。ハンドラ式は `handler-bind` に入るときに評価されます。

`--no-gc` を除くすべてのバックエンドでサポートされます。リスタートシステムを使うプログラムは wasm-GC バックエンドでは EH モードでコンパイルされるため、`wasmtime run`/`wasmtime serve` に `-W exceptions=y` を追加してください。**組み込み**が起こすエラー(`(car 5)` のような型エラー、範囲外の `aref`、未定義関数)でもハンドラは実行されます: インタプリタは通知されたコンディションと同じくシグナル点で実行し、コンパイルバックエンドはエラーが `handler-bind` 自身を越えて巻き戻るときに実行します — その時点では本体の*内側*で確立されたリスタートは消えており、間にある [`unwind-protect`](../special-forms/unwind-protect.md) のクリーンアップも実行済みです(**通知された**コンディションはどのバックエンドでも正確なシグナル点セマンティクスを保ちます)。wasm-GC バックエンドでは、通知ではなくトラップになる失敗(`(car 5)` はキャスト失敗にコンパイルされます。整数のゼロ除算も同様)は従来どおりハンドラを実行せずにプログラムを終了させます — ハンドラに届くのはコンディションチャネルに乗るもの(通知されたコンディション、未定義関数の呼び出し)だけです。

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

ハンドラは**内側から順に**実行され、本体の内側で確立された [`handler-case`](handler-case.md) もそのひとつです: それにマッチするコンディションはそこで処理され、外側のハンドラは呼ばれません。内側の `handler-case` のどの節にもマッチしなかったときにだけ、探索は外側のハンドラに届きます。

```lisp
(block b
  (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
    (handler-case (error "boom")
      (error () :caught)))) ; => :CAUGHT
```

組み込みが起こすエラーでもハンドラは実行されます — テストフレームワークが壊れたテスト本体を実行の中断ではなく失敗の記録に変える仕組みです:

```lisp
(block b
  (handler-bind ((error (lambda (e) (return-from b :caught))))
    (car 1))) ; => :CAUGHT
```
