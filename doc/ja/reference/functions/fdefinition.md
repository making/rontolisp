# fdefinition

`(fdefinition symbol)`

シンボルの関数値を返します。[`symbol-function`](symbol-function.md) と同じです (setf 関数名はサポートされません)。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```
