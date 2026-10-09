# get-in

`(get-in m keys)` / `(get-in m keys dflt)`

キーパスを下る読み取りを返します。パスは任意の seqable です（キーが空なら `m` を返します）。
デフォルトは最初に欠けた階層で返るため、途中の欠けもデフォルトになり、
`(get-in {} [:a :b] {:b 1})` はオラクル同様 `{:b 1}` です。ベクターの階層は [get](get.md) と
同じく添字で読みます。

```clojure
(println (get-in {:a {:b 1}} [:a :b])) ; 1
(println (get-in {} [:a :b] :dflt)) ; :dflt
(println (get-in [[0 1] [2 3]] [1 0])) ; 2
```
