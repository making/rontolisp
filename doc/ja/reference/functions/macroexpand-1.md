# macroexpand-1

`(macroexpand-1 form)`

`form` の演算子がユーザーマクロ([`defmacro`](../special-forms/defmacro.md) で定義)または組み込みマクロの場合に 1 段階だけ展開し、それ以外の場合はフォームをそのまま返します。展開されるのはトップレベルの演算子だけで、サブフォームには手を付けません。展開するのは [`macro-function`](macro-function.md) が非 nil を返す演算子と正確に同じです: `(return 1)` は `(RETURN-FROM NIL 1)` に展開され、関数呼び出しである `(typep x 'integer)` はそのまま返ります。`handler-case` と、rontolisp が特殊形式として実行する定義形式は内部フォームに展開されます。コードウォーカーがそれをどこに出力しても元のフォームとして実行されます: `(handler-case f (error (c) c))` は `(%HANDLER-CASE F 'ERROR #'(LAMBDA (C) C))`、`(defun f (x) x)` は `(%DEFUN 'F #'(LAMBDA (X) (BLOCK F X)))`、`defmacro`、`defvar`、`defparameter`、`defconstant`、`defclass`、`defgeneric`、`defmethod`、`defstruct`、`defsetf`、`defpackage`、`define-symbol-macro`、`in-package` は `(%DEFINITION 'form)` になります。

2 番目の値は Common Lisp の `expanded-p` フラグです: `(multiple-value-list (macroexpand-1 '(unless c x)))` は `((IF C NIL X) T)` になります。シンボルマクロを名指すシンボルは展開形に展開されます。束縛が保持するオブジェクトそのものなので、マクロがそこへ書き込み、後のマクロがそれを読むことができます。環境引数は nil か、マクロの `&environment` パラメータが保持する値です。後者はそのマクロの呼び出し位置でスコープにある [`symbol-macrolet`](../macros/symbol-macrolet.md) の名前を参照し、前者は [`define-symbol-macro`](../special-forms/define-symbol-macro.md) の名前だけを参照します。`macrolet` のマクロはどちらでも見つかります。

コンパイルパスで展開されるのは**リテラル**のクォートされた引数だけです: CLI がコンパイル時に呼び出しを展開結果に畳み込みます。計算された引数はコンパイル済みプログラムに届きますが、そこにはマクロテーブルが残っていないため、フォームをそのまま返し `expanded-p` は nil になります — ただしそのフォーム自体がマクロ呼び出しの場合は `macroexpand-1: a compiled program cannot expand a macro at run time` をシグナルします。これは [`macro-function`](macro-function.md) のスタブがそこで返すのと同じ答えで、「展開されなくなるまで展開する」という定番のループがどのバックエンドでも停止するのはこのためです。

組み込みの反復マクロは、コードウォーカーが読める標準の演算子に展開します。`dolist`、`dotimes`、`do`、`do*`、`loop`、`prog` は `block` と `tagbody` を返します。`*macroexpand-hook*` は渡された展開関数を呼び出します。これを通して呼ぶ展開関数のためのもので、ここでの展開はこの変数を参照しません。

```lisp
(macroexpand-1 '(unless c x)) ; => (IF C NIL X)
```

```lisp
(defmacro my-when (test &body body)
  `(if ,test (progn ,@body) nil))
(macroexpand-1 '(my-when (> 2 1) 'a 'b)) ; => (IF (> 2 1) (PROGN 'A 'B) NIL)
```

マクロでないフォームはそのまま返されます:

```lisp
(macroexpand-1 '(+ 1 2)) ; => (+ 1 2)
```
