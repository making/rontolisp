# objc:define-objc-block-type

`(objc:define-objc-block-type name result-type (argument-type*))`

Names a block signature: `name` then stands for `(result-type (argument-type*))` in `objc:make-objc-block`, `objc:with-objc-block` and `objc:call-objc-block`. The types are the ones `objc:invoke`'s list form takes, checked here. Answers `name`; needs no runtime. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

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
