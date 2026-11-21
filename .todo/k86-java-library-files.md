# k86. Java library: files (`File`, the file streams, `java.nio.file`)

Difficulty: High

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from `.todo/k72`.

No file can be named: `File`, `FileReader`, `FileWriter`, `FileInputStream`,
`FileOutputStream`, `RandomAccessFile`, `java.nio.file` (`Path`, `Paths`, `Files`) and the
members over them (`Scanner` of a `File` or a `Path`, `PrintWriter` / `PrintStream` / `Formatter`
of a file or its name, `Files.lines`, `readAllLines`, `writeString`) are refused by name
(`doc/*/java/deviations.md`, "What is refused").

- What each target can reach: the interpreter and the JVM the host's file system, a `.wasm`
  the WASI preopens its host grants (`wasmtime run --dir`), the browser playground nothing
  (a refusal by name where the program is compiled for it, as threads were: `.kb/java-frontend.md`,
  "In the browser"). The Common Lisp side already opens files on every backend
  (`.kb/` grep `fd_read`, `path_open`, `WasmIoRuntimeBuilder`).
- The answers to learn from a JDK: `File`'s path arithmetic (`getParent`, `getName`,
  `getAbsolutePath` against a working directory), the words of `FileNotFoundException`,
  `NoSuchFileException`, `AccessDeniedException`, the order `list()` and `Files.list` give
  (none is specified), `Files.readString`'s refusal of malformed input.
- `Scanner` reads a `Reader`, so `Scanner(File)` and `Scanner(Path)` are a constructor each
  once a file has a reader (`.kb/java-frontend.md`, "A joiner, a tokenizer, a bit set, a
  scanner and a char buffer").
- Measure: a program that reads no file holds none of it.
