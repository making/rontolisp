# reset-meta!

`(reset-meta! ref m)`

参照 `ref`（var、アトム、ref、エージェント、名前空間）のメタデータをマップ `m`（なしなら `nil`）に
置き換え、`m` を返します。拒否する値は `alter-meta!` と同じです。値としては2引数の関数です。

```clojure
(def a (atom 0))
(reset-meta! a {:owner "me"})
(println (meta a)) ; {:owner me}
```
