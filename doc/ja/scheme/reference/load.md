# load

`(load filename)`

`filename` で指名したファイルを実行時に評価します。ファイルはどのファイルと同様に
Scheme として読まれ、トップレベルのフォームがグローバル環境で順に評価されます。ロードした
ファイルでの定義はロードした側のものになり、`load` の後のフォームから見えます。相対パスは
ロードする側のファイルのディレクトリを基準に解決されます。`load` は未規定オブジェクトを返す。
[`--scheme-standard r7rs`](../standards.md) のもとでは、ロードされるファイルにも
ほかのファイルと同じ import で始まる規則が適用されます。

リテラルのファイル名によるトップレベル `load` はコンパイルされたバックエンドでは
コンパイル時にインライン化され、そのファイルのフォームがその場でコンパイルされます。
それ以外の `load` は実行時にロードします。

```scheme
(with-output-to-file "cfg.scm" (lambda () (display "(define mode 1)")))
(load "cfg.scm")
mode ; => 1
(delete-file "cfg.scm")
```
