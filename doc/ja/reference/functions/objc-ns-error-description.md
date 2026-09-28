# objc:ns-error-description

`(objc:ns-error-description condition)`

`objc:ns-error` が持つ `NSError` の `localizedDescription` を、ユーザーの言語の文字列で返します。 macOS 専用の `objc` パッケージのコンディションのリーダーです。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defvar *e* (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                                     "attributesOfItemAtPath:error:" "/no/such/file")
                      (ns-error (e) e)))
*E*
MY-APP> (ns-error-description *e*)
"The file “file” couldn’t be opened because there is no such file."
```
