# with-standard-io-syntax

`(with-standard-io-syntax form...)`

リーダーとプリンターの制御変数を CLHS 23.1.2 の標準値に束縛し、本体を `progn` として評価します。これにより本体は呼び出し側の設定に依存せず読み書きできます。

| 変数 | 値 |
| --- | --- |
| `*package*` | `cl-user` |
| `*print-array*`、`*print-escape*`、`*print-gensym*`、`*print-readably*`、`*read-eval*` | `t` |
| `*print-base*`、`*read-base*` | `10` |
| `*print-case*` | `:upcase` |
| `*print-circle*`、`*print-length*`、`*print-level*`、`*print-lines*`、`*print-miser-width*`、`*print-pretty*`、`*print-radix*`、`*print-right-margin*`、`*read-suppress*` | `nil` |
| `*print-pprint-dispatch*` | 新しい空のテーブル |
| `*read-default-float-format*` | `single-float` |
| `*readtable*` | 新しい標準の[リードテーブル](../../guides/readtables.md) |

`*print-readably*` が真のとき、プリンターの `prin1` 変換は `*print-length*` と `*print-level*` を無視し、`#:` を残します。`princ` と `~A` はこの変数を `nil` に束縛するので、その下の [`print-object`](../functions/print-object.md) メソッドには `nil` が見えます。

相違点:

- 読み戻せる表記を持たないオブジェクトを `*print-readably*` の下で印字すると、[`print-not-readable`](../functions/print-not-readable-object.md) をシグナルせず `#<...>` 形式を印字します。
- double は `d0` を付けずに印字します。すべての浮動小数点数が唯一の double 表現なので、`*read-default-float-format*` は情報提供用です。

```lisp
(let ((*print-base* 2))
  (with-standard-io-syntax (write-to-string 5))) ; => "5"
(with-standard-io-syntax
  (prin1-to-string (list 1 2 3))) ; => "(1 2 3)"
(with-standard-io-syntax *package*) ; => :CL-USER
```
