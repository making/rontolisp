# プロトコル、レコード、型

プロトコルはメソッド表にメソッドごとのディスパッチャを添えたもの、レコードは
すべてのマップが使うのと同じ `equal` 表の上に型タグを載せたマップです。全体が
4バックエンドどれでも同じ形で動き、バックエンドごとの値表現はありません。

| 名前 | 例 | 結果 |
|---|---|---|
| `defprotocol` | `(do (defprotocol P13 (m [x])) (satisfies? P13 nil))` | `false` |
| `defrecord` | `(do (defrecord R13 [a]) (get (->R13 1) :a))` | `1` |
| `deftype` | `(do (deftype T13 [a]) (instance? T13 (T13. 2)))` | `true` |
| `set!` | `(do (defprotocol Bump (b [x])) (deftype Cell [^:unsynchronized-mutable n] Bump (b [_] (set! n (inc n)))) (b (Cell. 1)))` | `2` |
| `reify` | `(do (defprotocol Q13 (m [x])) (m (reify Q13 (m [_] 7))))` | `7` |
| `extend-protocol` | `(do (defprotocol E13 (m [x])) (extend-protocol E13 String (m [s] :s)) (m "x"))` | `:s` |
| `extend-type` | `(do (defprotocol Y13 (m [x])) (extend-type String Y13 (m [s] :s)) (m "x"))` | `:s` |
| `extend` | `(do (defprotocol X13 (m [x])) (extend String X13 {:m (fn [s] :f)}) (m "x"))` | `:f` |
| `satisfies?` | `(do (defprotocol S13 (m [x])) (satisfies? S13 1))` | `false` |
