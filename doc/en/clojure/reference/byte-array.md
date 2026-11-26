# byte-array

`(byte-array size-or-seq)` `(byte-array size init)`

Answers a byte array, Java's `byte[]`: `size` zeros, or the members of a seq, each stored as
`Number.byteValue` stores it (an integer's low eight bits, so `300` is `44`). With `size` and
`init`, every element is the byte `init`, or the next member of the seq `init` (zeros past its
end). An element reads back as a signed byte, `-128` to `127`. The array is mutable
([aset](aset.md), [aset-byte](aset-byte.md)); it is no collection (`coll?` and `vector?` are
false, `=` is identity) but seqs its elements, so `count`, `seq`, `vec`, `nth`, `get` and the
seq functions take it. It prints as the oracle's `#object["[B" ...]` without the identity hash.
On the interpreter and the JVM it is a Java member's `byte[]`, both ways ([interop](interop.md)).
As a value a function of one or two arguments.

`.getBytes` and `(String. bytes ...)` take the charset as a name (`"UTF-8"`, `"ISO-8859-1"`,
`"US-ASCII"` or an alias) or as a `java.nio.charset.Charset`: one of the six
`StandardCharsets` fields, or `Charset/forName` of a string literal. That value is the
charset on every backend: `.name`, `.displayName`, `str` and `.toString` answer its name,
`=`, `.equals`, `.hashCode` and `.compareTo` compare by name, and it is an `instance?` of
`Charset` and `Comparable`. The decoding `InputStreamReader`, the encoding `OutputStreamWriter`
and `ByteArrayOutputStream.toString` take it too, where `:encoding` of `slurp` and
`clojure.java.io` takes a name only (a `Charset` is the oracle's `ClassCastException`). It
crosses into a Java member as the host `Charset` (the interpreter and the JVM,
[interop](interop.md)). `Charset/forName` of a name the JDK rejects is the oracle's
`UnsupportedCharsetException`, or `IllegalCharsetNameException` for a malformed one.

```clojure
(def ba (byte-array [104 105 -1 300]))
(println (vec ba) (alength ba) (count ba)) ; [104 105 -1 44] 4 4
(println (vec (byte-array 3)) (vec (byte-array 3 [1 2]))) ; [0 0 0] [1 2 0]
(println (String. (byte-array [104 105]) "UTF-8")) ; hi
(import '[java.nio.charset StandardCharsets])
(println (vec (.getBytes "é" StandardCharsets/UTF_8)) (.name StandardCharsets/UTF_8)) ; [-61 -87] UTF-8
```
