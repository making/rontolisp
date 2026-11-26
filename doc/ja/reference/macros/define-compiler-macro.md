# define-compiler-macro

`(define-compiler-macro name lambda-list body...)`

`name` に対するコンパイラマクロを定義します。以降のその関数の呼び出しは、評価前の引数フォームに対して `body` を実行した結果へ書き換えられます (4 バックエンドすべて)。定義自体はコードを生成せず消費されます。マクロが書き換えを辞退した呼び出しと、コンパイラマクロを参照しない `apply`/`funcall` のために、通常の関数定義はそのまま残ります。

`&whole` パラメータをそのまま返すのが辞退の標準的な方法です。同名の `defmacro` はコンパイラマクロより優先されます (Common Lisp と同じ)。

コンパイラマクロはヒントであり、Common Lisp は処理系が無視することを認めています。rontolisp は次の 3 つの場合にこれを利用し、いずれも黙って無視します: 本体がシグナルした場合 (呼び出しはそのまま)、`name` が標準の演算子である場合 (登録しません — 共通の展開器がコンパイラマクロより先に低位化するため)、ラムダリストがマクロ機構で束縛できない形の場合。

制限: `notinline` は未実装のため、`notinline` 宣言された呼び出しも書き換えられます。書き換えは 1 つの呼び出し箇所につき最大 1 回です。展開時に本体が出力した内容は抑止されるため、バックエンド間で出力は同一に保たれます。

```lisp
(defun myinc (x) (+ x 1))
(define-compiler-macro myinc (x) `(+ ,x 100))
(myinc 10) ; => 110
```

```lisp
(defun mydec (x) (- x 1))
(define-compiler-macro mydec (&whole form x) (declare (ignore x)) form) ; 辞退する
(mydec 10) ; => 9
```
