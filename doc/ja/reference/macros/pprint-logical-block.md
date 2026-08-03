# pprint-logical-block

`(pprint-logical-block (stream object &key prefix per-line-prefix suffix) body...)`

`prefix` を書き、本体（`stream` へ出力します）を評価し、`suffix` を書いて `nil` を返します。`object` がリストでないときは `write` で印字して本体は評価しません。これは Common Lisp 自身の規則で、リストかどうか分からない値をこのマクロで包んでも安全なのはそのためです。

rontolisp のストリームは桁位置を持たないため、このブロックが行を**折り返す**ことはなく、`:per-line-prefix` は `:prefix` の別名として受け付けます（ブロック内で行が独立して始まることがないためです）。詳しくは `pprint` を参照してください。

```lisp
(list (with-output-to-string (s)
        (pprint-logical-block (s '(1 2 3) :prefix "<" :suffix ">") (princ "body" s)))
      (with-output-to-string (s)
        (pprint-logical-block (s 5 :prefix "<" :suffix ">") (princ "body" s)))) ; => ("<body>" "5")
```
