# rontolisp:list-macros

`(rontolisp:list-macros &optional package)`

パッケージのマクロシンボル (関数値を持たない演算子) をアルファベット順にソートして
返します。省略可能なパッケージ指定子のデフォルトは `:cl` で、キーワード、裸のシンボル、
クォートされたシンボル、または文字列を指定できます。存在しないパッケージはエラーです。
詳しくは [パッケージのイントロスペクション](../packages.md#package-introspection)
を参照してください。

```lisp
(rontolisp:list-macros) ; => (and assert case ccase cerror check-type complement complex cond decf declaim declare define-compiler-macro define-condition define-modify-macro define-setf-expander deftype destructuring-bind do do* documentation dolist dotimes ecase error etypecase eval-when flet format handler-case ignore-errors incf labels let* load-time-value locally loop macrolet make-condition make-instance make-sequence multiple-value-bind multiple-value-call multiple-value-list multiple-value-setq nth-value or pop proclaim prog prog* prog1 prog2 psetq push pushnew remf restart-case return-from rotatef setf shiftf signal slot-boundp slot-makunbound slot-value the time typecase typep unless warn when with-input-from-string with-open-file with-output-to-string with-slots write-char)
```
