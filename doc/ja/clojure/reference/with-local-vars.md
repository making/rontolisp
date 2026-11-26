# with-local-vars

`(with-local-vars [name init ...] body...)`

各 name を init を保持する新しい var セルに束縛し、body の動的範囲で
スレッドローカルにします。`deref`、`var-get`、var 自体はセルを読み、
`var-set` は束縛中のみセルに書き込みます。範囲を出ると unbound な
root に戻ります（`unwind-protect` が全セルを復元するため、throw しても
unbound な root が残ります。オラクルの `try` がフレームを pop するのと
同じです）。セルは unbound で始まります。init が束縛するのは範囲内だけ
なので、範囲から逃げたセルは unbound な root を deref します。init は
全セル生成後に順に走るため、先のセルを unbound で読みます。body をまたぐ
`recur` はオラクルの `try` 境界同様拒否され、セルへの `set!` は
`Cannot assign to non-mutable` の拒否になります。body で作られた
[`bound-fn`](bound-fn.md) はセルを取り込みます。セルは `#'ns/name` と
印字されます（オラクルのセルは無名です）。セルへの `alter-var-root` は
拒否されます。値としては実行時の一回展開の委譲を答えます（[bound-fn](bound-fn.md) を
見てください）。オラクルはむき出しのマクロ名を値位置でコンパイル時に
拒否します。

```clojure
(println (with-local-vars [a 1 b 2] [@a @b])) ; [1 2]
(println (with-local-vars [a 1] (var-set a 5) @a)) ; 5
```
