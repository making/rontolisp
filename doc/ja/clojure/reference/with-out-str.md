# with-out-str

`(with-out-str body...)`

本体を、`*standard-output*` を新しい文字列ストリームに束縛して実行し、印字された内容を答えにします。リテラルの `with-output-to-string` は使いません（WASM モジュールが EH モードに切り替わるため）。`str` 同様、ストリームを直接作って読み戻します。`*out*` は `*standard-output*` そのものなので、`(. *out* write ...)` も同じ捕捉に届き、本体の中の `(prn *out*)` は捕捉のストリーム `#object[java.io.StringWriter ""]` を印字します。引数なしの `(new java.io.StringWriter)` はすべてのバックエンド（WASM を含む）で同じ文字列ストリームになるため、本体の実行中だけ `*out*` を束縛し直してライタを `str` で読み戻す、オラクル自身の `with-out-str` マクロがそのまま動作します。`str` / `.toString` は蓄積されたテキストをクリアせずに答えます。

```clojure
(println (with-out-str (print 1) (pr :a))) ; 1:a
```
