# objc Package Functions

The `objc` package binds the Objective-C runtime and AppKit through the foreign
function API — no reflection, so unlike `java:` it works in the **native
binary** as well as under `java -jar`. It is **macOS only** -- the interpreter, a
compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon,
never a `.wasm` -- and **not part of Common Lisp**; reference its functions with
the `objc:` qualifier. Each name below links to its own page; the
[macOS GUI guide](../../guides/objc-appkit.md) covers conversion, threads,
ownership and the native binary's shape table.

## The LispWorks interface

The names of LispWorks 8.1's Objective-C interface, with its lambda lists, so code
written against its manual runs here. The package also names the types:
`objc:objc-object-pointer` (every object), `objc:objc-class` (a class, also an
object pointer) and `objc:sel` (a selector) are the types of the values these
functions answer, and `objc:objc-bool`, `objc:objc-c++-bool`,
`objc:objc-c-string`, `objc:objc-at-question-mark` and `objc:objc-unknown` are
the type designators a list-form method and `objc:objc-class-method-signature`
use. The macro [`objc:with-autorelease-pool`](../macros/objc-with-autorelease-pool.md)
evaluates its body inside an autorelease pool; `objc:on-main` (below) is shared
by both halves of the package. Classes are defined with the macros
[`objc:define-objc-class`](../macros/objc-define-objc-class.md),
[`objc:define-objc-method`](../macros/objc-define-objc-method.md),
[`objc:define-objc-class-method`](../macros/objc-define-objc-class-method.md),
[`objc:current-super`](../macros/objc-current-super.md),
[`objc:define-objc-struct`](../macros/objc-define-objc-struct.md),
[`objc:define-objc-typedef`](../macros/objc-define-objc-typedef.md) and
[`objc:define-objc-protocol`](../macros/objc-define-objc-protocol.md); an instance of
such a class is an `objc:standard-objc-object`.

| Function | Example | Result |
|----------|---------|--------|
| [`objc:ensure-objc-initialized`](objc-ensure-objc-initialized.md) | `(objc:ensure-objc-initialized :modules '("/path/Framework"))` | `nil`; the modules are loaded |
| [`objc:invoke`](objc-invoke.md) | `(objc:invoke "NSString" "stringWithUTF8String:" "hi")` | the method's value, converted by its declared types |
| [`objc:invoke-bool`](objc-invoke-bool.md) | `(objc:invoke-bool s "hasPrefix:" "h")` | `t` or `nil` |
| [`objc:invoke-into`](objc-invoke-into.md) | `(objc:invoke-into 'string s "description")` | the value, converted or stored as the first argument says |
| [`objc:can-invoke-p`](objc-can-invoke-p.md) | `(objc:can-invoke-p s "length")` | `t` or `nil` |
| [`objc:alloc-init-object`](objc-alloc-init-object.md) | `(objc:alloc-init-object "NSObject")` | a new instance |
| [`objc:description`](objc-description.md) | `(objc:description s)` | the `description`, a string |
| [`objc:trace-invoke`](objc-trace-invoke.md) | `(objc:trace-invoke "length")` | the name; later sends of it are traced |
| [`objc:untrace-invoke`](objc-untrace-invoke.md) | `(objc:untrace-invoke "length")` | the name; the trace is removed |
| [`objc:coerce-to-objc-class`](objc-coerce-to-objc-class.md) | `(objc:coerce-to-objc-class "NSString")` | a class pointer |
| [`objc:objc-class-name`](objc-objc-class-name.md) | `(objc:objc-class-name c)` | the class's name |
| [`objc:coerce-to-selector`](objc-coerce-to-selector.md) | `(objc:coerce-to-selector "frame")` | a selector |
| [`objc:selector-name`](objc-selector-name.md) | `(objc:selector-name sel)` | the selector's name |
| [`objc:objc-class-method-signature`](objc-objc-class-method-signature.md) | `(objc:objc-class-method-signature "NSString" "length")` | argument types, result type, encoding |
| [`objc:retain`](objc-retain.md) | `(objc:retain p)` | `p`, retained |
| [`objc:release`](objc-release.md) | `(objc:release p)` | `nil`; one reference given up |
| [`objc:autorelease`](objc-autorelease.md) | `(objc:autorelease p)` | `p`; one reference handed to the pool |
| [`objc:retain-count`](objc-retain-count.md) | `(objc:retain-count p)` | the `retainCount` |
| [`objc:make-autorelease-pool`](objc-make-autorelease-pool.md) | `(objc:make-autorelease-pool)` | a pool, current until released |
| [`objc:ns-string-to-string`](objc-ns-string-to-string.md) | `(objc:ns-string-to-string s)` | a Lisp string |
| [`objc:string-to-ns-string`](objc-string-to-ns-string.md) | `(objc:string-to-ns-string "hi")` | an `NSString` the program owns |
| [`objc:objc-object-pointer`](objc-objc-object-pointer.md) | `(objc:objc-object-pointer p)` | the object's pointer |
| [`objc:objc-object-from-pointer`](objc-objc-object-from-pointer.md) | `(objc:objc-object-from-pointer p)` | the associated Lisp object, or `nil` |
| [`objc:objc-object-var-value`](objc-objc-object-var-value.md) | `(objc:objc-object-var-value obj "count")` | an instance variable's value; `setf`-able |
| [`objc:objc-object-copied`](objc-objc-object-copied.md) | `(defmethod objc:objc-object-copied :after ((old c) (new c)) ...)` | called when an instance is copied |
| [`objc:objc-object-destroyed`](objc-objc-object-destroyed.md) | `(defmethod objc:objc-object-destroyed :after ((o c)) ...)` | called when an instance is deallocated |

