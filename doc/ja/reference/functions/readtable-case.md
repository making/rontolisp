# readtable-case

`(readtable-case readtable)`

Lite スタブ: 常に `:upcase` を返します -- リーダーはリードテーブル駆動ではなく、エスケープされていないシンボル名を常に大文字化します。これは標準リードテーブルの `:upcase` モードそのものです。引数は評価されますが無視されます（`*readtable*` 変数は存在しますが `nil` で初期化されています）。s-sql の `from-sql-name` のようにリードテーブルの case で分岐するライブラリコードが標準モードの分岐を取るために存在します。

```lisp
(readtable-case *readtable*) ; => :UPCASE
```
