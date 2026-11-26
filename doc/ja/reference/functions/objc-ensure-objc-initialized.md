# objc:ensure-objc-initialized

`(objc:ensure-objc-initialized &key modules)`

`modules` の各要素 (フレームワークのバイナリまたは dylib のパス) をロードし、Objective-C ランタイムを開いて `nil` を返します。ほかの関数は最初の使用時にランタイムを開くため、LispWorks と違って最初に呼ぶ必要はありません。この関数が加えるのは、AppKit が読み込まないフレームワークのロードです。そうしたフレームワークのクラスは、ロードするまで存在しません。ロードできないパスは、そのパスを名指しする `error` をシグナルします。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:ensure-objc-initialized
           :modules '("/System/Library/Frameworks/Vision.framework/Vision"))
NIL
CL-USER> (objc:coerce-to-objc-class "VNRecognizeTextRequest")
#<Pointer: OBJC:OBJC-CLASS = #x00000001FB3C2A10>
```