## Blocks

Blocks made from Lisp functions, which LispWorks' `OBJC` has no interface for, under
names of this package's own: an `objc:objc-block` passes wherever a method or a C
function takes a block. The macros
[`objc:define-objc-block-type`](../macros/objc-define-objc-block-type.md) and
[`objc:with-objc-block`](../macros/objc-with-objc-block.md) name a signature and make a
block for the extent of a body; C functions such as `dispatch_async` are declared with
[`fli:define-foreign-function`](../macros/fli-define-foreign-function.md).

| Function | Example | Result |
|----------|---------|--------|
| [`objc:make-objc-block`](objc-make-objc-block.md) | `(objc:make-objc-block '(:int (:int :int)) #'+)` | an `objc:objc-block` calling the function |
| [`objc:free-objc-block`](objc-free-objc-block.md) | `(objc:free-objc-block b)` | `nil`; a copy a callee kept lives on |
| [`objc:call-objc-block`](objc-call-objc-block.md) | `(objc:call-objc-block '(:int (:int :int)) b 3 4)` | the block's value |
| [`objc:objc-block-pointer`](objc-objc-block-pointer.md) | `(objc:objc-block-pointer b)` | the literal's address, or `nil` once freed |
| [`objc:objc-block-live-p`](objc-objc-block-live-p.md) | `(objc:objc-block-live-p b)` | `t` until freed |

## The first verbs

The package's original verbs, which the `appkit`, `metal` and `scene` layers are
written over. They stay until those layers move to the LispWorks interface.

| Function | Example | Result |
|----------|---------|--------|
| `objc:class` | `(objc:class "NSWindow")` | a class (`#<objc NSWindow>`) |
| `objc:send` | `(objc:send (objc:string "hi") "length")` | the result, marshalled by the selector's declared type |
| `objc:define-class` | `(objc:define-class "Target" "NSObject" (list (list "invoke:" fn)))` | a class whose methods are Lisp functions |
| `objc:on-main` | `(objc:on-main (lambda () ...))` | the function's value, computed on the main thread |
| `objc:string` | `(objc:string "hi")` | an `NSString` |
| `objc:data` | `(objc:data buffer)` | an `NSMutableData` holding the buffer's bytes |
| `objc:bytes` | `(objc:bytes data)` | an `NSData`'s bytes as a packed `(unsigned-byte 8)` vector |
| `objc:address` | `(objc:address obj)` | the object's address, an integer |
| `objc:objectp` | `(objc:objectp x)` | `t` for an Objective-C object |

