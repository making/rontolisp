# catch

`(catch tag body...)`

`tag` で名前を付けた**動的な**脱出点を確立し、最後の `body` フォームの値を返します。body の動的エクステント内のどこかで `eq` なタグへの [`throw`](throw.md) が発生した場合は、投げられた値を返します。タグは入口で一度だけ評価される通常のランタイム値(通常はクォートされたシンボル)なので、[`block`](../macros/block.md)/`return-from` と違い、投げる側が捕まえる側をレキシカルスコープに持つ必要はありません。`catch` がアクティブな間に実行されさえすればよいのです。タグが一致するもっとも内側のアクティブな `catch` が勝ち、一致しないものは脱出をそのまま通過させます。

`catch`/`throw` は `--no-gc`(コンパイルエラー)を除く**すべてのバックエンド**で動作します。wasm-GC バックエンド(Preview 1 と `--component`)では WebAssembly の exception-handling プロポーザルを通じてコンパイルされるため、これを使うプログラムの実行には [`unwind-protect`](unwind-protect.md) や `handler-case` と同様に wasmtime 37+ で `-W exceptions=y` が必要です。

`throw` はコンディションではなく非局所脱出です。`handler-case` に捕捉されずにその**中を通り抜け**ますが、途中の `unwind-protect` の cleanup はすべて実行されます。

```lisp
(catch 'done (throw 'done :thrown) :not-reached) ; => :THROWN
```

脱出は関数境界を越えます。これがコールバックから抜け出す用途で有用な理由です:

```lisp
(defun first-even (xs)
  (catch 'found
    (map nil (lambda (x) (if (evenp x) (throw 'found x))) xs)
    :none))
(list (first-even '(1 3 4 5)) (first-even '(1 3 5))) ; => (4 :NONE)
```

タグは `eq` で比較されるため、新たにコンスしたタグは自分自身にのみ一致します:

```lisp
(catch 'outer (catch (list 1) (throw 'outer :to-outer))) ; => :TO-OUTER
```
