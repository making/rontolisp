# l02. Java library: what k86 left of files

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from `.todo/k86`;
what is there and how: `.kb/java-frontend.md`, "Files"; the probes and `four.sh`:
`.todo/artefacts/k86-java-library-files/`.

- Members refused by name (`doc/*/java/deviations.md`, "Files"): `File.toURI`,
  `deleteOnExit`, `createTempFile`, `listRoots`, `canExecute`, the setters
  (`setReadable` .. `setLastModified`, `setReadOnly`), the space queries; `Path.toUri`,
  `Path.of(URI)`, `getFileSystem`; `Files.newDirectoryStream` (with a glob), `find`,
  `walkFileTree` and the visitors, `isSymbolicLink`, `getLastModifiedTime` with `FileTime`,
  `readAttributes` and the rest of `java.nio.file.attribute`, `createTempFile` /
  `createTempDirectory`. Ask a JDK what each answers (`toURI` escapes otherwise than
  `toUri`; a temporary file's directory is `java.io.tmpdir`, which a `.wasm` has only where
  its host preopens it).
- Options taken and ignored: `DELETE_ON_CLOSE`, `LinkOption.NOFOLLOW_LINKS` (every member
  follows a link; `walk` goes into one), `COPY_ATTRIBUTES`, `ATOMIC_MOVE`.
- A `.wasm` cannot see a file it may not open: `%probe-file` opens it, and wasmtime 49 refuses
  a mode-000 file even to an open asking no rights (measured 2026-11-21), so `exists()` is
  false there. Lead: read `path_open`'s errno, `EACCES` meaning "there", and retry with
  `O_DIRECTORY` to tell a directory (`ENOTDIR` a file) -- a change to every wasm program's
  `probe-file`, to be measured on the CL side first (`.kb/read-load-streams.md`).
- What the host is not asked: whether a directory may be written (`canWrite`,
  `Files.isWritable` throw), the size of a directory and of a file the program may not read
  (0; `stat` needs no open, a `.wasm`'s `path_filestat_get` is an import gated on
  `file-write-date`), `lastModified` past whole seconds, `System.getProperty("user.dir")`
  (null: `Support.property` would carry the working directory into every program asking any
  key -- lower a constant key where it is called, as `Charset.forName` is).
- Measure: a program that reaches no file stays byte-identical (`sizes/` of the k86
  artefacts).
