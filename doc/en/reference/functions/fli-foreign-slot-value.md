# fli:foreign-slot-value

`(fli:foreign-slot-value pointer slot-name &key type object-type copy-foreign-object)`

Answers the slot `slot-name` of the structure `pointer` points at (of type `object-type` when given); a list of names reaches into a nested structure. Slots are those `objc:define-objc-struct` declared, and `cocoa:ns-rect`'s `origin` / `size`, `cocoa:ns-point`'s `x` / `y`, `cocoa:ns-size`'s `width` / `height` and `cocoa:ns-range`'s `location` / `length`, matched by name in any package. The value is read as [`fli:dereference`](fli-dereference.md) reads one, `copy-foreign-object` included; `setf` writes one. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (fli:with-dynamic-foreign-objects ((rect cocoa:ns-rect))
           (objc:invoke-into rect (objc:invoke "NSValue" "valueWithRect:" #(0 0 640 480)) "rectValue")
           (fli:foreign-slot-value rect '(:size :width)))
640.0
```
