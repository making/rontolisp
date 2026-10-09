# read-line

`(read-line &optional stream eof-error-p eof-value recursive-p)`

テキストを 1 行読み取り、末尾の改行を取り除いた文字列として返します(行を終えるのは改行 (LF) だけで、その直前 (または入力の終端の直前) のキャリッジリターン 1 つが取り除かれます。CR だけの区切りは行の一部として残り、[`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) のソケット経由の HTTP のような CRLF 終端の入力も通常の行として読めます)。引数がない場合は標準入力から読み取ります。`open` または `with-open-file` で開いたストリームを与えると、そのストリームから次の行を読み取ります。入力の終端では通知せず `eof-value` (デフォルト `nil`) を返します。`eof-error-p` のデフォルトは CL の `t` ではなく `nil` で、真を渡すと `end-of-file` を通知します。`recursive-p` は受け付けて無視します。意味を持つのはリーダーマクロの再帰的な読み取りだけです。2 番目の値 `missing-newline-p` は、改行ではなく入力の終端が行を終えたときに真で、CL と同じく入力の終端では `eof-value` とともに真を返します。すべてのバックエンドで返り、[Gray ストリーム](../../guides/gray-streams.md)の `stream-read-line` 経由でも同じです。3 つすべてのバックエンドで動作します。`read` と異なり、S 式として解析せずに生の行を返します。

```console
(print (read-line))
```

標準入力に `hello world` と入力すると、`read-line` は文字列 `"hello world"` を返します。入力が尽きると `nil` を返し、これはファイルを 1 行ずつ読むときの一般的なループ終了判定になります。

```lisp
(with-input-from-string (s (format nil "ab~%cd"))
  (list (multiple-value-list (read-line s))
        (multiple-value-list (read-line s))
        (multiple-value-list (read-line s nil :eof))))
;; => (("ab" NIL) ("cd" T) (:EOF T))
```
