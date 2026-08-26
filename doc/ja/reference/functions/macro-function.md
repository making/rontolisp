# macro-function

`(macro-function symbol &optional environment)`

`symbol` のマクロ展開器を返します。名前が関数、25 個の[特殊オペレータ](special-operator-p.md)のいずれか、または未知の場合は `nil` です。[`defmacro`](../special-forms/defmacro.md) で定義したユーザマクロ、すべての組み込みマクロ、そして rontolisp が独自に特殊形式として実装している Common Lisp のマクロ(`defun`、`handler-case`、`dolist` など)に対して非 nil を返します — 合わせて「`apply` してはいけない名前」のすべてです。

`environment` 引数は受け取って無視します。`macrolet` の本体は実行前に展開され尽くすため、参照できるのはグローバルな答えだけです。

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

`setf` の場所としては 1 つの形だけをサポートします。`(setf (macro-function 'new) (macro-function 'existing))` は既存の `defmacro` 定義マクロに 2 つ目の名前を与え、展開器を共有させます。以降、両方の名前は同一に展開されます。それ以外 — 任意の展開器関数や、ユーザマクロでない名前 — はエラーをシグナルします。保存すべきマクロ関数オブジェクトが存在しないためです。

```lisp
(defmacro greet2 (x) `(list :hello ,x))
(setf (macro-function 'hi) (macro-function 'greet2))
(hi "world") ; => (:HELLO "world")
```

ライト版: シンボル以外の引数は、Common Lisp が型エラーをシグナルするところで `nil` を返します。
