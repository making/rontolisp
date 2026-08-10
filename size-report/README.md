# Size report

How big a rontolisp artifact is, measured rather than remembered.

The numbers used to live in the example READMEs, where they went stale as soon
as the compiler changed. They live here instead: one script measures everything,
[`results/`](results) holds what it produced, and
[`.github/workflows/size-report.yaml`](../.github/workflows/size-report.yaml)
re-runs it weekly and commits the diff. Example READMEs link here; they quote no
byte counts of their own.

## The reports

| File | What it measures |
| --- | --- |
| [`results/wasm-flags.md`](results/wasm-flags.md) | Two micro programs across every size-relevant compiler flag -- what `--optimize`, `--component` and `--no-gc` are each worth |
| [`results/cloudflare-workers.md`](results/cloudflare-workers.md) | The [Cloudflare Worker](../examples/cloudflare-workers) modules, raw and gzipped -- what a web framework costs in a bundle |
| [`results/sizes.json`](results/sizes.json) | The same numbers, machine-readable |

Each report explains itself: what its family measures, how to read the numbers,
and the cross-language context sit below the table in the report, not here, so
the explanation travels with the numbers. That prose lives in
[`notes/`](notes) -- one file per report, appended verbatim by `measure.sh`
every time it regenerates `results/`. Edit `notes/`, never `results/`.

## Running it

```bash
./measure.sh            # both families, rewrite results/
./measure.sh wasm       # the flag matrix only (seconds)
./measure.sh workers    # the Worker modules only
./measure.sh clean      # remove out/
```

It uses `target/rontolisp` when the GraalVM native binary is built and the
executable jar otherwise (override with `RONTOLISP=/path/to/rontolisp`).
`wasmtime` is optional -- without it the micro programs are built and measured
but not run. `npx` is optional -- without it the jco-transpiled glue row is
skipped. The Worker builds `ql:quickload` clack / lack / tiny-routes / ningle,
so the first run needs network.
