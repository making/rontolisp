# macroexpand-1

`(macroexpand-1 form)`

`form` の演算子がユーザーマクロ([`defmacro`](../special-forms/defmacro.md) で定義)または組み込みマクロの場合に 1 段階だけ展開し、それ以外の場合はフォームをそのまま返します。展開されるのはトップレベルの演算子だけで、サブフォームには手を付けません。

2 番目の値は Common Lisp の `expanded-p` フラグです: `(multiple-value-list (macroexpand-1 '(unless c x)))` は `((IF C NIL X) T)` になります。環境引数は受け取って無視します(参照すべきレキシカルなマクロ環境が存在しないため)。

コンパイルパスで展開されるのは**リテラル**のクォートされた引数だけです: CLI がコンパイル時に呼び出しを展開結果に畳み込みます。計算された引数はコンパイル済みプログラムに届きますが、そこにはマクロテーブルが残っていないため、フォームをそのまま返し `expanded-p` は nil になります — ただしそのフォーム自体がマクロ呼び出しの場合は `macroexpand-1: a compiled program cannot expand a macro at run time` をシグナルします。これは [`macro-function`](macro-function.md) のスタブがそこで返すのと同じ答えで、「展開されなくなるまで展開する」という定番のループがどのバックエンドでも停止するのはこのためです。

```lisp
(macroexpand-1 '(unless c x)) ; => (IF C NIL X)
```

```lisp
(defmacro my-when (test &body body)
  `(if ,test (progn ,@body) nil))
(macroexpand-1 '(my-when (> 2 1) 'a 'b)) ; => (IF (> 2 1) (PROGN (QUOTE A) (QUOTE B)) NIL)
```

マクロでないフォームはそのまま返されます:

```lisp
(macroexpand-1 '(+ 1 2)) ; => (+ 1 2)
```
