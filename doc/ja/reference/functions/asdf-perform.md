# asdf:perform

`(asdf:perform operation component)`

本物の ASDF と同じく、システムのロードを構成する動作はすべてこの総称関数を通ります。
[`asdf:load-system`](asdf-load-system.md) は次の順に perform します。まずシステムの
`prepare-op`、次にコンポーネントファイルごとにロード順で `prepare-op`・`compile-op`・
`load-op`、最後にシステムの `compile-op` と `load-op` です。したがって、ライブラリが
追加したメソッドはコンポーネントのロードのたびにその周りで実行されます。asdf-flv
（fiveam が使うファイルローカル変数）の `:around` メソッドはファイルごとに変数を
束縛し直すので、あるファイルで行った代入はそのファイルの中にとどまります。

```console
(defmethod asdf:perform :around ((o asdf:load-op) (c asdf:cl-source-file))
  (progv *file-local-variables* (mapcar #'symbol-value *file-local-variables*)
    (call-next-method)))
```

operation は `asdf:operation` かそのサブクラス `asdf:prepare-op`・`asdf:compile-op`・
`asdf:load-op`・`asdf:test-op` のインスタンスです。`asdf:make-operation` はクラスごとに
一つのインスタンスを返し、クラス名で指定した operation（`(asdf:perform 'asdf:load-op c)`）
はそのインスタンスを意味します。デフォルトメソッドは、存在するクラスについては本物の
ASDF のものと同じです。`asdf:cl-source-file` への `load-op` はソースファイルをロードし、
ソースファイルへの `compile-op` は何もしません。fasl がないので、ソースファイルは
ロードすることでコンパイルされるからです。システム（親コンポーネント全般）へはどの
operation も何もしません。ただし、システムへの `test-op` は、その `.asd` が記録した
`:perform (test-op (o c) ...)` の本体を実行します（[`asdf:test-system`](asdf-test-system.md)）。
`.asd` 自体に書いたメソッドは実行されません。`.asd` はデータとして読まれるからです。

`asdf:input-files` と `asdf:output-files` も総称関数です。ソースファイルへの
`compile-op` と `load-op` はそのパス名を読み、ファイルを書く operation はありません
（`output-files` は `nil` を返します）。`asdf:source-file-type` は、ライブラリが
メソッドを定義できるように存在します（lass が定義します）。コンポーネントの拡張子は
`.asd` の解析時に決まります。`asdf:operation-done-p` も同じ理由で存在し、既定では
`t` を返します（arnesi の `clean-op` がメソッドを定義します）。
`asdf:compile-bundle-op` は `asdf:operation` のサブクラスですが、バンドルする fasl が
ないため `asdf:operate` は受け付けません。`asdf/interface`（本物の ASDF が `asdf` パッケージ自体に
付けている名前）と、本物の ASDF の内部パッケージ（`asdf/action`・`asdf/component`・
`asdf/lisp-action` など）は、これらと同じシンボルを指します。

## バックエンドサポート

4 つすべてのバックエンドで、同じ順序で動作します。コンパイル済みプログラムには
包むべきロードがありません（システムはコンパイル時にスプライスされています）。
そこで、プログラムが `asdf:perform` のメソッドを定義すると、その定義より後に
スプライスされたコンポーネントは、それぞれ独立した単位としてコンパイルされ、
その `load-op` を perform するときに渡すサンクとして実行されます。フォームは
トップレベルフォームのままなので定義はその位置で収集されますが、`:around`
メソッドはその周りで実行されます。定義より前のコンポーネントと、メソッドを定義しない
プログラムのコンポーネントは、従来どおりフォームを順に実行します。コンパイル済みの
バックエンドではメソッドのディスパッチが静的なので、メソッドはプログラムの開始時から
適用されます。そのため、二つのメソッド定義の間でロードされるコンポーネントには両方が
適用されます。`--no-gc` は、この単位を必要とするプログラムを拒否します。それ以外の
場面で実行時にコンポーネントへ `load-op` を perform すると、コンパイル済みの
バックエンドでは、実行時の `load-system` と同じくエラーを通知します。
