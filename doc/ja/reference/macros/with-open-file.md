# with-open-file

`(with-open-file (stream filename options...) body...)`

`filename` で指定されたファイルを開き、開いたストリームを `stream` に束縛し、その束縛のもとで本体のフォームを評価して、あとでファイルを閉じ、最後の本体フォームの値を返します。インタープリタと JVM では展開が本体を [`unwind-protect`](../special-forms/unwind-protect.md) で包むため、あらゆる脱出時（通常復帰、本体内でのエラー通知、`return`/`return-from`）にファイルが閉じられます。これは exception-handling サポートの導入以降、wasm-GC を含むすべてのバックエンドで成り立ちます(wasm-GC では `with-open-file` を使うプログラムは EH モードでコンパイルされ、`wasmtime -W exceptions=y`(37+)が必要です)。サポートされるオプションは `:direction`（リテラルのキーワード `:input`（デフォルト）または `:output`）、`:element-type`（リテラルの `'character`（デフォルト。テキストストリーム）または `'(unsigned-byte 8)`（`read-byte`/`write-byte` 用のバイナリストリーム））、および `:if-exists :append`（既存ファイルを切り詰めずに出力用に開き、すべての書き込みが末尾に追加される）です。コンパイラがファイルモードを静的に決定できるよう、いずれもリテラルでなければなりません。単純な `open`／`close` のペアに展開されるため、特別なストリーム型は関与しません。

ファイルシステムに触れるため、`with-open-file` はここでは実行可能な例ではなく静的に示します。

```console
(with-open-file (s "out.txt" :direction :output)
  (write-line "hello" s))
(with-open-file (s "out.txt" :direction :input)
  (read-line s)) ; => "hello"
(with-open-file (s "out.bin" :direction :output :element-type '(unsigned-byte 8))
  (write-byte 255 s)) ; => 255
(with-open-file (s "out.txt" :direction :output :if-exists :append)
  (write-line "again" s))
```
