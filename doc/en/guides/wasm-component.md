# WASI 0.3 Component (`--component`)

Add `--component` to emit a WASI 0.3 (Preview 3) **component** instead of a
Preview 1 core module. The component prints through
`wasi:cli/stdout@0.3.0`:

```bash
rontolisp hello.lisp --component -o hello.wasm
wasmtime run -W gc=y hello.wasm
```

```
3
```

In WASI 0.3 all byte I/O flows through the built-in component-model
`stream<u8>` / `future<T>` types and the async canonical ABI. rontolisp keeps
the same Preview 1 core module unchanged — it still imports the eight
`wasi_snapshot_preview1` functions — and an **adapter** core module
implements them over WASI 0.3 (`wasi:cli`, `wasi:filesystem`, `wasi:clocks`,
`wasi:random`) using `stream.new`/`stream.read`/`stream.write` and
`future.read`. Those built-ins are the **asynchronous** (non-blocking)
variants: when one reports BLOCKED, the task parks on a blocking
`waitable-set.wait` until the completion event arrives, so the adapter stays
straight-line code. The component's `wasi:cli/run@0.3.0` export (an
`async func`) is lifted as an async-typed export, from which that blocking
wait is legal. All of this sits on the base component-model async ABI,
enabled by default in wasmtime 46+ — no gated feature flags remain; only
`-W gc=y` (for the wasm-GC core) is needed.

The wasmtime invocation does **not** select the output kind. `wasmtime run`
is wasmtime's default subcommand and auto-detects a core module vs a
component, so `wasmtime run -W gc` runs a Preview 1 `hello.wasm` just as
well. Only the `--component` compile flag decides whether a Preview 1 core
module or a WASI 0.3 component is produced. (The practical difference shows
up on a component-only runtime, which runs the component but not the
Preview 1 core module.)

## What Runs Inside a Component

What works inside a component, and what each feature needs at run time:

- `print`/stdout, stdin (`read`, 0-argument `read-line`, over
  `wasi:cli/stdin@0.3.0`), and file I/O (`open`, `close`, `write-line`,
  stream `read-line`, `load`, `with-open-file`) all work. In an async body a
  pending stdin `read-line`/`read-char` suspends only its own task, like a
  socket read — a concurrent `rontolisp:wait-for` timer keeps running while
  the program waits for input. File access requires `--dir` (paths resolve
  against the first preopened directory):

```bash
cat > fileio.lisp <<'EOF'
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out))
(with-open-file (in "greeting.txt")
  (print (read-line in)))
EOF
rontolisp fileio.lisp --component -o fileio.wasm
wasmtime run -W gc=y --dir . fileio.wasm
# "hello"
```

