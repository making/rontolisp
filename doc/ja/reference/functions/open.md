# open

`(open filename &optional direction element-type)`

ファイルを開いてストリームを返します。省略可能な direction はリテラルのキーワード `:input`（デフォルト。読み込み用に開く）または `:output`（作成または切り詰めて書き込み用に開く）でなければなりません。コンパイラがファイルモードを静的に決定できるよう、これはリテラルである必要があります。省略可能な element type（これもリテラル）はストリームの種類を選択します。`'character`（デフォルト）は `read`/`read-line`/`write-line` 用のテキストストリームを、`'(unsigned-byte 8)` は `read-byte`/`write-byte`/`read-sequence`/`write-sequence` 用のバイナリストリームを開きます。返されるストリームは不透明なハンドル（インタプリタ/JVM ではストリームテーブルへのインデックス、WASM では WASI ファイルディスクリプタ）で、その実行内でのみ有効です。対応する読み書き関数に渡したうえで `close` してください。WASM ではパスは最初の preopen ディレクトリを基準に解決されるため、`--dir` を付けて実行してください。ストリームを自動的にクローズする `with-open-file` の利用を推奨します。

```console
(let ((s (open "data.txt")))
  (print (read-line s))
  (close s))
```

これは `data.txt` を入力用に開き、最初の行を読み込み、ストリームをクローズします。代わりに `:output` を渡すと、書き込み用にファイルを作成または切り詰めます。`(open "data.bin" :input '(unsigned-byte 8))` は同じ種類のハンドルをバイナリモードで開きます。
