# objc:ns-error-object

`(objc:ns-error-object condition)`

`objc:ns-error` が持つ `NSError` を返します。参照を 1 つ保持する `objc:objc-object-pointer` です (ポインタが回収されると解放されます)。 macOS 専用の `objc` パッケージのコンディションのリーダーです。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (defvar *e* (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                                     "attributesOfItemAtPath:error:" "/no/such/file")
                      (ns-error (e) e)))
*E*
MY-APP> (invoke-into 'string (ns-error-object *e*) "domain")
"NSCocoaErrorDomain"
```
