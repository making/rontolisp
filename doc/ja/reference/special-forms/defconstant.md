# defconstant

`(defconstant name value)`

`value` を評価し、それに束縛されたグローバルな `name` を定義し、名前シンボルを返します。動作は `defparameter` と同様で、rontolisp は定数性を強制しないため、束縛は後から変更することもできます。固定されることを意図した値を文書化するためのものです。

```lisp
(defconstant +pi+ 3.14159) ; => +pi+
```

```lisp
(defconstant +pi+ 3.14159)
+pi+ ; => 3.14159
```
