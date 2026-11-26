# m11. Worker glue: `wasi:config/store` answered by the Worker's `env`

Difficulty: Medium

Asked by the user (2026-11-24), the second of three after m10 (read m10 and what it decided
first): a program reads its settings and secrets (a database URL, a password) through
`wasi:config/store` (`get`, `get-all`), answered by `wasmtime serve -S config -S config-var=..`
and wasmCloud on a component, and by the Worker's `env` (vars and secrets) through the glue.

- Measure first what `System.getenv` already gives each target (`%host-getenv`,
  `wasi:cli/environment`; a Worker has none) and decide what `wasi:config` adds over it.
- The Java side: an `@WitImport` interface where a program can reach it (`rontolisp-java-api/` or
  a library of its own; `.kb/java-frontend.md`, "`@WitImport`"), its JDK provider reading the
  environment or system properties.
- `defaultHost()` entries over `env`; an example's `wrangler.jsonc` with a var and a secret.
- Docs in `doc/en` and `doc/ja` together; extend `HostGlueEmitterTest`.

What m10 decided (`.kb/wasm-import.md`, "A WIT interface beyond the flat set"): on `--no-wasi`
an interface binding a member outside the flat set is imported through the canonical ABI and the
glue lifts it; `defaultHost()` holds a table of the members it answers (`HostGlueEmitter.KEYVALUE`).
Every canonical import may suspend unless `HostGlueEmitter.answersSynchronously` says otherwise,
and one reached from the load path is refused -- so `wasi:config`'s members, answered from `env`
without a promise, belong in that synchronous set, or a program reading its settings in `main`
is refused.

A consumer waiting for it (m44, 2026-11-25): `examples/cloudflare-workers/hello-jdbc/`'s
`src/index.js` hands the Hyperdrive connection string to the program as a request field,
there being no other way in; with this item the program reads it as a setting.
