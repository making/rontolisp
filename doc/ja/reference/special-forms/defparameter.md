# defparameter

`(defparameter name value)`

グローバル変数 `name` を定義して `value` に束縛します。`value` を評価し、`name` がすでに束縛されている場合でも **常に** (再)代入します。これは未束縛の名前のみを束縛する `defvar` とは異なります。名前シンボルを返します。

```lisp
(defparameter *limit* 100) ; => *limit*
```

```lisp
(defparameter *limit* 100)
*limit* ; => 100
```
