# objc:invoke-into

`(objc:invoke-into result class-or-object-pointer method &rest args)`

`result` の指示どおりに答えを変換または格納する `objc:invoke` です。シンボル `string` は結果の `NSString` を Lisp 文字列に、`array` は `NSArray` をポインタのベクタに変換し、`(array string)` (入れ子可) は要素も変換します。ベクタを渡すと `NSArray` の要素、または結果の `NSRect` / `NSSize` / `NSPoint` のフィールドで埋め、コンスには結果の `NSRange` の位置と長さを格納します。`:pointer` は C 文字列の結果をアドレスのまま返します。それ以外の組み合わせは `objc:invoke` と同じ値を返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:invoke-into 'string *s* "description")
"hello world"
CL-USER> (let ((rect (make-array 4)))
           (objc:invoke-into rect (objc:invoke "NSValue" "valueWithRect:" #(1 2 3 4)) "rectValue")
           rect)
#(1.0 2.0 3.0 4.0)
CL-USER> (objc:invoke-into '(array string) (objc:invoke "NSArray" "arrayWithArray:" #("a" "b")) "self")
#("a" "b")
```
