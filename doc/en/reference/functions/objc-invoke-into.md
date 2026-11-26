# objc:invoke-into

`(objc:invoke-into result class-or-object-pointer method &rest args)`

`objc:invoke` whose answer is converted or stored as `result` says. The symbol `string` converts an `NSString` result to a Lisp string; `array` converts an `NSArray` to a vector of pointers, and `(array string)` (nesting allowed) converts its elements too. A vector is filled with an `NSArray`'s elements, or with an `NSRect` / `NSSize` / `NSPoint` result's fields; a cons receives an `NSRange` result's location and length, and a foreign object ([`fli`](fli.md)) receives any result of its type. `:pointer` answers a C-string result as its address. Any other combination answers what `objc:invoke` would. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

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
