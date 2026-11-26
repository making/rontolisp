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

`*print-readably*` が真のとき、プリンターの `prin1` 変換は `*print-length*` と `*print-level*` を無視し、`#:` を残します。読み戻せる表記を持たない値は、どの深さにあっても [`print-not-readable`](../functions/print-not-readable-object.md) をシグナルします。対象は関数、固有のメソッドを持たないクラスやコンディションのインスタンス、[`print-unreadable-object`](print-unreadable-object.md) です。`princ` と `~A` はこの変数を `nil` に束縛するので、その下の [`print-object`](../functions/print-object.md) メソッドには `nil` が見え、シグナルも起きません。コンディションのメッセージも同じ束縛の下で組み立てるので、`(error "bad ~S" #'car)` は指定どおりのエラーをシグナルします。ハッシュテーブルは `*read-eval*` が真なら構築式の `#.` として印字し、偽ならシグナルします。

相違点:

- パッケージと標準ストリームはこの処理系での値 (`:CL-USER`、`T`) として印字するので、シグナルしません。
- random state はシグナルします。SBCL は構造体として書き出します。
- エントリを持つハッシュテーブルは `#.(LET ((H (MAKE-HASH-TABLE ...))) (SETF (GETHASH ...) ...) ... H)` と印字します。SBCL でも読めます。`#.` を評価するのはインタプリタのリーダーだけで、コンパイル済みのリーダーは拒否します。
- double は `d0` を付けずに印字します。すべての浮動小数点数が唯一の double 表現なので、`*read-default-float-format*` は情報提供用です。

```lisp
(let ((*print-base* 2))
  (with-standard-io-syntax (write-to-string 5))) ; => "5"
(with-standard-io-syntax
  (prin1-to-string (list 1 2 3))) ; => "(1 2 3)"
(with-standard-io-syntax *package*) ; => :CL-USER
(handler-case (with-standard-io-syntax (prin1-to-string #'car))
  (print-not-readable () :refused)) ; => :REFUSED
```
