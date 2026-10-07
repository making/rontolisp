# defvar

`(defvar name [value])`

グローバル変数 `name` を定義し、`name` がまだ束縛されていない場合に限り `value` に束縛します。すでに値を持っている場合、`defvar` はそれを変更しません(冪等です)。`value` を省略した場合、変数は宣言されますが未束縛のままになり、代入か束縛で値を与えるまでは、その参照はその名前を持つ `unbound-variable` を通知します(値の有無は先に [`boundp`](../functions/boundp.md) で確認できます)。`value` は実際に束縛が確立されるときにのみ評価され、名前シンボルが返されます。

`defvar` は `name` を **スペシャル** としても宣言します。以降の [`let`](let.md)/`let*` によるその名前の束縛(およびその名前の関数パラメータ。[`defun`](defun.md#special-parameters) を参照)は、レキシカルではなくダイナミック束縛(そのエクステント内で呼ばれた関数からも見え、脱出時に復元される)になります。[`let`](let.md) と [`progv`](progv.md) を参照してください。

```lisp
(defvar *counter* 0) ; => *COUNTER*
```

```lisp
(defvar *scale* 1)
(defun scaled (n) (* n *scale*))
(let ((*scale* 10)) (scaled 5)) ; => 50
```

```lisp
(defvar *request*)
(handler-case *request* (unbound-variable () :unset)) ; => :UNSET
```
