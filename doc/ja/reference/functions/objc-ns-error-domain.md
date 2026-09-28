# objc:ns-error-domain

`(objc:ns-error-domain condition)`

`objc:ns-error` が持つ `NSError` のドメインを文字列で返します (`"NSCocoaErrorDomain"`)。`objc:ns-error` は `objc:invoke-with-error` がシグナルします。 macOS 専用の `objc` パッケージのコンディションのリーダーです。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defvar *e* (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                                     "attributesOfItemAtPath:error:" "/no/such/file")
                      (ns-error (e) e)))
*E*
MY-APP> (ns-error-domain *e*)
"NSCocoaErrorDomain"
```
