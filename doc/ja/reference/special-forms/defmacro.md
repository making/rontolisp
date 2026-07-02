# defmacro

`(defmacro name (params... [&rest|&body rest]) body...)`

`name` という名前のユーザーマクロを定義し、名前シンボルを返します。マクロ呼び出しは引数フォームを**評価せずに**受け取ります。`body` は展開時にパラメータへ生のフォームを束縛して実行され、返したフォーム(展開形)が呼び出しの代わりに評価されます。ラムダリストは必須パラメータと、残りのフォームをリストとして集める末尾の `&rest`/`&body` パラメータ 1 つをサポートします。`&optional`/`&key` はサポートされません。標準オペレータ(`when`、`setf` など)は再定義できず、マクロは関数値を持ちません(`#'name` はエラーです)。

マクロ本体は通常、バッククォートのテンプレート構文で展開形を組み立てます。この構文はプログラム中のどこでも使えます。

- `` `form `` はカンマで unquote された箇所を除き `form` をクォートします
- `,expr` は `expr` の値を挿入します
- `,@expr` は `expr` の値(リスト)を周囲のリストに継ぎ足し(splice)ます

ネストしたバッククォートはサポートされません。マクロ本体で変数捕捉のない一時変数を生成するには [`gensym`](../functions/gensym.md) を、展開結果を調べるには [`macroexpand-1`](../functions/macroexpand-1.md)/[`macroexpand`](../functions/macroexpand.md) を使用してください。

インタープリタはマクロ呼び出しを評価時に展開します(そのため `defmacro` は REPL や `load`/`eval` 経由でも動作します)。コンパイルパスでは、CLI が JVM/WASM コンパイラの実行**前に**すべてのマクロ呼び出しを完全展開して定義を取り除くため、コンパイル出力には通常のフォームだけが含まれます。したがってコンパイル済みプログラムの実行時 `eval`/`read` は `defmacro` やバッククォート文字を認識せず、マクロは最初の使用より前に定義されている必要があります。

```lisp
(defmacro my-unless (test &body body)
  `(if ,test nil (progn ,@body)))
(my-unless (> 1 3) 'a 'b) ; => b
```

```lisp
(defmacro swap! (a b)
  `(let ((__tmp ,a))
     (setq ,a ,b)
     (setq ,b __tmp)))
(setq x 1)
(setq y 2)
(swap! x y)
(list x y) ; => (2 1)
```
