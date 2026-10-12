# with-bindings

`(with-bindings binding-map body...)`
`(with-bindings* binding-map f args...)`

マップの各 var（キーは `#'*x*` のような var オブジェクト）をその値に束縛し、本体
（`with-bindings`）または引数への `f` の呼び出し（`with-bindings*`）を動的エクステントで実行します。
[binding](binding.md) と同じスレッド単位の束縛で、束縛深さのカウンタも含むため、`set!` と
`thread-bound?` から見えます。束縛できるのは `^:dynamic` な var と `clojure.core` のスペシャル
だけで、それ以外の var はオラクルと同じ `IllegalStateException`
（`Can't dynamically bind non-dynamic var: user/x`）です。var オブジェクトが自身の束縛方法を
持つため、マップは計算した値でも構いません。`with-bindings`・`with-bindings*`・
`push-thread-bindings` のいずれかを含むプログラムでは、すべての動的 var の位置がこれを持ちます。
`with-bindings*` は関数値としても使えます。[push-thread-bindings](push-thread-bindings.md) も
参照してください。

```clojure
(def ^:dynamic *indent* 0)
(defn show [] (str "indent " *indent*))
(println (with-bindings {#'*indent* 2} (show))) ; indent 2
(println (with-bindings* {#'*indent* 4} show)) ; indent 4
(println (show)) ; indent 0
```
