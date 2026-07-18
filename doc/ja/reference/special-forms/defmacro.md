# defmacro

`(defmacro name lambda-list body...)`

`name` という名前のユーザーマクロを定義し、名前シンボルを返します。マクロ呼び出しは引数フォームを**評価せずに**受け取ります。`body` は展開時にパラメータへ生のフォームを束縛して実行され、返したフォーム(展開形)が呼び出しの代わりに評価されます。ラムダリストはマクロラムダリストで、[`destructuring-bind`](../macros/destructuring-bind.md) と同様に引数フォームへ分配束縛されます: 必須位置ではパターンをネストでき、`&optional`(デフォルト値付き)、`&rest`/`&body`、`&key`、`&aux` をサポートします。`&whole` はサポートされません。`&environment` は受け付けられ、そのパラメータは nil に束縛されます(環境オブジェクトは存在しません)。これは `constantp`/`get-setf-expansion` へ渡すイディオムには十分です。このような拡張ラムダリストではマッチングは寛容です(足りない位置は nil に束縛され、余ったフォームは無視されます)。プレーンなラムダリスト(必須パラメータと末尾の `&rest`/`&body` 1 つ)では厳密な引数個数チェックが維持されます。標準オペレータ(`when`、`setf` など)は再定義できず、マクロは関数値を持ちません(`#'name` はエラーです)。

マクロ本体は通常、バッククォートのテンプレート構文で展開形を組み立てます。この構文はプログラム中のどこでも使えます。

- `` `form `` はカンマで unquote された箇所を除き `form` をクォートします
- `,expr` は `expr` の値を挿入します
- `,@expr` は `expr` の値(リスト)を周囲のリストに継ぎ足し(splice)ます

ネストしたバッククォート(バッククォートテンプレートの中の別のバッククォート)はサポートされ、読み取り時に完全に展開されるため、`once-only` のような古典的なマクロ書きマクロも動作します。マクロ本体で変数捕捉のない一時変数を生成するには [`gensym`](../functions/gensym.md) を、展開結果を調べるには [`macroexpand-1`](../functions/macroexpand-1.md)/[`macroexpand`](../functions/macroexpand.md) を使用してください。

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

```lisp
(defmacro with-point ((x y) form &key (scale 1))
  `(destructuring-bind (,x ,y) ,form
     (list (* ,x ,scale) (* ,y ,scale))))
(with-point (px py) '(3 4) :scale 10) ; => (30 40)
```
