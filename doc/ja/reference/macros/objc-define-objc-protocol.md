# objc:define-objc-protocol

`(objc:define-objc-protocol name &key incorporated-protocols instance-methods class-methods)`

プロトコル `name` (文字列) を、`(name result-type arg-type*)` の形で並べたメソッドとともに宣言します。宣言するだけで作りはしません。マニュアルのとおりプロトコルは Lisp では定義せず、`objc:define-objc-class` の `(:objc-protocols "Name")` はランタイムに既にあるものを採用します (ないときは警告します)。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。定義にランタイムは要りません。Objective-C 側はランタイムを開いたとき (`objc:ensure-objc-initialized` か最初の送信) に作られます。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-protocol "NSCopying"
          :instance-methods (("copyWithZone:" objc-object-pointer (:pointer :void))))
"NSCopying"
MY-APP> (define-objc-class copyable () () (:objc-class-name "Copyable") (:objc-protocols "NSCopying"))
COPYABLE
```
