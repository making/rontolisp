# push-thread-bindings

`(push-thread-bindings binding-map)` ... `(pop-thread-bindings)`

オラクルの `binding` が展開される形で、ライブラリが手で書く形でもあります（hiccup の
`binding*`、honeysql の `with-inline`）。同じ本体の中で `push-thread-bindings` の直後に、
`finally` で `(pop-thread-bindings)` を呼ぶ `try` が続く形です。この組は `try` に対する
[with-bindings](with-bindings.md) と同じく束縛します。catch 節は束縛の中で走り、`finally` の
フォームのうち pop より前のものは束縛の中、後のものは外で走ります。ここでの束縛は動的
エクステントで、push したままのフレームは残らないため、どちらの関数もそれ以外の使い方は
プログラムの読み込み時に拒否されます。

```clojure
(def ^:dynamic *mode* :html)
(defn render [] (str "mode " *mode*))
(defn render-as [mode]
  (push-thread-bindings {#'*mode* mode})
  (try (render)
       (finally (pop-thread-bindings))))
(println (render-as :xml)) ; mode :xml
(println (render)) ; mode :html
```
