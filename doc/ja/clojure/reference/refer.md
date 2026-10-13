# refer

`(refer ns-sym & filters)`

名前空間 `ns-sym` を読んで今の名前空間へ refer する。そのままでは公開 var 全部。`:only`、`:exclude`、`:rename` で絞る。`nil` を返す。oracle と同じ。求まる形でないものは実行時に拒否される。値としては何引数でもよく（拒否される）。

```clojure
(refer 'clojure.string :only '[upper-case])
(println (upper-case "ab")) ; AB
```
