# macro-function

`(macro-function symbol &optional environment)`

`symbol` のマクロ展開器を返します。名前が関数、25 個の[特殊オペレータ](special-operator-p.md)のいずれか、または未知の場合は `nil` です。[`defmacro`](../special-forms/defmacro.md) で定義したユーザマクロ、すべての組み込みマクロ、そして rontolisp が独自に特殊形式として実装している Common Lisp のマクロ(`defun`、`handler-case`、`dolist` など)に対して非 nil を返します — 合わせて「`apply` してはいけない名前」のすべてです。rontolisp がインライン展開しているだけの Common Lisp の**関数**(`typep`、`format`、`error`、`make-instance` など)と `declare` には `nil` を返します。

`environment` 引数は受け取りますが、名前の検索では無視します。`macrolet` の本体は実行前に展開され尽くすため、参照できるのはグローバルな答えだけです。

```lisp
(defmacro greet (x) `(list :hello ,x))
(list (and (macro-function 'greet) t) (and (macro-function 'when) t)
      (macro-function 'car) (macro-function 'if)) ; => (T T NIL NIL)
```

インタープリタでは値は本物の展開器で、`(funcall expander form environment)` として 1 段階の展開を実行できます。

```lisp
(funcall (macro-function 'when) '(when t 1) nil) ; => (IF T 1 NIL)
```

**コンパイル済み**プログラムにはマクロテーブルが残っていない(バックエンドが見る前にマクロは完全展開される)ため、そこでの値はスタブです。上記の述語としての用途は 4 バックエンドすべてで正確ですが、呼び出すと `macro-function: a compiled program cannot expand a macro at run time` をシグナルします。

`(setf (macro-function 'name) function)` は `function` を `name` の展開器にします。呼び出しは `(funcall function form environment)` の答えに展開されます。`form` は呼び出し全体、`environment` は `&environment` パラメータが受け取るものと同じ値です。以降 `macro-function` はその関数そのものを答えます。関数でない値やシンボルでない名前は `type-error` をシグナルします。

```lisp
(setf (macro-function 'twice)
      (lambda (form env) (declare (ignore env)) `(* 2 ,(second form))))
(twice 21) ; => 42
```

コンパイル済みプログラムにはマクロテーブルがないため、トップレベル、`progn` や `eval-when` のメンバー(マクロが生成したものでも可)、またはそれを囲む `let` や `flet` の本体では、書き込みは `defmacro` と同じくコンパイル時に行われ、プログラムには何も残りません。値のフォームはコンパイル時に実行され、その時点で定義済みのものを参照できます。値のフォームが失敗すると、そのフォームを示してコンパイルが止まります。

関数本体の中など、それ以外の場所の書き込みはプログラムの実行時に行われます。上記の拒否に加えて、`cl` の名前も拒否します。コンパイル済みプログラムの呼び出しは `compile-file` と同じくコンパイル時に展開済みなので、呼び出しは変わりません。以降 `macro-function` はその関数を答え、`macroexpand` と `macroexpand-1` はそれを実行します。そのような書き込みだけが定義した名前の呼び出しは未定義関数です。インタプリタでは呼び出しが展開されます。Lite: コンパイル済みプログラムでは、そのような名前をリテラルで書いた `fboundp` は真を答えず(実行時に計算した名前なら答えます)、`fmakunbound` は `macro-function` からマクロを取り除きません。

`(setf (macro-function 'new) (macro-function 'existing))` は既存のマクロに 2 つ目の名前を与え、以降、両方の名前は同一に展開されます。`defmacro` 定義マクロは展開器を共有し、`incf` などの組み込みマクロは呼び出しの書き換えで到達します。新しい名前はキーワードでも構いません（`(setf (macro-function :+) (macro-function 'incf))`）。マクロでない名前はエラーをシグナルします。

```lisp
(defmacro greet2 (x) `(list :hello ,x))
(setf (macro-function 'hi) (macro-function 'greet2))
(hi "world") ; => (:HELLO "world")
```

ライト版: シンボル以外の引数は、Common Lisp が型エラーをシグナルするところで `nil` を返します。
