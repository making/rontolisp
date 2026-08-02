# fdefinition

`(fdefinition symbol)`

シンボルの関数値を返します。[`symbol-function`](symbol-function.md) と同じです (setf 関数名はサポートされません)。

クォートされたシンボルリテラル (`(fdefinition 'car)`) はコンパイラではコンパイル時に解決されます。実行時に計算されたシンボルは、結果が呼び出されたときにコンパイル済み名前レジストリを通じて遅延解決されます。相違点は [`symbol-function`](symbol-function.md) と同じです。

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```
