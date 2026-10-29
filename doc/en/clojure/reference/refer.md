# refer

`(refer ns-sym & filters)`

The namespace `ns-sym` loaded and its vars referred into the current namespace -- bare,
every public one; `:only`, `:exclude` and `:rename` narrow -- answering `nil`, like the
oracle's. Anything computed answers when the call runs on the interpreter, which keeps the program's lowering, and refuses at run time in a compiled program. As a value any
number of arguments.

```clojure
(refer 'clojure.string :only '[upper-case])
(println (upper-case "ab")) ; AB
```
