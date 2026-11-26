# cocoa Package Functions

The `cocoa` package is LispWorks 8.1's `COCOA`: the four Foundation structures
`objc:invoke` converts, their setters, `cocoa:ns-not-found` and the notification observers. It is **macOS
only** and **not part of Common Lisp**. A structure is the Lisp value `objc:invoke`
passes and answers for it -- a vector `#(x y)` for `NSPoint`, `#(width height)` for
`NSSize`, `#(x y width height)` for `NSRect` (all doubles) and a cons
`(location . length)` for `NSRange` -- or a foreign object of the type
([`fli`](fli.md)), whose slots are `x` `y`, `width` `height`, `origin` `size` and
`location` `length`. The setters fill either. The
symbols `cocoa:ns-point`, `cocoa:ns-size`, `cocoa:ns-rect` and `cocoa:ns-range`
name the structures in a list-form method's types and in
`objc:objc-class-method-signature`'s answer (`(:struct cocoa:ns-range)`), and
`cocoa:ns-not-found` is `NSNotFound`, 9223372036854775807.

| Function | Example | Result |
|----------|---------|--------|
| [`cocoa:set-ns-point*`](cocoa-set-ns-point-star.md) | `(cocoa:set-ns-point* (make-array 2) 10 20)` | `#(10 20)` |
| [`cocoa:set-ns-size*`](cocoa-set-ns-size-star.md) | `(cocoa:set-ns-size* (make-array 2) 640 480)` | `#(640 480)` |
| [`cocoa:set-ns-rect*`](cocoa-set-ns-rect-star.md) | `(cocoa:set-ns-rect* (make-array 4) 0 0 640 480)` | `#(0 0 640 480)` |
| [`cocoa:set-ns-range*`](cocoa-set-ns-range-star.md) | `(cocoa:set-ns-range* (cons 0 0) 6 5)` | `(6 . 5)` |
| [`cocoa:add-observer`](cocoa-add-observer.md) | `(cocoa:add-observer w "noticed:" :name "Ping")` | `nil`; `w` observes `Ping` |
| [`cocoa:remove-observer`](cocoa-remove-observer.md) | `(cocoa:remove-observer w :name "Ping")` | `nil`; `w` stops observing |
