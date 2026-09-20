# interactive-stream-p

`(interactive-stream-p stream)`

ストリームが対話的なデバイスに接続されているかどうかを返します。ここでは常に nil です。端末とパイプを区別できるバックエンドがないためです。引数はストリーム指定子ではなくストリームそのもので、それ以外は `type-error` になります。

```lisp
(with-output-to-string (s) (princ (interactive-stream-p s) s)) ; => "NIL"
```
