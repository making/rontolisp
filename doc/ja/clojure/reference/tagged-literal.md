# tagged-literal

`(tagged-literal tag form)`

`clojure.core/tagged-literal`: シンボル `tag` と `form` を持つタグ付きリテラルを返します。`{:read-cond :preserve}` の下でリーダ条件の中にあるタグはこの値として読まれます（レコードリテラル・`#inst`・`#uuid` も同じです。リーダ条件の外では `#inst` と `#uuid` はその[値](instants.md)に読まれ、それ以外のタグは `No reader function` のままです）。マップと同じく `:tag` と `:form` を引けます（それ以外のキーは既定値を返します）。同じタグと `=` なフォームを持つものと `=` になり、`#tag form` と印字されます。コレクションでも関数でもありません。`tag` はシンボルか `nil` でなければなりません（それ以外は `ClassCastException`）。[tagged-literal?](tagged-literal-p.md) で判定できます。すべてのバックエンドで動きます。値としては2引数を取ります。

```clojure
(def rc (read-string {:read-cond :preserve} "#?(:cljs #js {:a 1} :clj 2)"))
(def t (second (:form rc)))
(println t (:tag t) (:form t))
(println (= t (tagged-literal 'js {:a 1})))
```

```
#js {:a 1} js {:a 1}
true
```
