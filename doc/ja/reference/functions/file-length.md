# file-length

`(file-length stream)`

lite 版: 常に nil を返します (ストリーム長は追跡されません)。移植性のある呼び出し側は長さ不明のフォールバック経路を通ります。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(with-input-from-string (s "abc")
  (file-length s)) ; => nil
```
