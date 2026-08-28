# Benchmark report

How fast rontolisp is next to the other Common Lisp implementations, measured
rather than assumed.

Ten portable ANSI Common Lisp programs, each run on every implementation
installed on the machine -- SBCL, ECL, ABCL, and rontolisp's interpreter, JVM
backend and wasm backend. One script measures everything,
[`results/`](results) holds what it produced, and
[`.github/workflows/bench-report.yaml`](../.github/workflows/bench-report.yaml)
re-runs it **on request only** and commits the diff.

By hand rather than on a schedule, unlike [`size-report/`](../size-report): a
size is the same on any machine, a timing is not. Two runs on differently loaded
GitHub runners differ by more than most real changes do, so a nightly job would
commit noise every night. Trigger it after a change that is expected to move the
numbers, and read the run-to-run difference as meaningful only past ~10%.

## The report

| File | What it holds |
| --- | --- |
| [`results/benchmarks.md`](results/benchmarks.md) | Run time, run time relative to SBCL, and build time, per benchmark per implementation |
| [`results/benchmarks.json`](results/benchmarks.json) | The same numbers, machine-readable |

The report explains itself: what each benchmark measures, how to read the
numbers and what the comparison is worth sit below the tables in the report, not
here, so the explanation travels with the numbers. That prose lives in
[`notes/benchmarks.md`](notes/benchmarks.md), appended verbatim by `measure.sh`
every time it regenerates `results/`. Edit `notes/`, never `results/`.

## Running it

```bash
./measure.sh                      # every implementation found, rewrite results/
./measure.sh sbcl rontolisp-jvm   # only those implementations, print only
./measure.sh clean                # remove out/
```

Only a full run rewrites `results/`. A run narrowed by argument or by
`BENCH_ONLY` prints its numbers and leaves the files alone: narrowing is for
iterating, and iterating should not cost the last complete measurement.

| Variable | Default | Meaning |
| --- | --- | --- |
| `RONTOLISP` | -- | An explicit compiler path, instead of the search below |
| `BENCH_REPS` | 3 | Runs per cell; the FASTEST is reported |
| `BENCH_TIMEOUT` | 120 | Seconds one run may take before the cell reads `timeout` |
| `BENCH_ONLY` | -- | Comma-separated benchmark names, to narrow a run while iterating |

It uses `target/rontolisp` when the GraalVM native binary is built and the
executable jar otherwise. Every other implementation is whatever is on `PATH`;
one that is missing is reported as `-` in its column rather than dropped, so the
report can be read for what it did not measure as well.

A full run is ten benchmarks times six implementations times three repetitions,
which is on the order of twenty minutes -- most of it the rontolisp interpreter
and the cells that time out. `BENCH_REPS=1` with `BENCH_ONLY=...` is the way to
iterate.

## Installing the other implementations

```bash
sudo apt-get install -y sbcl ecl abcl   # Debian / Ubuntu
brew install sbcl ecl abcl              # macOS
```

ECL's `compile-file` shells out to the system C compiler, so it also needs a
working `cc`. ABCL runs on the JVM already on the machine.
