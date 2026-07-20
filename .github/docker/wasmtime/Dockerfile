# Prebuilt image for the WASM integration tests: a Debian base with a pinned
# wasmtime on PATH. Published to GHCR by .github/workflows/wasmtime-image.yaml so
# CI (and local `./mvnw test`) pull it instead of downloading and installing
# wasmtime on every run -- the on-the-fly install was the slow step that made the
# Unit Tests job look stuck.
#
# The pin must stay >= 46: the `--component` (WASI 0.3) tests need wasmtime 46+.
# Bump WASMTIME_VERSION here and the tag in WasmtimeSupport.IMAGE together.
FROM debian:bookworm-slim

ARG WASMTIME_VERSION=v46.0.1

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
