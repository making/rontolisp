# Command-Line Options

`rontolisp --help` describes every option, grouped by what it belongs to: the options every
run takes, those of one front end (the source language, picked by the file's extension or
`--source-language`) and those of one back end (picked by `-o`).

```bash
rontolisp --help                              # every group, the same as --help=all
rontolisp --help=frontend:java,backend:wasm   # the common options, Java and WASM
```

`--help=` takes group names, comma-separated, and always prints the common options as well.
A name that is no group is refused with the list of those that exist. An option of several
printed groups is described under the first of them; the others name it at their end
(`See backend:jvm for --dynamic, --optimize, ...`). The value is glued with `=`, as for
`--optimize=size`.

An option no group holds is refused (`unknown option '--simdd' (try: rontolisp --help)`)
rather than ignored. The subcommands `format`, `test`, `build` and `notices` have help of
their own (`rontolisp test --help`).

## Groups

| Group | Options |
|---|---|
| `common` | `-h`, `-v`, `-e`, `-o`, `--source-language`, `--dump-ir` |
| `frontend:common-lisp` | `--system-path`, `--dist`, `--feature`, `--swank` |
| `frontend:clojure` | `-A`, `-M`, `-X`, `--nrepl`, `--java-update-snapshots` |
| `frontend:scheme` | `--scheme-standard` |
| `frontend:starlark` | `--starlark-standard` |
| `frontend:java` | `--java-source-path`, `--java-source-dep`, `--java-metadata-repository`, `--java-repository`, `--java-snapshots`, `--java-update-snapshots`, `--wasi-logging` |
| `backend:interpreter` | `--stack`, `--buffered-output`, `--simd`, `--parallel`, `--blas`, `--gpu`, `--java-classpath`, `--java-dep`, `--warn-java-reflection`, `--swank`, `--nrepl`, `--java-repository`, `--java-snapshots`, `--java-update-snapshots` |
| `backend:jvm` | `--class-name`, `--no-main`, `--maven-coordinates`, `--emit-pom`, `--java-release`, `--java-static`, `--dynamic`, `--optimize`, `--no-prune`, `--warnings-as-errors`, `--simd`, `--parallel`, `--blas`, `--gpu`, `--java-classpath`, `--java-dep`, `--warn-java-reflection`, `--java-repository`, `--java-snapshots`, `--java-update-snapshots` |
| `backend:wasm` | `--no-gc`, `--no-wasi`, `--host-random`, `--host-output`, `--host-fetch`, `--host-sockets`, `--host-timer`, `--host-boundary`, `--reentrant`, `--emit-js-glue`, `--emit-worker-entry`, `--report-locations`, `--dynamic`, `--optimize`, `--no-prune`, `--warnings-as-errors`, `--simd`, `--wasi-logging` |
| `backend:component` | `--component`, `--emit-wit`, `--scaffold-wit`, `--world`, `--no-gc`, `--no-wasi`, `--report-locations`, `--dynamic`, `--optimize`, `--no-prune`, `--warnings-as-errors`, `--wasi-logging` |
| `backend:native` | `--native`, `--native-target`, `--native-cpu`, `--report-locations`, `--dynamic`, `--optimize`, `--no-prune`, `--warnings-as-errors`, `--simd` |

The back ends: `interpreter` runs the program in the `rontolisp` process (no `-o`); `jvm`
compiles to `-o OUT.class`, `OUT.jar` or `OUT.war`; `wasm` to `-o OUT.wasm`; `component` to
`-o OUT.wasm --component`; `native` to an executable with `--native -o OUT`.
