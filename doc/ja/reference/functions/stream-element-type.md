# stream-element-type

`(stream-element-type stream)`

ストリームが運ぶ要素型を返します。バイナリの**ファイル**ストリームは、開いたときの整数型を SBCL と同じ規則で広げた型を返します。1 オクテットに収まる符号なしの型（`bit`、`(unsigned-byte 3)`、`(integer 100 200)`）は `(unsigned-byte 8)`、`(unsigned-byte 9)` から `16` までは `(unsigned-byte 16)`、`(signed-byte 20)` は `(signed-byte 32)` です（[`open`](open.md) を参照）。それ以外のストリーム（文字ファイルストリーム、文字列ストリーム、ソケット、標準ストリーム）は `character` を返します。4 つのバックエンドすべてで動作します。

```lisp
(with-input-from-string (s "x")
  (stream-element-type s)) ; => CHARACTER
```

```console
(with-open-file (s "data.bin" :element-type '(unsigned-byte 12))
  (stream-element-type s)) ; => (UNSIGNED-BYTE 16)
```