- `random` draws real entropy from `wasi:random@0.3.0` (Preview 1 uses the
  host's `random_get`), so `(random N)` differs each run.
  `get-universal-time` / `get-internal-real-time` / `get-internal-run-time`
  read `wasi:clocks@0.3.0` (`system-clock`/`monotonic-clock`), and `getenv`
  reads `wasi:cli/environment@0.3.0`.
- Outgoing HTTP (`rontolisp:fetch` with the `rontolisp:await` /
  `rontolisp:futurep` future operations) works in component mode, including
  true asynchrony: `fetch` sends the request and returns a future (wrapping
  the in-flight `wasi:http` response handle) immediately, so several
  requests can overlap before `await` suspends on each. The future
  operations themselves compile in every mode; only `fetch` is
  component-only. fetch imports the async `wasi:http@0.3.0`
  (`wasi:http/types` + `wasi:http/client`) — uniformly WASI 0.3, like the
  rest of the component. Run a fetch component with `-S http=y` (which makes
  the host provide `wasi:http`) in addition to the usual flags. Non-fetch
  components do not import `wasi:http`, so they do not need `-S http`. A
  transport failure (refused connection, unresolvable host) signals
  `rontolisp:wit-error` at `await` time on every backend; `nil` comes back
  only for a request that cannot be started. See the
  [HTTP fetch guide](http-fetch.md) for the request/response shape.
- TCP sockets (`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` /
  `tcp-local-port`) work in component mode over `wasi:sockets@0.3.0`
  (natively WASI 0.3 — no 0.2 hybrid). A socket is a bidirectional stream
  handle, so `read-line` / `write-line` / `write-string` / `read-byte` /
  `write-byte` / `close` work on it directly. Run a socket component with
  `-W exceptions=y -S tcp=y -S inherit-network=y` in addition to the usual
  flags (a tcp component always compiles in exception-handling mode);
  without the `-S` flags the component still starts but every socket
  operation fails and yields `nil`. Hosts must be IPv4 literals (no
  hostname resolution yet). `rontolisp:fetch` and the tcp functions can be
  combined in one component, and tcp works inside a
  `rontolisp:http-handler` (serve) component. In an async body a pending
  `tcp-accept` or socket read suspends only its own task — other tasks (a
  `rontolisp:wait-for` timer, another request) keep running. See the
  [TCP sockets guide](tcp-sockets.md) for the full API.
- The compiled Lisp otherwise behaves identically to the Preview 1 output
  for the supported features. Serving incoming HTTP
  (`rontolisp:http-handler`) also compiles to a component, but a different
  kind (exporting `wasi:http/handler@0.3.0`) run under `wasmtime serve` —
  see the [HTTP handler guide](http-handler.md).

## Component-model Function Exports (`wasm-export`)

Under `--component`, a [`rontolisp:wasm-export`](wasm-host-boundary.md#exporting-lisp-functions)
becomes a **typed component-model export**, callable through the canonical
ABI with WAVE syntax (`wasmtime run --invoke 'name(args)'`, no experimental
warning) — and it co-exists with the `wasi:cli/run` command entry, so the
same component still runs as a command:

```lisp
(defun sumsquared (a b) (* (+ a b) (+ a b)))
(rontolisp:wasm-export 'sumsquared :params '(:int :int) :returns :int)
(print (sumsquared 10 10))
```

```bash
rontolisp sumsq.lisp --component -o sumsq.wasm
wasmtime run -W gc=y --invoke 'sumsquared(2, 3)' sumsq.wasm
# 25    (the export's return value, rendered by wasmtime)
wasmtime run -W gc=y sumsq.wasm
# 400    (the ordinary run entry executes the top-level program)
```

The two commands print different things: `--invoke` calls **only** the named
export — the top-level program (the `wasi:cli/run` entry) does not run —
and the `25` is wasmtime rendering the export's return value in WAVE syntax,
not output from `print`. The plain `run` executes the top-level program
instead, so the `400` is the output of `(print (sumsquared 10 10))`.

The typed signature (each integer designator under its own WIT name — `:s32`
→ `s32`, `:u32` → `u32`, … — plus `:float` → `f64`, `:bool` → `bool`,
`:string` → `string`, `:s-expr` → `string` carrying the printed s-expression
text, omitted `:returns` → no result) is visible to any component host, and
`:as` renames the component export just like the core one.

A `:string` boundary crosses as a real component-model `string` — no manual
pointer handling on either side. The host lowers the argument bytes into
linear memory and reads the result back out through the canonical ABI, and
the module frees the per-call allocations afterwards (a canonical
*post-return* function pops the bump allocator), so a resident instance
stays flat across repeated calls:

```lisp
;; greet.lisp
(defun greet (s) (concatenate 'string "Hello, " s))
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
```

```bash
rontolisp greet.lisp --component -o greet.wasm
wasmtime run -W gc=y --invoke 'greet("世界")' greet.wasm
# "Hello, 世界"
```

By default an export is lifted **synchronously**. Even so, I/O inside it
usually works: the asynchronous built-ins complete without blocking whenever
the host accepts immediately (stdout does), and only a host that reports
BLOCKED forces the blocking wait, which traps in a synchronous task with
"cannot block a synchronous task". Declare the export async with
**`:async t`** to lift it against an async function type instead — the same
async-typed lift as the `run` entry — and remove that residual risk.
`wasmtime --invoke` calls an async export exactly the same way:

```lisp
;; status.lisp
(rontolisp:async-defun fetch-status (url)
  (print "fetching")
  (getf (rontolisp:await (rontolisp:fetch url)) :status))
(rontolisp:wasm-export 'fetch-status :params '(:string) :returns :int :async t)
```

```bash
rontolisp status.lisp --component -o status.wasm
wasmtime run -W gc=y -W exceptions=y -S http=y \
  --invoke 'fetch-status("https://httpbin.ik.am/status/204")' status.wasm
# "fetching"
# 204
```

In the component's WIT-level contract an `:async t` export is an `async
func` (for example, jco types it as a Promise-returning function, while a
sync export stays a plain function). Sync and async exports mix freely in
one component, `:async` composes with every boundary type including
`:string`/`:s-expr`, and a program without `:async` exports produces
byte-identical output.

Current limitations of component exports:

- A **sync** (default) export can usually do I/O anyway (the asynchronous
  built-ins complete without blocking when the host accepts immediately);
  only a host that reports BLOCKED makes the blocking wait trap with
  "cannot block a synchronous task". Opt into `:async t` when the export
  prints, fetches, or otherwise does I/O to remove that residual risk;
  keep pure-compute exports sync.
- `:async` is meaningful only here: Preview 1 / `--no-wasi` core exports
  ignore it (the host provides I/O directly there), and `--no-gc
  --component` rejects it (the compact reactor component has no async
  adapter).
- jco (1.25.2) transpiles an `:async t` export and types it as async, but
  cannot call it yet — its support for the 0.3 async ABI is not implemented
  upstream (the same gap as calling the transpiled `run`). `wasmtime run
  --invoke` is the verified path for async exports; sync exports work on
  both.
- The export name must be a lower-kebab-case component-model name
  (`sum-squared`); for a Lisp name outside that grammar the compiler asks
  you to rename it with `:as`.
- Invoking an export does not run the program's top level first, so an
  export that reads a `defvar`/`defparameter` global would see it
  uninitialized (this matches the Preview 1 `--invoke` behavior).

For a pure-compute export kit, the compact
[`--no-gc --component`](wasm-nogc.md#compact-component-output---no-gc---component)
variant emits the same typed exports (plus `:long` → `s64`, minus
`:s-expr`) in a component of a few hundred bytes that needs no wasmtime
flags at all.
