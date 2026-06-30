# rontolisp:list-macros

`(rontolisp:list-macros &optional package)`

パッケージのマクロシンボル (関数値を持たない演算子) をアルファベット順にソートして
返します。省略可能なパッケージ指定子のデフォルトは `:cl` で、キーワード、裸のシンボル、
クォートされたシンボル、または文字列を指定できます。存在しないパッケージはエラーです。
詳しくは [パッケージのイントロスペクション](../packages.md#package-introspection)
を参照してください。

```lisp
(rontolisp:list-macros) ; => (and case ccase cond decf do do* dolist dotimes ecase error etypecase format incf let* loop or pop prog1 prog2 psetq push remf setf time typecase unless when with-open-file)
```
