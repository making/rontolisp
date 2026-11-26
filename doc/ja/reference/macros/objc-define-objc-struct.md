# objc:define-objc-struct

`(objc:define-objc-struct (name (:foreign-name "Name") [(:typedef-name alias)]) (slot-name slot-type)*)`

構造体型 `name` を定義します。`objc:invoke` のリスト形式と定義マクロで `(:struct name)` (と `alias`) として使えます。`:foreign-name` はランタイムのエンコーディングが使う名前です。値はフィールドをメモリ順に並べたベクタで、`cocoa` の四つ以外の構造体を `objc:invoke` が受け渡す形と同じです。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。定義にランタイムは要りません。Objective-C 側はランタイムを開いたとき (`objc:ensure-objc-initialized` か最初の送信) に作られます。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-struct (pair (:foreign-name "_Pair"))
          (:first :float)
          (:second :float))
PAIR
MY-APP> (define-objc-method ("pair" (:struct pair)) ((this my-object))
          (vector 1.0 2.0))
"pair"
MY-APP> (invoke (alloc-init-object "MyObject") "pair")
#(1.0 2.0)
```
