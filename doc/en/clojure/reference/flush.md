# flush

`(flush)`

Flushes `*out*` and answers `nil`. A prompt written with `print` needs it before the program waits for input. As a value, a function of no arguments.

```clojure
(print "name: ")
(prn (flush)) ; nil
```
