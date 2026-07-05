# rontolisp:list-macros

`(rontolisp:list-macros &optional package)`

パッケージのマクロシンボル (関数値を持たない演算子) をアルファベット順にソートして
返します。省略可能なパッケージ指定子のデフォルトは `:cl` で、キーワード、裸のシンボル、
クォートされたシンボル、または文字列を指定できます。存在しないパッケージはエラーです。
詳しくは [パッケージのイントロスペクション](../packages.md#package-introspection)
を参照してください。

```lisp
(rontolisp:list-macros) ; => (and assert case ccase check-type cond decf declaim declare destructuring-bind do do* dolist dotimes ecase error etypecase eval-when flet format incf labels let* loop multiple-value-bind multiple-value-call multiple-value-list nth-value or pop proclaim prog1 prog2 psetq push remf setf the time typecase unless when with-input-from-string with-open-file with-output-to-string)
```
