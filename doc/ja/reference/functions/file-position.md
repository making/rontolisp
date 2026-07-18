# file-position

`(file-position stream [position])`

lite 版: 常に nil を返します。ストリームはシーク (位置変更) をサポートしないため、`ignore-errors` で保護する移植性のある呼び出し側は非シークのフォールバック経路を通ります。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(with-input-from-string (s "abc")
  (file-position s)) ; => nil
```
