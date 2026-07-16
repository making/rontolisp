# rontolisp:make-stream

`(rontolisp:make-stream)`

新しいオープン状態の非同期ストリームを作成します。1 つの値が読み側と書き側の
両端を持ちます: 生産側は [`rontolisp:stream-write`](rontolisp-stream-write.md)
でチャンクを追加して [`rontolisp:stream-close`](rontolisp-stream-close.md) で
終了し、消費側は [`rontolisp:stream-read`](rontolisp-stream-read.md) でチャンクを
取り出す (各 read は future を返します) か、
[`rontolisp:read-all`](rontolisp-read-all.md) で文字列チャンクを一括で
読み切ります。

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "hello ")
  (rontolisp:stream-write s "world")
  (rontolisp:stream-close s)
  (rontolisp:await (rontolisp:read-all s)))   ; => "hello world"
```

## バックエンドのサポート

非同期ストリームは現在インタプリタと JVM バックエンドに存在します。WASM
バックエンドはストリーム操作をコンパイル時に拒否します。
