# torch:item

`(torch:item tensor)`

スカラー (または要素 1 個の) テンソルの唯一の要素を数値として返します。損失値を印字やログのためにグラフから取り出す方法です。要素が複数あるテンソルはエラーを通知します。

```lisp
(torch:item (torch:sum (torch:tensor '(1.0 2.0 3.0)))) ; => 6.0
(torch:item (torch:tensor '(7.0)))                      ; => 7.0
```
