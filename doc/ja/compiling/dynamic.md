# 動的バインディング（遅延束縛）

デフォルトでは、JVM コンパイラと WASM コンパイラはすべての呼び出しおよび変数参照を静的に解決し、コンパイル時に見つからないものはすべて拒否します（`Cannot compile: cube`）。これによりタイプミスは検出できますが、一方で `load` によって後から定義される関数を呼び出すソースは、コンパイルするために呼び出しを `eval`（`(eval '(cube 3))`）でラップする必要があることも意味します。

`--dynamic` フラグはこれを緩和します。静的に解決できない呼び出しや参照は、失敗する代わりにランタイムの `eval` 環境へ委ねられます（遅延束縛）。これにより、インタプリタでテストしたプログラムを `(cube 3)` を `(eval '(cube 3))` に書き換えることなく、そのままコンパイルできます（通常はより高速に実行するため）。

```bash
echo '(defun cube (n) (* n n n))' > lib.lisp
echo '(load "lib.lisp") (print (cube 3))' > prog.lisp
rontolisp prog.lisp -o Prog.class --dynamic   # compiles; (cube 3) resolves at runtime
rontolisp prog.lisp -o prog.wasm  --dynamic
```

呼び出し `(f a b)` は `_apply(_eval('(function f), null), (list a b))` にコンパイルされます。演算子はランタイムの関数名前空間に対して解決される一方、引数は通常どおりコンパイルされるため、コンパイル対象の外側の関数のローカル変数は引き続き参照可能です（例えば `(defun caller (n) (cube n))` は動作します）。裸の参照 `x` は `_eval('x, null)` にコンパイルされ、これは変数名前空間のみを解決します。このフォールバックは組み込みの `eval` ランタイムを使用するため、`--dynamic` は常にそれを出力します（プログラムが `eval` を使用したかのように）。そしてランタイムで一度も定義されない未知のシンボルは、コンパイル時ではなくそこに到達した時点でエラーになります。この方法で解決された関数はランタイムの `eval` インタプリタ上で実行されるため、上記の [コンパイルされた `eval` の制限](../guides/eval-limitations.md) の対象となります。
