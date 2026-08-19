# torch:backward

`(torch:backward tensor)`

スカラー (要素 1 個の) テンソルから逆方向の自動微分を実行します。勾配を `1.0` でシードし、記録されたテープを逆位相順に辿り、各演算の入力勾配を親テンソルに蓄積します。複数の経路から到達するテンソル (残差接続や再利用された埋め込み行) は合計を受け取ります。結果は [`torch:grad`](torch-grad.md) で読み、戻り値は `nil` です。要素が複数あるテンソルはエラーを通知します。

勾配は中間テンソルにも保持され、backward を繰り返すと蓄積され続けます。学習ステップの間では [`torch:zero-grad`](torch-zero-grad.md) でパラメータをクリアしてください。

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(defparameter *loss* (torch:sum (torch:mul *w* *w*)))
(torch:backward *loss*)
(torch:grad *w*) ; => #d(2.0 4.0)
```
