# fmakunbound

`(fmakunbound function-name)`

`function-name` が関数を指さない状態に戻し、それを返します。未知の名前に対しては何もしません。`(setf name)` リストは、クォートされたものも実行時に組み立てたものも、`(setf name)` 関数を失効させます。

インタプリタではグローバルな関数束縛(および同名の `defmacro` マクロ)がそのまま削除されるため、以降の呼び出しは `The function X is undefined` を通知します。コンパイルバックエンドでは、クォートした名前はすべての参照から失効し、[トップレベル以外の `defun`](../special-forms/defun.md#below-the-top-level) が定義する関数も、実行時に計算した名前も同様です(例外は計算した文字列から組み立てた名前で、[`fdefinition`](fdefinition.md) を参照)。その名前の次の `defun` は、フォームを実行した位置で名前を定義し直します。総称関数では次の `defmethod` または `defgeneric` が名前を束縛し直し、それより前のメソッドも残ります(SBCL はメソッドのない新しい総称関数を作ります)。組み込みマクロと特殊形式は言語の一部でありイメージの関数名前空間には属さないので、影響を受けません。

```lisp
(defun greet (n) n)
(list (fboundp 'greet) (fmakunbound 'greet) (fboundp 'greet)) ; => (T GREET NIL)
```
