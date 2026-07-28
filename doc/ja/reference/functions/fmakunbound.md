# fmakunbound

`(fmakunbound symbol)`

`symbol` が関数を指さない状態に戻し、そのシンボルを返します。未知の名前に対しては何もしません。

インタプリタではグローバルな関数束縛(および同名の `defmacro` マクロ)がそのまま削除されるため、以降の呼び出しは `The function X is undefined` を通知します。コンパイルバックエンドでは**遅延束縛**の参照 -- [`fboundp`](fboundp.md)、シンボル経由の `funcall`/`#'name`/`eval` -- に対してのみ名前が失効します。コンパイラがすでに直接束縛した呼び出し箇所は取り消せないためです。組み込みマクロと特殊形式は言語の一部でありイメージの関数名前空間には属さないので、影響を受けません。

```lisp
(defun greet (n) n)
(list (fboundp 'greet) (fmakunbound 'greet) (fboundp 'greet)) ; => (T GREET NIL)
```
