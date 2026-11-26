# objc:define-objc-typedef

`(objc:define-objc-typedef (name [(:c-type type)]) [type])`

`name` を FLI 型の別名として定義します。定義マクロと `objc:invoke` のリスト形式が型を受け取るところで使えます。`:c-type` を書くとそれが型になり、`type` は無視されます。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。定義にランタイムは要りません。Objective-C 側はランタイムを開いたとき (`objc:ensure-objc-initialized` か最初の送信) に作られます。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-typedef (count-type) (:unsigned :int))
COUNT-TYPE
MY-APP> (define-objc-method ("count:" count-type) ((self my-object) (items objc-object-pointer (array string)))
          (length items))
"count:"
MY-APP> (invoke (alloc-init-object "MyObject") "count:" #("a" "b" "c"))
3
```
