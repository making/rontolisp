# stream-element-type

`(stream-element-type stream)`

常にシンボル `character` を返します。rontolisp のストリームはすべて文字ストリームです (バイナリ要素型はありません)。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(with-input-from-string (s "x")
  (stream-element-type s)) ; => character
```
