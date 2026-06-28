# WASI 0.3 Component

Add `--component` to emit a WASI 0.3 (Preview 3) **component** instead of a Preview 1 core module. The component prints through `wasi:cli/stdout@0.3.0`:

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar hello.lisp --component -o hello.wasm
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y hello.wasm
```

```
3
```

In WASI 0.3 all byte I/O flows through the built-in component-model `stream<u8>` / `future<T>` types and the async canonical ABI. rontolisp keeps the same Preview 1 core module unchanged — it still imports the eight `wasi_snapshot_preview1` functions — and an **adapter** core module implements them over WASI 0.3 (`wasi:cli`, `wasi:filesystem`, `wasi:clocks`, `wasi:random`) using `stream.new`/`stream.read`/`stream.write` and `future.read`. The component's `wasi:cli/run@0.3.0` export (an `async func`) is lifted as a **stackful** async export, so the synchronous stream/future built-ins block cooperatively and the adapter stays straight-line code. The three `component-model-async*` flags enable those features (stackful async lift + synchronous stream/future built-ins).

The wasmtime invocation does **not** select the output kind. `wasmtime run` is wasmtime's default subcommand and auto-detects a core module vs a component, so `wasmtime run -W gc` runs the Preview 1 `hello.wasm` from the previous section just as well. Only the `--component` compile flag decides whether a Preview 1 core module or a WASI 0.3 component is produced. (The practical difference shows up on a component-only runtime, which runs the component but not the Preview 1 core module.)

The default output (without `--component`) stays a Preview 1 core module, so nothing changes for existing usage.

File I/O works in component mode too — it is implemented over `wasi:filesystem@0.3.0` (`read-via-stream` / `append-via-stream`, driven through `stream`/`future`). As in Preview 1, file access needs `--dir`:

```bash
cat > fileio.lisp <<'EOF'
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out))
(with-open-file (in "greeting.txt")
  (print (read-line in)))
EOF
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar fileio.lisp --component -o fileio.wasm
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y --dir . fileio.wasm
# "hello"
```

Notes and current limitations of component mode:

- Requires a runtime with WASI 0.3 component-model async support: **wasmtime 46+** (pass `-W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y`).
- `print`/stdout, stdin (`read`, 0-argument `read-line`, over `wasi:cli/stdin@0.3.0`), and file I/O (`open`, `close`, `write-line`, stream `read-line`, `load`, `with-open-file`) all work. File access requires `--dir` (paths resolve against the first preopened directory).
- `random` draws real entropy from `wasi:random@0.3.0` (Preview 1 uses the host's `random_get`), so `(random N)` differs each run. `get-universal-time` / `get-internal-real-time` / `get-internal-run-time` read `wasi:clocks@0.3.0` (`system-clock`/`monotonic-clock`), and `getenv` reads `wasi:cli/environment@0.3.0`.
- Outgoing HTTP (`rontolisp:fetch`) works in component mode, but is a **hybrid**: the base I/O stays WASI 0.3 while fetch itself imports `wasi:http@0.2` + `wasi:io@0.2` (async `wasi:http@0.3` does not exist upstream yet — see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md`). Run a fetch component with `-S http=y` in addition to the async flags. Non-fetch components do not import `wasi:http`, so they do not need `-S http`.
- The compiled Lisp otherwise behaves identically to the Preview 1 output for the supported features.
