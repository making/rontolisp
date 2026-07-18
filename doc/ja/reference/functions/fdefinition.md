# fdefinition

`(fdefinition symbol)`

シンボルの関数値を返します。[`symbol-function`](symbol-function.md) と同じです (setf 関数名はサポートされません)。

コンパイラでは束縛がコンパイル時に解決されるため、引数はクオートされたシンボルリテラル (`(fdefinition 'car)`) である必要があります ([`symbol-function`](symbol-function.md) と同様)。実行時に計算されるシンボルはインタープリタのみで動作します。

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```
