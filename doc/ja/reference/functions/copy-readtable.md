# copy-readtable

`(copy-readtable &optional from to)`

ライト版スタブ: 何もせず `nil` を返します。リーダーはリードテーブル駆動ではないため、コピーすべきリードテーブルオブジェクトが存在しません (変数 `*readtable*` は存在しますが `nil` に初期化されています)。引数は評価されます。ライブラリでよくあるヘッダーイディオム `(defparameter *my-readtable* (copy-readtable nil))` がロードできるように存在します。

```lisp
(copy-readtable nil) ; => NIL
```
