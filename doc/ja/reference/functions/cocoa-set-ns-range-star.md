# cocoa:set-ns-range*

`(cocoa:set-ns-range* range location length)`

`NSRange` (コンス) を `(location . length)` に設定して返します。 `objc` と並ぶ macOS 専用の `cocoa` パッケージの一部です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:invoke-into 'string *s* "substringWithRange:" (cocoa:set-ns-range* (cons 0 0) 6 5))
"world"
```
