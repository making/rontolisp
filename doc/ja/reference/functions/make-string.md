# make-string

`(make-string size &key initial-element element-type)`

`size` 文字からなる新しい文字列を返します。各文字は `:initial-element`（デフォルトは空白。埋め文字は標準では処理系定義です）に等しくなります。`:element-type` は受け付けますが無視されます -- rontolisp の文字列表現は単一だからです。`--no-gc` を除くすべてのバックエンドで利用できます。

```lisp
(make-string 3 :initial-element #\x) ; => "xxx"
```
