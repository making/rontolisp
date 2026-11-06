# rontolisp:json-value

`(rontolisp:json-value document &rest path)` / `(rontolisp:json-value address size &rest path)`

JSON ドキュメントから値を 1 つ読み出します。
[`rontolisp:json-span`](rontolisp-json-span.md) がパスの先に見つけた値に対して
[`rontolisp:json-parse`](rontolisp-json-parse.md) が返すものと、値が見つかった
ことを示す `t` を返します。Lisp の値になるのはその値だけで、ドキュメントの
残りは検査されて読み飛ばされます。そのため大きな本文から数個のフィールドを
読むコストは、走査のコストとそのフィールドが占めるメモリで済みます。

ドキュメントとパスは `json-span` と同じです。ドキュメントは文字列、
`(unsigned-byte 8)` のベクタ、または wasm-GC バックエンドでの線形メモリの
アドレスとサイズで、パスはキーとインデックスの並びです。2 つの形のあいだで
フィルタを移植するときに変えるのは、先頭の引数だけです。

```lisp
(rontolisp:json-value "{\"usage\": {\"input_tokens\": 12, \"output_tokens\": 34}}" "usage" "output_tokens")   ; => 34, T
(gethash "input_tokens" (rontolisp:json-value "{\"usage\": {\"input_tokens\": 12, \"output_tokens\": 34}}" "usage"))   ; => 12
(rontolisp:json-value "{\"usage\": null}" "usage")   ; => NULL, T
(rontolisp:json-value "{\"usage\": null}" "model")   ; => NIL, NIL
```

2 番目の値は、`gethash` の 2 番目の値と同じく、`false`(`nil`, `t`)と存在しない
値(`nil`, `nil`)を区別します。

## エラー

`json-span` が受け付けないドキュメントには、そのエラー `json-span: ...` を通知
します。[`json-span` のページ](rontolisp-json-span.md#errors)を参照してください。

## バックエンドの対応

すべてのバックエンド、すべての WASM モードで動作します。`json-span` の走査の
あと、値のバイト列だけに `json-parse` を適用します。
