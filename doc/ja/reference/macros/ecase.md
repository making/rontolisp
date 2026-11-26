# ecase

`(ecase key (k1 body...) ((k2 k3) body...))`

`case` の網羅的な変種です。`case` とまったく同じく `eql` を使って `key` でディスパッチしますが、デフォルト節を持ちません。`t` と `otherwise` は通常のキーとして扱われます。`key` がどの節にも一致しない場合、`ecase` は nil を返すのではなく `error` をシグナルするため、有効なすべての値を明示的に網羅すべき場合に使われます。

```lisp
(let ((x 3)) (ecase x (1 'one) ((2 3) 'two-or-three))) ; => TWO-OR-THREE
```
