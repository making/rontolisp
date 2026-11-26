# k91. wasm: the standard input is read a character a host call

Difficulty: Medium

Measured by k72 (2026-11-21, `.kb/java-frontend.md`, "A joiner, a tokenizer, a bit set, a
scanner and a char buffer"): reading 10 MB of the
standard input on wasm takes 5.63 s through Java's `InputStreamReader` (`ReadChars.java`),
6.24 s through `BufferedReader.readLine` (`ReadLines.java`, which is `read-line`), where the
JVM class takes 0.83 / 1.04 s. A `Scanner` summing a million ints is 7.84 s on wasm, of which
the reading is most; a JDK takes 2.16 s.

- Find where the time goes: Java's `StandardInput` asks `%java-read-character` for one
  character a call (`read-char` of `*standard-input*`), and `read-line` on wasm reads a
  character at a time too; whether `_read_char` asks `fd_read` for one byte a call
  (`.kb/` grep `_read_char`, `WasmCharIoRuntimeBuilder`).
- A buffer in the runtime must keep what the line discipline promises: a program reading a
  line from a terminal waits for that line and no more (`.kb/java-frontend.md`, "java.io":
  a read of several goes on to the line's end on wasm).
- Every language reading its standard input on wasm gains; measure the Common Lisp
  `read-line` loop beside the Java ones.
