# load-time-value

`(load-time-value form [read-only-p])`

lite 版: `form` そのものに展開されるため、ロード時に一度ではなく使用のたびに再評価されます (実在ライブラリがこのマクロで守る純粋なテーブル参照では等価です)。`read-only-p` は受理されますが無視されます。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(load-time-value (+ 1 2)) ; => 3
```
