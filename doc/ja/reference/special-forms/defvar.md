# defvar

`(defvar name [value])`

グローバル変数 `name` を定義し、`name` がまだ束縛されていない場合に限り `value` に束縛します。すでに値を持っている場合、`defvar` はそれを変更しません(冪等です)。`value` を省略した場合、変数は宣言されますが未束縛のままになります。`value` は実際に束縛が確立されるときにのみ評価され、名前シンボルが返されます。

`defvar` は `name` を **スペシャル** としても宣言します。以降の [`let`](let.md)/`let*` によるその名前の束縛は、レキシカルではなくダイナミック束縛(そのエクステント内で呼ばれた関数からも見え、脱出時に復元される)になります。[`let`](let.md) と [`progv`](progv.md) を参照してください。

```lisp
(defvar *counter* 0) ; => *counter*
```

```lisp
(defvar *scale* 1)
(defun scaled (n) (* n *scale*))
(let ((*scale* 10)) (scaled 5)) ; => 50
```
