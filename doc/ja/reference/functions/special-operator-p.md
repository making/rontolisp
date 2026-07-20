# special-operator-p

`(special-operator-p symbol)`

ライト版スタブ: 常に `nil` を返します。コンパイル済みプログラムには演算子テーブルの実体がなく、インタープリタのディスパッチもデータとして公開されません。コールドブランチの内省ヘルパー(cl-ppcre の `regex-apropos`)をコンパイル可能にするために存在します。

```lisp
(special-operator-p 'if) ; => NIL
```
