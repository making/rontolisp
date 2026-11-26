# push-thread-bindings

`(push-thread-bindings binding-map)` ... `(pop-thread-bindings)`

The shape the oracle's `binding` expands to, and libraries write by hand (hiccup's
`binding*`, honeysql's `with-inline`): a `push-thread-bindings` immediately followed, in
the same body, by a `try` whose `finally` calls `(pop-thread-bindings)`. The pair binds
like [with-bindings](with-bindings.md) over the `try`: its catches run under the
bindings, the `finally` forms ahead of the pop inside them and the ones after it outside.
A binding here is a dynamic extent, never a frame left pushed, so any other use of
either verb is refused when the program is read.

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
