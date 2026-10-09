# e82. wasm: `file-write-date` and removing an empty directory

Difficulty: Medium

Both WASM backends answer `file-write-date` with nil and cannot remove a directory
(`.kb/read-load-streams.md`: preview1's `path_unlink_file` takes no directory, and the
component adapter zero-fills `descriptor-stat`'s timestamps). Clojure now surfaces both
through `clojure.java.io` (measured 2026-10-08): a File's `.lastModified` answers 0 on wasm
(the interpreter and the JVM the modification time in ms), and `.delete` of an empty
directory answers `false` and leaves it (`ClojureJavaIoTest#anEmptyDirectoryIsDeletedWhereTheHostRemovesDirectories`
pins the deviation: interpreter/JVM `true true false`, wasm `true false true`).

## Plan

1. The path stat: lift `data-modification-timestamp` out of `descriptor.stat-at` (component
   adapter) and `path_filestat_get`'s `mtim` (preview1) beside `_probe_file`;
   `file-write-date` answers universal time on all four backends (a ci-spec line).
2. `path_remove_directory` (preview1) / `descriptor.remove-directory-at` (adapter) behind
   the runtime's delete of a directory; `%delete-file` of an empty directory removes it.
3. Flip the `ClojureJavaIoTest` deviation and the two bullets of
   `doc/*/clojure/reference/clojure-java-io.md`.
