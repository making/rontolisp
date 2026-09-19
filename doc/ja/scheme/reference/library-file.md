# (scheme file)

ファイルポートと、ファイルの存在確認・削除です。ファイル名は文字列で、相対パスは
カレントディレクトリを基準にします。WebAssembly では、ホストが事前に開いたディレクトリ
（`wasmtime run --dir`）の中のファイルにしか届きません。

| 名前 | 例 | 結果 |
|---|---|---|
| `open-input-file` | `(open-input-file "hello.txt")` | `hello.txt` を読むテキスト入力ポート |
| `open-output-file` | `(open-output-file "out.txt")` | `out.txt` を空にしてから書くテキスト出力ポート |
| `open-binary-input-file` | `(open-binary-input-file "hello.txt")` | `hello.txt` を読むバイナリ入力ポート |
| `open-binary-output-file` | `(open-binary-output-file "bytes.bin")` | `bytes.bin` を空にしてから書くバイナリ出力ポート |
| `call-with-input-file` | `(call-with-input-file "hello.txt" read-line)` | `"hello"` |
| `call-with-output-file` | `(call-with-output-file "out.txt" (lambda (p) (write '(1 2) p)))` | `out.txt` に `(1 2)` を書いて閉じる |
| `with-input-from-file` | `(with-input-from-file "hello.txt" read-line)` | `"hello"` |
| `with-output-to-file` | `(with-output-to-file "out.txt" (lambda () (display "hi")))` | `out.txt` に `hi` を書いて閉じる |
| `file-exists?` | `(file-exists? "no-such-file.txt")` | `#f` |
| `delete-file` | `(delete-file "hello.txt")` | `hello.txt` を削除する |
