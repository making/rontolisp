# 409. `55af7714` traps a WASM component with `cast failure` where cl-postgres was fine

Difficulty: Medium

A WASI 0.3 component that quickloads `cl-postgres` and drives it through a rove
suite ran 183 assertions green at `7f496c5b` and dies at `55af7714` -- "Read
:defsystem-depends-on and asdf:component-version" -- with an UNCATCHABLE raw
trap 166 assertions in:

```
Error: failed to run main module `suite.wasm`
Caused by:
    0: error while executing at wasm backtrace:
       0:   0x998c - <unknown>!<wasm function 85>
       1:  0xfd8de - <unknown>!<wasm function 414>
       2:  0xe6475 - <unknown>!<wasm function 245>
       ...
    1: wasm trap: cast failure
```

**Bisected, one commit wide.** Same suite, same container, same wasmtime 47.0.3,
each jar built from source with `./mvnw clean package -DskipTests`:

| commit | result |
| --- | --- |
| `a220155a` unwind-protect cleanup values | 183 passed, 2 failed (`.todo/393`), clean exit |
| `7f496c5b` babel decoding-mapping protocol | 183 passed, 2 failed, clean exit |
| **`55af7714` :defsystem-depends-on** | **166 passed, then `cast failure`** |
| `55df035a` (develop HEAD) | 166 passed, then `cast failure` |

Deterministic -- the same built `suite.wasm` traps at the same assertion on
every run.

**WASM component only.** The interpreter (172/14) and the JVM (178/7) are
byte-for-byte unchanged across the same commits, so this is not a shared-lowering
bug. And because it is a raw trap rather than a signaled condition, no handler
sees it: the run ends, and a component that was the BEST backend for this library
is now the only unusable one.

## Reproduction

Needs Docker and `wasmtime` 37+; the suite is
[cl-postgres-client](https://github.com/making/cl-postgres-client), see
`.todo/408`.

```bash
# 1. the rontolisp under test, at whichever commit is being bisected
./mvnw --no-transfer-progress clean package -DskipTests
JAR="$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"

# 2. the suite
git clone https://github.com/making/cl-postgres-client
cd cl-postgres-client && git checkout e4dea9e
make RONTOLISP="java -jar $JAR" rontolisp-test-wasm
```

The target builds its own component and starts its own PostgreSQL container, so
the only variable is the jar. Green is 183 passed / 2 failed and a clean exit;
the regression is 166 passed and a trap.

It dies in `database-errors-pass-through`, on the assertion after

```lisp
(let ((condition (caught-condition (pgc:query-list client "select from where"))))
  (ok (typep condition 'pgc:database-error))
  (ok (search "select from where" (pgc:database-error-query condition))))
```

-- a PostgreSQL syntax error caught with `handler-bind` + `return-from`, then
read back. **That shape alone does not reproduce it**: the same catch driven
directly against `cl-postgres:exec-query`, and the same catch wrapped in a
one-`deftest` rove file, both compile `--component` and answer correctly at
HEAD. Whatever changed needs the rest of the suite ahead of it -- 166 assertions
across nine tests, each opening its own connection.

## The lead

`55af7714` makes `trivial-features` a built-in shim whose whole content is a
read-time ANNOUNCEMENT of `:unix` and `:little-endian`, and that announcement now
reaches the `#+`/`#-` reading of every system that depends on it -- which, for
`cl-postgres`, means `ironclad` and `alexandria` (ironclad reads `little-endian`
in `common.lisp`, `cipher.lisp` and the whole digest tree). So the compiled
module plausibly contains DIFFERENT source than it did at `7f496c5b`, taking a
branch whose wasm-GC lowering has a bad cast in it. Diffing the set of forms the
reader admits for `cl-postgres` and its dependency closure across the two commits
is the first thing to look at; a `cast failure` says a `ref.cast` saw a value of
a type the compiler had proved impossible, so the branch is likely one that
handles machine-layout bytes.

## Verification

- The reproduction above green on the component again, at 183 passed.
- A socket-free pin: whatever branch the announcement newly admits, compiled
  `--component` and run, in the per-backend compiler tests.
- Check the other announcement consumers the same way -- anything that reads
  `#+little-endian` and compiles to wasm.
