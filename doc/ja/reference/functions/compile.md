# compile

`(compile name &optional definition)`

リテラルの `(lambda ...)` 定義を、空のレキシカル環境で評価して関数に変換します。`definition` がすでに関数であれば、そのまま使います。`name` が nil なら関数そのものを返し、関数名を渡すとその名前のグローバル定義としてもインストールし、Common Lisp と同様に名前を返します。`definition` を省略すると、名前の現在の定義をそのまま残して名前を返します。マクロの名前も受け付け、定義のない名前は `undefined-function` を通知します。第 2、第 3 の戻り値 `warnings-p` と `failure-p` は常に `nil` です。rontolisp の `compile` はネイティブコードを生成しません — 定義時のコード構築イディオムをロード可能にするために存在します。特に、クラスメタオブジェクトに対してメソッドを定義する引数なしの定義(オブジェクトマッパーの `(funcall (compile nil `(lambda () ,code)))` イディオム。`code` はクラスから計算した値を閉じ込めます)は、定義時のメソッド構築として実行されます。

コンパイルされたプログラム(JVM / WASM 出力)では、このメソッド構築イディオムはコンパイル時に展開されてプログラムに継ぎ足され、同じ呼び出しの実行時再実行は no-op になります。プログラム自身のトップレベル定義が `compile` に渡す定義(ライブラリがクラスやメソッドを定義する途中でラムダ式から関数を作るもの。contextl のレイヤードアクセサなど)は事前にコンパイルされます。コンパイラはプログラムのコンパイル時にそれらの定義を実行し、そこで `compile` に渡された定義をそれぞれプログラム内のコンパイル済みコードにします。定義が保持するオブジェクト(総称関数、クォートされたリスト)は実行時の呼び出しから取ります。それ以外の実行時 `compile` 呼び出しは、定義をプログラムの [`eval`](eval.md) ランタイムで評価します — コンパイル済みプログラムはコンパイラを持たないためです([コンパイル済み `eval` の制限](../../guides/eval-limitations.md)を参照)。[`(setf fdefinition)`](fdefinition.md) と同じく、プログラム自身が `defun` で定義した名前の直接呼び出しは、`(compile 'name definition)` の後もその定義を呼び続けます。シンボル経由の呼び出しは新しい定義を呼びます。

```lisp
(list (funcall (compile nil '(lambda (x) (* x x))) 7)
      (compile 'my-inc '(lambda (x) (+ x 1)))
      (my-inc 41)
      (multiple-value-list (compile 'my-inc))) ; => (49 MY-INC 42 (MY-INC NIL NIL))
```
