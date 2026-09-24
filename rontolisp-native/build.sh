#!/usr/bin/env bash
# Builds the precompile shim and the runner stub for the host and lays them out as
#   <out>/am/ik/rontolisp/native/<os>-<arch>/{librlprecomp.so|librlprecomp.dylib, rlrun}
# (<out> defaults to target/resources), the classpath layout the Java side loads.
#
#   ./build.sh [--test] [out-dir]    --test: then run the workspace tests against the stub
#
# The two are built by SEPARATE cargo invocations on purpose: cargo unifies a
# dependency's features across the packages of one invocation, so building them together
# would put the shim's Cranelift into the stub.
set -euo pipefail
cd "$(dirname "$0")"
run_tests=false
if [[ ${1:-} == --test ]]; then run_tests=true; shift; fi
out=${1:-target/resources}

case "$(uname -s)" in
  Linux) os=linux; lib=librlprecomp.so ;;
  Darwin) os=macos; lib=librlprecomp.dylib ;;
  *) echo "unsupported host OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x86_64 ;;
  arm64 | aarch64) arch=aarch64 ;;
  *) echo "unsupported host architecture: $(uname -m)" >&2; exit 1 ;;
esac

cargo build --locked --release -p rlprecomp
cargo build --locked --profile release-runner -p rlrun

dest=$out/am/ik/rontolisp/native/$os-$arch
mkdir -p "$dest"
cp "target/release/$lib" "target/release-runner/rlrun" "$dest/"
ls -l "$dest"

if $run_tests; then
  RLNATIVE_STUB=$PWD/target/release-runner/rlrun cargo test --locked --workspace
fi
