# gensym

`(gensym [prefix])`

`#:<prefix><n>` という名前の新しいシンボルを返します。`prefix` のデフォルトは `G` で、`n` は 1 から始まるプログラム全体で共有されるカウンタです。rontolisp にはインターンされないシンボルはありません: 結果は通常のシンボルであり、その一意性は(ユーザーが書く名前には通常含まれない)`#:` プレフィックスと単調増加するカウンタによって保たれます。主な用途は [`defmacro`](../special-forms/defmacro.md) 本体の中で変数捕捉のない一時変数を生成することで、従来の `__` プレフィックスの命名規約を置き換えます。

Common Lisp からの相違点: コンパイルパス(JVM/WASM)では、シンボルのテキストをコンパイル時に確定させるため、プレフィックスは**リテラル**文字列でなければなりません — 計算されたプレフィックスはコンパイルエラーになります(インタプリタは任意の文字列を受け付けます)。`*gensym-counter*` 変数はなく、生成されたシンボルも他のシンボル同様にインターンされるため、同じ印字名を 2 回 `read` すると `eq` なシンボルになります。

```lisp
(list (gensym) (gensym)) ; => (#:G1 #:G2)
```

```lisp
(gensym "tmp") ; => #:|tmp3|
```

```lisp
(eq (gensym) (gensym)) ; => NIL
```

`gensym` で生成したマクロの一時変数は、呼び出し側の変数と衝突しません:

```lisp
(defmacro swap! (a b)
  (let ((tmp (gensym)))
    `(let ((,tmp ,a)) (setq ,a ,b) (setq ,b ,tmp))))
(setq tmp 1)
(setq other 2)
(swap! tmp other)
(list tmp other) ; => (2 1)
```
