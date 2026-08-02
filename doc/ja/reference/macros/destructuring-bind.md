# destructuring-bind

`(destructuring-bind pattern form body...)`

`pattern` の変数を `form` の値の対応する部分に束縛して本体を評価します。パターンはマクロ形式のラムダリストです: 必須位置ではパターンをネストでき、`&optional`（デフォルト値と supplied-p 付き）、`&rest`/`&body`、`&key`（デフォルト値と supplied-p 付き）、`&aux` をサポートします -- ネストしたパターンの中でも使えます。ドット付き末尾は `&rest` の略記です（`((a &rest b) . rest)` は先頭要素以降のすべてを `rest` に束縛します）。`&whole`（パターンの先頭要素として）は変数をソースリスト全体に束縛します。`&environment` はサポートされません。マッチングは寛容です: 足りない位置は nil に束縛され、余った要素は無視されます（不一致エラーはありません）。`&key` の下で宣言されていないキーワードだけがエラーを通知します（`&allow-other-keys` を指定した場合を除く）。

```lisp
(destructuring-bind (a (b c) &optional (d 10)) '(1 (2 3))
  (list a b c d)) ; => (1 2 3 10)
```

```lisp
(destructuring-bind (name &key (size 1) color) '(box :color red)
  (list name size color)) ; => (box 1 red)
```
