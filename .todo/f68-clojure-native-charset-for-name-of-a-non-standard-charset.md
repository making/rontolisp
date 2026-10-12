# f68. Clojure on the native binary: `Charset/forName` of a non-standard charset literal

Difficulty: Medium

The native binary lowers `(Charset/forName "windows-1252")` to a refusal where the jar lowers
it to the charset's value (measured 2026-10-12, `--dump-ir`, GraalVM 25.0.4):

| driver | `(str (java.nio.charset.Charset/forName "windows-1252"))` |
|---|---|
| exec jar | `(%CLOJURE-IO-CHARSET-VALUE "windows-1252" "sun.nio.cs.MS1252")` |
| native binary | `(ERROR "windows-1252")` |

`"Shift_JIS"` alike; `"UTF-8"`, `"latin1"`, `"ascii"` agree. `ClojureCharsetLowering.forName`
calls `Charset.forName` while lowering, and the image holds only GraalVM's default (standard)
charsets. The host-classes fix (`.kb/clojure-frontend.md`, "Host classes") does not reach it:
a charset comes from the process's providers, not from a class file.

Two ways, the choice being the binary's size against a table to keep:

1. `-H:+AddAllCharsets` in the `native` profile. Tried 2026-10-12: the binary built with it
   (and the host-classes change, which adds little) was 129,239,304 bytes against 122,816,776
   before. The interpreter on the binary gains every charset too.
2. A table of the JDK's charsets (canonical name, aliases, implementation class) generated from
   `Charset.availableCharsets()`, pinned to the JDK by a test the way `ClojureThrowablesTest`
   pins `ClojureThrowables.PARENTS`, read by the lowering in place of `Charset.forName`.

Check: add the form to `ClojureLoweringCorpus.HOST_FORMS`; `ClojureNativeLoweringE2eTest` then
holds the binary to the jar.
