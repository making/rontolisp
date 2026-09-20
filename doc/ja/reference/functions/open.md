# open

`(open filename &optional direction element-type)`

ファイルを開いてストリームを返します。省略可能な direction は `:input`（デフォルト。読み込み用に開く）、`:output`（作成または切り詰めて書き込み用に開く）、`:probe` です。`:probe` は既にクローズ済みのファイルストリーム（`typep` や `pathname` は問えますが読み込みはできません）を、ファイルが無ければ nil を返します。キーワード引数形式 `(open filename :direction :output :if-exists :append)` は既存ファイルを切り詰めずに書き込み用に開き、すべての書き込みが末尾に追加されます。`:if-exists` は**出力**オープンでのみ参照されます。`:supersede`（ここでのデフォルト）とバージョン系の綴り `:new-version`/`:rename`/`:rename-and-delete` はいずれも既存の内容を上書きし、`:error` は `file-error` を通知し、nil は開かずに nil を返します。実装していない値は `:overwrite` だけです。入力・probe オープンでは `:if-exists` は何が書かれていても参照されません。`:if-does-not-exist` は `:create`・`:error`・nil のいずれかで、CL の規定どおり、上書き出力オープンでは `:create`、`:probe` では nil、それ以外（**追記出力オープンを含む**）では `:error` がデフォルトです（したがって存在しないファイルへの `:if-exists :append` は `:if-does-not-exist :create` を書かない限り通知します）。`:external-format` は `:utf-8`/`:default` を受け付けます。どのバックエンドもこの1つしか書き出しません。省略可能な element type はストリームの種類を選択します。`'character`（デフォルト）は `read`/`read-line`/`write-line` 用のテキストストリームを、`'(unsigned-byte 8)`（サイズなしの `'unsigned-byte` も同様）は `read-byte`/`write-byte`/`read-sequence`/`write-sequence` 用のバイナリストリームを開きます。キーワード形式ではオプションの値は**計算された値**でも構いません（`(open path :direction dir :element-type type)`）。その値は呼び出しの実行時に読み取られ、対応するリテラル形にディスパッチされます。これにより、移植性のあるラッパーがオプションを引数として受け取れます。リテラルの値は従来どおりコンパイル時に解決され、サポート範囲外の値は呼び出しの実行時にエラーを通知します。返されるストリームは自己記述的な「値」で、`streamp` と `(typep s 'file-stream)` はこれに対して `t` を返します。値はバックエンドのハンドル（インタプリタ/JVM ではストリームテーブルへのインデックス、WASM では WASI ファイルディスクリプタ）を保持し、その実行内でのみ有効です。対応する読み書き関数に渡したうえで `close` してください。WASM ではパスはプリオープンされたディレクトリに対して解決されます。相対パスは最初の 1 つ、絶対パスは名前がそのパスの最長の接頭辞になるプリオープンディレクトリに対して解決されます。そのため `--dir` を付けて実行してください。ストリームを自動的にクローズする `with-open-file` の利用を推奨します。

```console
(let ((s (open "data.txt")))
  (print (read-line s))
  (close s))
```

これは `data.txt` を入力用に開き、最初の行を読み込み、ストリームをクローズします。代わりに `:output` を渡すと、書き込み用にファイルを作成または切り詰めます。`(open "data.bin" :input '(unsigned-byte 8))` は同じ種類のハンドルをバイナリモードで開きます。

開けないファイルはすべてのバックエンドで `file-error` を通知し、`OPEN: cannot open file <name>` を報告します。[`file-error-pathname`](file-error-pathname.md) は渡された指定子を返します。
