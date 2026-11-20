# k88. Java library: the `java.nio` buffers beyond `CharBuffer`

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from `.todo/k72`.

k72 wrote `Buffer` and `CharBuffer` (one class for every char buffer: an array from an offset,
or a text; `.kb/java-frontend.md`, "A joiner, a tokenizer, a bit set, a scanner and a char
buffer"). Refused by name: `ByteBuffer`, `ShortBuffer`, `IntBuffer`, `LongBuffer`,
`FloatBuffer`, `DoubleBuffer`, `ByteOrder`, `CharBuffer.order()`, the views of a byte buffer
(`asCharBuffer`, `asIntBuffer` ..), `BitSet.valueOf` of a `LongBuffer` or a `ByteBuffer` and
`Charset.encode` / `decode` over buffers, the charsets' `CharsetEncoder` / `CharsetDecoder`.

- `ByteBuffer` first: the HTTP client's `BodySubscriber` / `BodyPublisher` hand one
  (`.kb/java-frontend.md`, "java.net.http"; `.todo/k82`). Its typed reads (`getInt`,
  `getLong`, `getDouble`) in both orders, `wrap`, `allocate`, `allocateDirect` (an array here,
  `isDirect` a JDK's answer to decide), `slice`, `duplicate`, `compact`, `order`.
- The words of each refusal and `hashCode` are a JDK's answers (`Chars.java` of
  `.todo/artefacts/k72-java-library-the-everyday-classes-j71-left/probes/` asked them of
  `CharBuffer`). `ByteOrder.nativeOrder()` is the platform's: decide what each target answers.
- Measure: a program with a `CharBuffer` and none of the others holds none of them.
