# guard

`(guard (variable clause...) body...)`

`body` を評価し、最後の式の値を返します。本体が `raise`、`raise-continuable`、`error` で、あるいは組み込み手続きのエラーとしてオブジェクトを発生させると、本体を抜け（その中の `dynamic-wind` の `after` が実行されます）、`variable` をそのオブジェクトに束縛して `clause` を `cond` と同じように試します（`=>` と `else` も使えます）。どの節も選ばれなければ、`guard` からそのオブジェクトを再び発生させます。

仕様との差異:

- 再発生は本体を抜けた後に行われるため、`guard` の外側の `with-exception-handler` は本体の `raise-continuable` を再開できません。ハンドラが戻ると二次エラーになります。Gauche は本体を再開します。
- 複数の値を返す本体は、最初の値だけを返します。
- WebAssembly では範囲外の文字列添字は検査されず、終端を超えた `string-ref` はエラーを発生させずに文字を返します。`error`、`raise`、算術の型エラー、ペアでないものの `car`、範囲外のベクタ添字はどのバックエンドでも捕捉されます。
- `eval` の中の `guard` は名前を挙げて拒否されます。

```scheme
(guard (e ((symbol? e) (list 'caught e))) (raise 'oops)) ; => (caught oops)
(guard (e ((assq 'a e) => cdr) ((assq 'b e))) (raise (list (cons 'a 42)))) ; => 42
(+ 1 (guard (e ((number? e) e)) (raise 5))) ; => 6
(guard (e ((error-object? e) 'caught)) (+ 1 (car '(a)))) ; => caught
```
