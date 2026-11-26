# fli:define-foreign-function

`(fli:define-foreign-function name (arg*) &key result-type module variadic-num-of-fixed)`

The part of LispWorks' `fli:define-foreign-function` the `objc` package's examples reach for: defines `name` as a Lisp function calling the C function `foreign-name`, which any image loaded in the process may define (libSystem, a framework, a module `objc:ensure-objc-initialized` or `:module` loaded). `name` is `lisp-name` or `(lisp-name foreign-name)`; a foreign name left out is the Lisp name in lower case with each hyphen an underscore. An argument is `(arg-name type)` or `(:constant value type)`, the types those `objc:invoke`'s list form takes, converted the same way -- a block goes as `objc:objc-at-question-mark`. `:result-type` defaults to `:int`; an object result is owned when the function's name says it creates or copies (`dispatch_queue_create`, `CFStringCreateCopy`), and retained otherwise. `:variadic-num-of-fixed` makes the arguments past that count variadic. The function runs on the calling thread. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime a call signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (fli:define-foreign-function (dispatch-queue-create "dispatch_queue_create")
            ((label objc-c-string) (attributes :pointer))
          :result-type objc-object-pointer)
DISPATCH-QUEUE-CREATE
MY-APP> (fli:define-foreign-function (dispatch-async "dispatch_async")
            ((queue objc-object-pointer) (work objc-at-question-mark))
          :result-type :void)
DISPATCH-ASYNC
MY-APP> (defvar *queue* (dispatch-queue-create "com.example.work" nil))
*QUEUE*
```
