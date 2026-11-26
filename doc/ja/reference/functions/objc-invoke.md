# objc:invoke

`(objc:invoke class-or-object-pointer method &rest args)`

メッセージを送り、その値を返します。レシーバはオブジェクトポインタ、クラスポインタ、またはクラス名の文字列 (クラスメソッドの呼び出し) で、`nil` には `nil` を返します。`method` はコロン込みのセレクタ (`"setWidth:height:"`) か、型を自分で述べるリスト `(name arg-types &key result-type variadic-num-of-fixed)` です。可変長引数のメソッドには後者が必要です。引数と結果はメソッドの宣言型に従って変換されます。文字列とベクタは呼び出しの間だけ存在する `NSString` / `NSArray` として、`#(x y width height)` は `NSRect`、`(location . length)` は `NSRange`、`t` / `nil` は `BOOL` として、外部オブジェクト ([`fli`](fli.md)) はポインタとして、または内容をコピーして構造体として渡ります。結果の `BOOL` は `1` か `0`、`NSRect` はベクタ、`NSRange` はコンス、オブジェクトは `objc:objc-object-pointer`、ポインタは外部ポインタになります。レシーバにないメソッドは、何も送らずに `No method ... for object ...` をシグナルします。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *s* (objc:invoke "NSString" "stringWithUTF8String:" "hello world"))
*S*
CL-USER> (objc:invoke *s* "length")
11
CL-USER> (objc:invoke *s* "rangeOfString:" "world")
(6 . 5)
CL-USER> (objc:invoke (objc:invoke "NSScrollView" "alloc") "initWithFrame:" #(0 0 100 100))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600003B04000>
```
