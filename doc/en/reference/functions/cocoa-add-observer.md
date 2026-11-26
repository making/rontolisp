# cocoa:add-observer

`(cocoa:add-observer target selector &key name object center)`

Makes `target` (an object, or an `objc:standard-objc-object`) observe the notifications named `name` (a string) posted by `object`, `nil` matching any: `selector`, a method taking the `NSNotification`, is sent for each. `center` defaults to the default notification center. Answers `nil`. Part of the macOS-only `cocoa` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (define-objc-class watcher ()
          ((seen :initform nil :accessor seen))
          (:objc-class-name "Watcher"))
WATCHER
MY-APP> (define-objc-method ("noticed:" :void) ((self watcher) (note objc-object-pointer))
          (push (invoke-into 'string note "name") (seen self)))
"noticed:"
MY-APP> (defvar *w* (make-instance 'watcher))
*W*
MY-APP> (cocoa:add-observer *w* "noticed:" :name "Ping")
NIL
MY-APP> (invoke (invoke "NSNotificationCenter" "defaultCenter")
                "postNotificationName:object:" "Ping" nil)
NIL
MY-APP> (seen *w*)
("Ping")
MY-APP> (cocoa:remove-observer *w* :name "Ping")
NIL
```
