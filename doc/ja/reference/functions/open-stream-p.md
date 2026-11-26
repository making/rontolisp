# open-stream-p

`(open-stream-p stream)`

ストリームが開いている間は `t`、閉じられた後は `nil` を返します。「開いていれば閉じる」というイディオムが二重クローズもリークも避けるために問い合わせる述語です。ファイル、文字列、ソケットのいずれのストリームでも、答えは全バックエンドで同じです。シノニムストリームは参照先の答えを返し、`t` 指定子は常に開いています。インタプリタと JVM はストリームテーブルから答え ([`close`](close.md) がエントリを削除します)、相手側から閉じられたソケットについても `nil` を返します。WASM バックエンドはディスクリプタが次の `open` で再利用されるため、`close` がストリームの値に付ける印を読みます。

```lisp
(with-input-from-string (s "x") (open-stream-p s)) ; => T
(let ((s (make-string-output-stream)))
  (close s)
  (open-stream-p s)) ; => NIL
```

クローズ後の挙動はファイルを触るため、静的に示します:

```console
(let ((s (open "f.txt" :direction :input)))
  (open-stream-p s)   ; => T
  (close s)
  (open-stream-p s))  ; => NIL
```
