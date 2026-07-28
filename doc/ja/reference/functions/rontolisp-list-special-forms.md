# rontolisp:list-special-forms

`(rontolisp:list-special-forms &optional package)`

パッケージの特殊形式シンボル (特別に評価され関数値を持たない演算子) をアルファベット順に
ソートして返します。省略可能なパッケージ指定子のデフォルトは `:cl` で、キーワード、
裸のシンボル、クォートされたシンボル、または文字列を指定できます。存在しないパッケージは
エラーです。詳しくは [パッケージのイントロスペクション](../packages.md#package-introspection)
を参照してください。

```lisp
(rontolisp:list-special-forms) ; => (CATCH DEFCLASS DEFCONSTANT DEFGENERIC DEFMACRO DEFMETHOD DEFPACKAGE DEFPARAMETER DEFSTRUCT DEFUN DEFVAR FUNCTION GO IF IN-PACKAGE LAMBDA LET PROGN PROGV QUOTE RETURN SETQ TAGBODY THROW UNWIND-PROTECT WHILE)
```
