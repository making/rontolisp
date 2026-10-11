# ref

`(ref v)` / `(ref v :validator validate-fn)`

ref は `atom` と同じタグ付きセルで、`deref`/`@` で読みます。書き込めるのはトランザクション動詞（`alter`/`commute`/`ref-set`）だけで、`dosync` の内側に限ります。`:validator` は初期値と、ref を書き込んだトランザクションの終わりの最終値を受け入れる必要があり、拒否（`Invalid reference state`）はトランザクション全体を取り消します。`:meta` は他のメタデータ同様に捨てられます。それ以外のオプションは名前で拒否されます。

```clojure
(def r (ref 0 :validator (fn [n] (>= n 0))))
(dosync (alter r inc))
(println @r) ; 1
```
