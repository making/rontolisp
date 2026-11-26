# char-whitespace?

`(char-whitespace? char)`

`char` が Unicode の `White_Space` 属性を持てば `#t`、そうでなければ `#f` を返します。ASCII の空白・タブ・改行・垂直タブ・改ページ・復帰、U+0085、ノーブレークスペース、Unicode の空白と行区切り・段落区切りが該当します。Gauche は U+0085 に `#f` を返します。

```scheme
(char-whitespace? #\space) ; => #t
(char-whitespace? #\tab) ; => #t
(char-whitespace? #\a) ; => #f
```
