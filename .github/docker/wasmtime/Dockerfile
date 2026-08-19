# Prebuilt image for the WASM integration tests: a Debian base with a pinned
# wasmtime on PATH. Published to GHCR by .github/workflows/wasmtime-image.yaml so
# CI (and local `./mvnw test`) pull it instead of downloading and installing
# wasmtime on every run -- the on-the-fly install was the slow step that made the
# Unit Tests job look stuck.
#
# The pin must stay >= 47: the `--component` (WASI 0.3) tests need wasmtime 46+ to
# run at all, and 47+ additionally inlines final-type casts, without which serve
# throughput collapses under concurrency (.kb/wasm-gc-final-types.md).
# Bump WASMTIME_VERSION here, the env of the same name in
# .github/workflows/wasmtime-image.yaml (which overrides this default), and the `version:`
# .github/workflows/ci.yaml installs on the host -- all three, then re-run the image
# workflow. WasmtimeSupport.IMAGE tracks :latest and is not one of them.
FROM debian:bookworm-slim

# Keep in step with WASMTIME_VERSION in .github/workflows/wasmtime-image.yaml, which
# overrides this default when the published image is built, and with the pin the CI test
# job installs on the host for WasmLispCompilerIntegrationTest. This default had drifted
# to a v46.0.2 that was never released, so a local `docker build` of this file 404'd.
ARG WASMTIME_VERSION=v47.0.3

# curl and ca-certificates stay installed at run time: an http-handler test curls
# the served component under `wasmtime serve`.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
 && arch="$(uname -m)" \
 && case "$arch" in \
      x86_64) wt_arch=x86_64 ;; \
      aarch64|arm64) wt_arch=aarch64 ;; \
      *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac \
 && curl -sSfL "https://github.com/bytecodealliance/wasmtime/releases/download/${WASMTIME_VERSION}/wasmtime-${WASMTIME_VERSION}-${wt_arch}-linux.tar.xz" -o /tmp/wasmtime.tar.xz \
 && tar -xf /tmp/wasmtime.tar.xz -C /tmp \
 && mv "/tmp/wasmtime-${WASMTIME_VERSION}-${wt_arch}-linux/wasmtime" /usr/local/bin/wasmtime \
 && rm -rf /tmp/wasmtime* \
 && wasmtime --version \
 && rm -rf /var/lib/apt/lists/*

CMD ["sleep", "infinity"]
