# ref

`(ref v)` / `(ref v :validator validate-fn)`

ref は `atom` と同じタグ付きセルで、`deref`/`@` で読みます。書き込めるのはトランザクション動詞（`alter`/`commute`/`ref-set`）だけで、`dosync` の内側に限ります。`:validator` があると、書き込みのたびにそれが走り、失敗した書き込みはシグナルを上げ（`Invalid reference state`）、古い値を残します。validator は走りますが、リトライはしません（スレッドが1つなので競合は起きません）。`:meta` は他のメタデータ同様に捨てられます。それ以外のオプションは名前で拒否されます。

```clojure
(def r (ref 0 :validator (fn [n] (>= n 0))))
(dosync (alter r inc))
(println @r) ; 1
```
