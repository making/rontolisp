# k75 probes

Written here; run under JDK 25.0.5 (`-Duser.language=en -Duser.country=US`) and on the four
backends (`four.sh JAR OUTDIR args..`, which compares each compiled leg with the
interpreter's output). What they showed is in `.kb/java-frontend.md`, "Resources"; the
answers the corpus keeps are lines of `java-spec.yaml`.

- `directories/Main.java`: a directory as a resource -- of the tree, a class path
  directory, a jar holding directory entries (`lib.jar`, made by `jar cf`) and one holding
  none (`nodir.jar`, `zip -D`), each by a constant and a computed name. Run as
  `java -cp libdir:lib.jar:nodir.jar tree/app/Main.java` (the subset:
  `--java-source-path libdir:lib.jar:nodir.jar`) with `tree/app/sub/{a,b}.txt`,
  `tree/app/sub/deeper/c.txt`, an empty `tree/app/sub/emptydir`, `libdir/app/sub/l.txt`,
  `libdir/lone/x/y.txt`, `lib.jar` of `app/sub/j.txt` and `jaronly/k.txt`, `nodir.jar` of
  `nd/inner/f.txt`. `norm.py` sorts each listing, whose order differs by decision.
- `bundle/app`: a bundle class only a computed base name finds; `Unused.java` holds a type
  error the launcher never compiles and the subset must not read.
- `xml/Main.java`: `loadFromXML` of 59 documents and `storeToXML` in five charsets;
  `xml/Encodings.java` the charsets, the order of the entries and the streams' closing.
- `control/app/Main.java`: `ResourceBundle.Control` -- its constants, the library's
  controls, a program's logging every call, `getBundle` of a module -- over
  `app/m.properties`, `m_en`, `m_en_GB` and `u.properties` (UTF-8).
- `playground/Probe.java`: what the uploads' listing changes in the playground's languages,
  run against `target/classes` with the old loader (a lambda) and `UploadedFiles`.
- `sz.sh OLD.jar NEW.jar File.java..`: the wasm bytes of each program under two exec jars.
