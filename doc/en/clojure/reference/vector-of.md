# vector-of

`(vector-of type x ...)`

`clojure.core/vector-of`: a vector of the members, each stored as the primitive `type`
holds it: `:int`, `:long`, `:short` and `:byte` truncate a number (a character to its
code) and refuse one out of range in the oracle's words (`integer overflow`, `Value out of
range for byte: 200`), `:double` and `:float` widen to a double, `:char` takes a character
or a code, `:boolean` is the truthiness. Any other `type` signals `Unrecognized type`. The
answer is an ordinary vector: a later `conj` or `assoc` stores its value as given, where
the oracle's keeps casting, and `:float` holds doubles (the oracle rounds to a float). As
a value a type and any number of members.

```clojure
(println (vector-of :int 1.7 -1.7 \a)) ; [1 -1 97]
(println (vector-of :double 1 1/2))    ; [1.0 0.5]
```
