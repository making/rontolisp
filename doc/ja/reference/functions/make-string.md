# make-string

`(make-string size &key initial-element element-type)`

`size` 文字からなる新しい文字列を返します。各文字は `:initial-element`（デフォルトは空白。埋め文字は標準では処理系定義です）に等しくなります。`:element-type` は受け付けますが無視されます -- rontolisp の文字列表現は単一だからです。結果はすべてのバックエンドで可変なバッファです。[`replace`](replace.md)、`(setf (char ...))`、`(setf (subseq ...))` はその場で書き込み、その書き込みはそのバッファへのすべての参照から見えます。`--no-gc` を除くすべてのバックエンドで利用できます。

```lisp
(make-string 3 :initial-element #\x) ; => "xxx"
```

```lisp
(let ((buf (make-string 5)))
  (replace buf "ab")
  (replace buf "cde" :start1 2)
  buf) ; => "abcde"
```
