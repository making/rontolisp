# read-char

`(read-char &optional stream eof-error-p eof-value recursive-p)`

`stream` (デフォルトは標準入力) から 1 文字読み取って返します。ストリームには `open`/`with-open-file` で開いたファイルストリーム、`with-input-from-string` の文字列入力ストリーム、[TCP/TLS ソケットハンドル](../../guides/tcp-sockets.md)を渡せます。ソケットの場合はワイヤー上の UTF-8 バイトから 1 文字を組み立てるため、1 つの接続で `read-char` と `read-byte` を混在させられます。入力の終端では `end-of-file` コンディションを通知しますが、`eof-error-p` が `nil` の場合は `eof-value` (デフォルト `nil`) を返します。通知されるのは登録済みの `end-of-file` クラスなので、CL のレキサーによくある形 -- 読み取りループを `(handler-case ... (end-of-file (e) ...))` で囲む -- がそのまま終了します。文字はどのバックエンドでも Unicode のコードポイント 1 つで、ストリームの UTF-8 からデコードします。不正な入力は全バックエンドで JVM のデコーダーと同じに読みます。どのシーケンスの先頭にもならないバイトは U+FFFD (置換文字) 1 文字になり、不正なバイトか入力の終わりで途切れたシーケンスも U+FFFD 1 文字になります。途切れさせたバイトは次の文字の先頭になるので、バイト列 `41 E9 42` は `A`、U+FFFD、`B` と読みます。`read-line`、`peek-char`、`read-sequence` も同じ規則でデコードします。`recursive-p` は受け付けて無視します。意味を持つのはリーダーマクロの再帰的な読み取りだけです。

```lisp
(with-input-from-string (s "hi")
  (let* ((c1 (read-char s))
         (c2 (read-char s))
         (c3 (read-char s nil :end)))
    (list c1 c2 c3))) ; => (#\h #\i :END)
```
