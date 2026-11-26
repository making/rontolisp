# torch:parameters

`(torch:parameters module)`

モジュールから到達できるすべてのパラメータを、登録順かつ同一性で重複排除して返します。自身のパラメータフィールド、続いてサブモジュールおよび保持しているサブモジュールの**リスト**のものを再帰的に集めます。2 つのレイヤーで共有された重みは 1 回だけ現れます。学習ループ (そしてオプティマイザ) が組み立てられる対象がこのリストです。パラメータを到達可能にするのに、フィールドのプロパティリストに入れる以上の宣言は要りません。

```lisp
(defparameter *net*
  (torch:sequential (torch:linear 4 8) (function torch:relu) (torch:linear 8 2)))
(length (torch:parameters *net*))                       ; => 4
(torch:shape (car (torch:parameters *net*)))            ; => (4 8)
```
