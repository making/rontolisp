# parameterize

`(parameterize ((parameter value)...) body...)`

すべての `parameter` と `value` を評価し、各値をそのパラメータの変換手続きに通してから、各パラメータが変換後の値を返す状態で `body` を評価し、最後の式の値を返します。束縛は動的で、`body` から呼ばれた手続きにも見えます。`body` をどのように抜けても -- 通常の終了、`raise` やエラー、脱出する継続、`exit` -- 各パラメータは元の値に戻ります。変換手続きが失敗した場合、どのパラメータも変わりません。`body` の先頭には定義を書けます。

仕様との差異:

- パラメータオブジェクトでない `parameter` はエラーです。手続きは問い合わせのため引数 1 つで呼ばれます。それ以外は名前を挙げて拒否されます。
- `eval` の中の `parameterize` は名前を挙げて拒否されます。

```scheme
(define width (make-parameter 10))
(define (show) (width))
(parameterize ((width 80)) (show)) ; => 80
(show) ; => 10
(guard (e (#t (width))) (parameterize ((width 0)) (raise 'oops))) ; => 10
(let ((p (make-parameter 1))) (parameterize ((p 2)) (p))) ; => 2
```
