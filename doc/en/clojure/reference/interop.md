# Java interop

Interop lowers to the `java:` surface and runs on the interpreter and the JVM only -- the wasm backends reject `java:`. Class names resolve dotted as written, through `:import`, or through `java.lang`. A string receiver answers the mapped core operation (a Lisp string is no host object), and `.toString` of any value that is no host object its `str` spelling, on every backend. `str` of a host object answers its `toString`; printing one shows `#<java C>`, except a class object, which prints its name.

| Name | Example | Result |
|---|---|---|
| `.` | `(. "hi" length)` | `2` |
| `..` | `(.. "hi" (toUpperCase) (length))` | `2` |
| `.name / .-name` | `(.toUpperCase "hi")` | `HI` |
| `Class/member` | `(Integer/parseInt "42")` | `42` |
| `Class/member` (value) | `(every? Character/isWhitespace " ")` | `true` |
| `Class/.method` | `(map String/.length ["ab" "abcd"])` | `(2 4)` |
| `Class/new` | `(String/new "q")` | `q` |
| `^[types]` | `(map ^[double] Math/abs [-1 2])` | `(1.0 2.0)` |
| `new` | `(.length (new String "hi"))` | `2` |
| `memfn` | `((memfn toUpperCase) "hi")` | `HI` |
| `proxy` | `(.get (proxy [java.util.function.Supplier] [] (get [] "p")))` | `p` |
