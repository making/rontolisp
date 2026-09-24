# Bump every wasmtime pin to the latest release (49.0.0)

Difficulty: Medium

Latest release on 2026-09-24: v49.0.0 (crates.io `wasmtime` 49.0.0). Everything is on 47.0.3.

## Pins that move together

- `.github/workflows/wasmtime-image.yaml` `WASMTIME_VERSION`, `.github/docker/wasmtime/Dockerfile`
  `ARG WASMTIME_VERSION`, `ci.yaml` (both `version:`), `bench-report.yaml`, `size-report.yaml`.
- `rontolisp-native/Cargo.toml` (`wasmtime`, `wasmtime-wasi`, exact `=`), `Cargo.lock`, the
  version in `rontolisp-native/abi/src/lib.rs` (fingerprint), tests that spell the fingerprint.
- The local CLI (`~/.wasmtime/bin/wasmtime`) the E2Es run against.

## Check

- API/feature changes between 47 and 49 in the shim and the stub (Config, GC, exceptions,
  WASI P1); `rontolisp-native/build.sh --test`; regenerate fixtures if needed.
- Full `./mvnw test` against the new CLI; the native E2E.
- Whether the copying-collector defect in .todo/784 still reproduces on 49; update 784 and the
  `.kb/` files that cite wasmtime 47 behavior with what 49 measures.
