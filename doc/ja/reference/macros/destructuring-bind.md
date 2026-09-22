# destructuring-bind

`(destructuring-bind pattern form body...)`

`pattern` の変数を `form` の値の対応する部分に束縛して本体を評価します。パターンはマクロ形式のラムダリストです: 必須位置ではパターンをネストでき、`&optional`（デフォルト値と supplied-p 付き）、`&rest`/`&body`、`&key`（デフォルト値と supplied-p 付き）、`&aux` をサポートします -- ネストしたパターンの中でも使えます。ドット付き末尾は `&rest` の略記です（`((a &rest b) . rest)` は先頭要素以降のすべてを `rest` に束縛します）。`&whole`（パターンの先頭要素として）は変数をソースリスト全体に束縛します。`&environment` はサポートされません。パターンを超える要素は -- ドット付き末尾、`&rest`/`&body`、`&key` のいずれも受け取らない階層では -- `program-error` を通知します。`&key` の下で宣言されていないキーワードも同様です（`&allow-other-keys` を指定した場合を除く）。足りない位置は引き続き nil に束縛されます（エラーにはなりません）。

```lisp
(destructuring-bind (a (b c) &optional (d 10)) '(1 (2 3))
  (list a b c d)) ; => (1 2 3 10)
```

```lisp
(destructuring-bind (name &key (size 1) color) '(box :color red)
  (list name size color)) ; => (BOX 1 RED)
```

```lisp
(handler-case (destructuring-bind (a b) '(1 2 3) (list a b))
  (program-error () :too-many)) ; => :TOO-MANY
```
