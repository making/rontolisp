# unexport

`(unexport symbols &optional package)`

[`export`](export.md) の逆操作です。`symbols` を `package` (既定では現在のパッケージ) の内部シンボルに戻します。シンボル自体は残り、コロン2つの綴りでは引き続き到達できますが、[`use-package`](use-package.md) を通じて修飾なしで見えることはなくなります。`t` を返します。

コンパイル時に消費され、`export` と同じ「定義より前に」の規則が適用されます。両方についてはそちらのページを参照してください。

```lisp
(defpackage #:partial (:use #:cl) (:export #:pub #:priv))
(in-package #:partial)
(unexport 'priv)
(defun pub () 1)
(defun priv () 2)
(in-package #:cl-user)
(+ (partial:pub) (partial::priv)) ; => 3
```
