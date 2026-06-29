# defvar

`(defvar name [value])`

グローバル変数 `name` を定義し、`name` がまだ束縛されていない場合に限り `value` に束縛します。すでに値を持っている場合、`defvar` はそれを変更しません(冪等です)。`value` を省略した場合、変数は宣言されますが未束縛のままになります。`value` は実際に束縛が確立されるときにのみ評価され、名前シンボルが返されます。

```lisp
(defvar *counter* 0) ; => *counter*
```

```lisp
(defvar *counter* 0)
*counter* ; => 0
```
