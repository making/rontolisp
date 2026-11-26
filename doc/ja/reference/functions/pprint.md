# pprint pprint-newline pprint-indent pprint-tab

`(pprint object &optional stream)` -- `(pprint-newline kind &optional stream)` -- `(pprint-indent relative-to n &optional stream)` -- `(pprint-tab kind colnum colinc &optional stream)`

`pprint` は改行してから `object` を読み戻せる形式で出力し、値を返しません。残りの 3 つはプリティプリンタのレイアウト操作です。**rontolisp のストリームは桁位置を持たない**ため、実際に効くのは `(pprint-newline :mandatory)` だけで、`*print-pretty*` が真のときに改行を書きます。条件付きの 3 種類（`:linear`、`:fill`、`:miser`）と `pprint-indent`、`pprint-tab` は受け付けて何もせず、`*print-right-margin*` / `*print-miser-width*` / `*print-lines*` も同じ理由で無効です。テキストは行が十分に広いものとして出力されます。

```lisp
(with-output-to-string (s) (princ "a" s) (pprint-newline :fill s) (princ "b" s)) ; => "ab"
```
