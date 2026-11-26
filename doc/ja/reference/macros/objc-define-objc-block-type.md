# objc:define-objc-block-type

`(objc:define-objc-block-type name result-type (argument-type*))`

ブロックのシグネチャに名前を付けます。以後 `name` は `objc:make-objc-block`、`objc:with-objc-block`、`objc:call-objc-block` で `(result-type (argument-type*))` の代わりに使えます。型は `objc:invoke` のリスト形式と同じもので、ここで検査されます。`name` を返し、ランタイムは要りません。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (define-objc-block-type comparator :long-long
          (objc-object-pointer objc-object-pointer))
COMPARATOR
MY-APP> (with-objc-block (compare 'comparator
                                  (lambda (a b)
                                    (let ((x (ns-string-to-string a))
                                          (y (ns-string-to-string b)))
                                      (cond ((string< x y) -1) ((string> x y) 1) (t 0)))))
          (invoke-into '(array string) *words* "sortedArrayUsingComparator:" compare))
#("apple" "fig" "pear")
```
