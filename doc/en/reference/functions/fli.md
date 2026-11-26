# fli Package Functions

The `fli` package is the part of LispWorks 8.1's foreign language interface the
Objective-C manual's examples use, beside `objc`: foreign objects a method fills by
reference, the pointers Objective-C hands back, and
[`fli:define-foreign-function`](../macros/fli-define-foreign-function.md). It is **macOS
only** and **not part of Common Lisp**. A foreign pointer is an address and the FLI type it
points at; the memory is the process's heap on every target. The macro
[`fli:with-dynamic-foreign-objects`](../macros/fli-with-dynamic-foreign-objects.md) binds
foreign objects for the extent of a body. See the [macOS GUI guide](../../guides/objc-appkit.md).

| Function | Example | Result |
|----------|---------|--------|
| [`fli:allocate-foreign-object`](fli-allocate-foreign-object.md) | `(fli:allocate-foreign-object :type :int)` | a foreign pointer to a zeroed `int` |
| [`fli:free-foreign-object`](fli-free-foreign-object.md) | `(fli:free-foreign-object p)` | `nil`; the memory is freed |
| [`fli:dereference`](fli-dereference.md) | `(fli:dereference p)` | the object `p` points at |
| [`fli:foreign-slot-value`](fli-foreign-slot-value.md) | `(fli:foreign-slot-value rect '(:size :width))` | a slot of a structure |
| [`fli:size-of`](fli-size-of.md) | `(fli:size-of 'cocoa:ns-rect)` | `32` |
| [`fli:pointerp`](fli-pointerp.md) | `(fli:pointerp p)` | `t` for a foreign pointer |
| [`fli:pointer-address`](fli-pointer-address.md) | `(fli:pointer-address p)` | the address, an integer |
| [`fli:make-pointer`](fli-make-pointer.md) | `(fli:make-pointer :address 4096 :type :int)` | a foreign pointer to that address |
| [`fli:null-pointer-p`](fli-null-pointer-p.md) | `(fli:null-pointer-p p)` | `t` for address 0 |
| [`fli:pointer-eq`](fli-pointer-eq.md) | `(fli:pointer-eq p q)` | `t` for the same address |
