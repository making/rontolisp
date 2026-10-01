# with-out-str

`(with-out-str body...)`

本体を、`*standard-output*` を新しい文字列ストリームに束縛して実行し、印字された内容を答えにします。リテラルの `with-output-to-string` は使いません（WASM モジュールが EH モードに切り替わるため）。`str` 同様、ストリームを直接作って読み戻します。`*out*` は `*standard-output*` そのものなので、`(. *out* write ...)` も同じ捕捉に届きます。

```clojure
(println (with-out-str (print 1) (pr :a))) ; 1:a
```
