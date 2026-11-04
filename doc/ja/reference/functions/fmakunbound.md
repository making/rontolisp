# fmakunbound

`(fmakunbound function-name)`

`function-name` が関数を指さない状態に戻し、それを返します。未知の名前に対しては何もしません。`(setf name)` リストは、クォートされたものも実行時に組み立てたものも、`(setf name)` 関数を失効させます。

インタプリタではグローバルな関数束縛(および同名の `defmacro` マクロ)がそのまま削除されるため、以降の呼び出しは `The function X is undefined` を通知します。コンパイルバックエンドでは、クォートした名前はすべての参照から失効し、[トップレベル以外の `defun`](../special-forms/defun.md#below-the-top-level) が定義する関数も同様です。実行時に計算した名前でトップレベルの `defun` を失効させると、**遅延束縛**の参照 -- [`fboundp`](fboundp.md)、計算した名前経由の `funcall`/`apply`/`eval` -- に対してのみ失効します。コンパイラがすでに直接束縛した呼び出し箇所は取り消せないためです。組み込みマクロと特殊形式は言語の一部でありイメージの関数名前空間には属さないので、影響を受けません。

```lisp
(defun greet (n) n)
(list (fboundp 'greet) (fmakunbound 'greet) (fboundp 'greet)) ; => (T GREET NIL)
```
