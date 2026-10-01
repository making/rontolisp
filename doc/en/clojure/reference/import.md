# import

`(import class...)`

Registers class names for interop, answering `nil` -- the same wiring `ns`'s `:import` clause
does, spelled at top level with the package-qualified class names.

```clojure
(import java.util.Date)
(println (new Date)) ; the current instant
```
