# macroexpand-1

`(macroexpand-1 form)`

`form` の演算子がユーザーマクロ([`defmacro`](../special-forms/defmacro.md) で定義)または組み込みマクロ(`rontolisp:list-macros` が報告する名前)の場合に 1 段階だけ展開し、それ以外の場合はフォームをそのまま返します。展開されるのはトップレベルの演算子だけで、サブフォームには手を付けません。

Common Lisp からの相違点: `macroexpand-1` は 2 番目の `expanded-p` 値を供給しません — 展開されたかどうかは結果と入力を比較して判定してください。環境引数もありません。コンパイルパスでは引数はリテラルのクォートされたフォームでなければなりません: CLI がコンパイル時に呼び出しを展開結果に畳み込みます(コンパイル済み出力の実行時にはマクロ表が存在しません)。計算された引数はコンパイルエラーになり、コンパイル済みプログラムの実行時 `eval` は `macroexpand-1` を認識しません。

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
