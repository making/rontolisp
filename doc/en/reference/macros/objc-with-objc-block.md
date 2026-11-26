# objc:with-objc-block

`(objc:with-objc-block (var type function) form*)`

Evaluates `form`s with `var` bound to a block `objc:make-objc-block` makes from `type` and `function`, and frees it on every exit, answering the last form's values. Right for asynchronous work too: a callee that keeps the block holds a copy, which keeps `function` alive after the form returns. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (with-objc-block (each '(:void (objc-object-pointer (:unsigned :long-long)
                                        (:pointer objc-c++-bool)))
                               (lambda (word index stop)
                                 (declare (ignore stop))
                                 (format t "~a ~a~%" index (ns-string-to-string word))))
          (invoke *words* "enumerateObjectsUsingBlock:" each))
0 pear
1 fig
2 apple
NIL
```
