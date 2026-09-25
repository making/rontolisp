#!/usr/bin/env bash
# Builds the precompile shim and the runner stub for the host and lays them out as
#   <out>/am/ik/rontolisp/native/<os>-<arch>/{librlprecomp.so|librlprecomp.dylib, rlrun}
# (<out> defaults to target/resources), the classpath layout the Java side loads.
#
#   ./build.sh [--test] [out-dir]    --test: then run the workspace tests against the stub
#   ./build.sh --stub PLATFORM [out-dir]
#       only the runner stub of PLATFORM (<os>-<arch>), laid out the same way: what a
#       --native-target output for PLATFORM starts with. The host's platform, or on Linux
#       the other Linux architecture, cross-built with <arch>-linux-gnu-gcc (Debian/Ubuntu:
#       gcc-aarch64-linux-gnu / gcc-x86-64-linux-gnu) and the rustup target
#       <arch>-unknown-linux-musl
#   ./build.sh --is-static FILE      exit 0 iff FILE is an ELF executable with no interpreter
#   ./build.sh --maven BUILD REQUIRED
#       what pom.xml runs in generate-resources, with the values of the properties
#       rontolisp.native.build and rontolisp.native.required (true|false):
#       BUILD     build the pair when cargo is found; without cargo, warn and go on
#       REQUIRED  fail unless target/resources holds the host's pair afterwards (built
#                 here or put there by CI), and on Linux unless its stub is static
#
# The two are built by SEPARATE cargo invocations on purpose: cargo unifies a
# dependency's features across the packages of one invocation, so building them together
# would put the shim's Cranelift into the stub.
#
# On Linux the stub links musl STATICALLY (the Rust target <arch>-unknown-linux-musl,
# added through rustup when missing), so an output runs on any distribution: no glibc
# floor. Not static glibc, which made every output 0.83 MB bigger; on x86_64 the stub
# brings its own memcpy/memmove/memset, since musl's made the copying GC ~17% slower
# (.kb/native-output.md, "musl"). Without the target (no rustup) the stub falls back to
# linking glibc dynamically with a warning, except under REQUIRED.
set -euo pipefail
cd "$(dirname "$0")"

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

# Prints the stub's path once it is built. Every step ends in `|| return`: callers run it
# in a command substitution under `if`, where `set -e` does not apply.
build_pair() {
  local static_required=$1
  cargo build --locked --release -p rlprecomp >&2 || return
  build_stub "$arch" "$static_required"
}

# Builds the stub for <os>-$1 (on Linux, $1 may be the other architecture) and prints its
# path.
build_stub() {
  local stub_arch=$1 static_required=$2
  if [[ $os == linux ]]; then
    local cc=cc
    if [[ $stub_arch != "$arch" ]]; then
      cc=$stub_arch-linux-gnu-gcc
      command -v "$cc" >/dev/null || {
        echo "error: cross-building the $stub_arch stub needs $cc" >&2
        return 1
      }
    fi
    # --target keeps the flags off the build scripts and proc macros. The C compiler and
    # linker are named because cc-rs would look for <arch>-linux-musl-gcc, which the
    # static link does not need: the Rust target brings musl's CRT objects and libc.a.
    # relocation-model=static keeps both Linux stubs the same non-PIE static shape
    # (musl's default is static-pie). It does NOT make qemu-x86_64 on an aarch64 host
    # run a glibc binary: that QEMU (8.2.2) dies with an internal SIGSEGV (MAPERR
    # addr=0x20) as soon as the guest opens /proc/self/maps -- Rust's startup guard
    # setup does under glibc, and even a C program that only opens it dies the same way
    # (CI run 36086331612, cross_target_module... linux-x86_64, reproduced 2026-09-25).
    # The emulated runs for an architecture whose qemu cannot run are skipped by a probe
    # in precomp/tests/stub.rs, not by the link shape.
    local triple=$stub_arch-unknown-linux-musl
    local var
    var=$(tr 'a-z-' 'A-Z_' <<<"$triple")
    if ! have_target "$triple" || ! env "CARGO_TARGET_${var}_LINKER=$cc" "CC_${triple//-/_}=$cc" \
      "CARGO_TARGET_${var}_RUSTFLAGS=-C relocation-model=static" \
      cargo build --locked --profile release-runner -p rlrun --target "$triple" >&2; then
      if $static_required; then
        echo "error: the runner stub did not link statically against musl; install the" \
          "Rust target: rustup target add $triple" >&2
        return 1
      fi
      echo "WARNING: the runner stub did not link statically against musl (rustup target" \
        "add $triple); linking glibc dynamically, so --native outputs need this host's" \
        "glibc or newer" >&2
      triple=$stub_arch-unknown-linux-gnu
      var=$(tr 'a-z-' 'A-Z_' <<<"$triple")
      env "CARGO_TARGET_${var}_LINKER=$cc" "CC_${triple//-/_}=$cc" \
        cargo build --locked --profile release-runner -p rlrun --target "$triple" >&2 || return
    fi
    echo "target/$triple/release-runner/rlrun"
  else
    [[ $stub_arch == "$arch" ]] || {
      echo "error: on macOS the stub is built for the host's architecture only" >&2
      return 1
    }
    cargo build --locked --profile release-runner -p rlrun >&2 || return
    echo "target/release-runner/rlrun"
  fi
}

install_pair() {
  local stub=$1 dest=$2
  mkdir -p "$dest"
  cp "target/release/$lib" "$stub" "$dest/"
  ls -l "$dest"
}

# From the ELF program headers, not ldd: ldd calls every foreign-architecture file "not a
# dynamic executable" and, on aarch64, reports a static-pie as dynamic. Static means no
# PT_INTERP (a static-pie still has a DYNAMIC segment for its own relocations).
statically_linked() {
  command -v readelf >/dev/null || { echo "error: readelf (binutils) not found" >&2; return 2; }
  local headers
  headers=$(readelf -lW "$1") || return
  ! grep -q 'INTERP' <<<"$headers"
}

# Whether the standard library of Rust target $1 is installed; adds it through rustup
# when it is not and rustup is there.
have_target() {
  local libdir
  libdir=$(rustc --print target-libdir --target "$1") || return
  compgen -G "$libdir/libstd-*.rlib" >/dev/null && return
  command -v rustup >/dev/null || return
  echo "rontolisp-native: adding the Rust target $1 (the runner stub links musl statically)" >&2
  rustup target add "$1" >&2
}

find_cargo() {
  if ! command -v cargo >/dev/null && [[ -x $HOME/.cargo/bin/cargo ]]; then
    PATH=$HOME/.cargo/bin:$PATH
  fi
  command -v cargo >/dev/null
}

if [[ ${1:-} == --is-static ]]; then
  file=${2:?FILE}
  [[ $file == /* ]] || file=$OLDPWD/$file # relative to the caller, not to this directory
  statically_linked "$file"
  exit
fi

if [[ ${1:-} == --stub ]]; then
  platform=${2:?PLATFORM}
  out=${3:-target/resources}
  [[ $platform == "$os"-* ]] || { echo "error: a $os host cannot build the $platform stub" >&2; exit 1; }
  find_cargo || { echo "error: cargo (Rust >= 1.96) not found" >&2; exit 1; }
  stub=$(build_stub "${platform#*-}" true)
  if [[ $os == linux ]] && ! statically_linked "$stub"; then
    echo "error: $stub links glibc dynamically" >&2
    exit 1
  fi
  dest=$out/am/ik/rontolisp/native/$platform
  mkdir -p "$dest"
  cp "$stub" "$dest/"
  ls -l "$dest"
  exit 0
fi

if [[ ${1:-} == --maven ]]; then
  build=${2:?BUILD}
  required=${3:?REQUIRED}
  dest=target/resources/am/ik/rontolisp/native/$os-$arch
  if [[ $build == true ]]; then
    if find_cargo; then
      if ! stub=$(build_pair "$required"); then
        echo "error: rontolisp-native/build.sh failed; pass -Drontolisp.native.build=false" \
          "to build without --native for $os-$arch" >&2
        exit 1
      fi
      install_pair "$stub" "$dest"
    elif [[ ! -f $dest/$lib ]]; then
      echo "WARNING: cargo (Rust >= 1.96) not found: this build carries no --native" \
        "precompiler for $os-$arch, and --native will say it is not available" >&2
    fi
  fi
  if [[ $required == true ]]; then
    if [[ ! -f $dest/$lib || ! -f $dest/rlrun ]]; then
      echo "error: -Drontolisp.native.required=true but rontolisp-native/$dest has no" \
        "shim and stub (install Rust >= 1.96 or put CI's artifact there)" >&2
      exit 1
    fi
    if [[ $os == linux ]] && ! statically_linked "$dest/rlrun"; then
      echo "error: -Drontolisp.native.required=true but rontolisp-native/$dest/rlrun" \
        "links glibc dynamically" >&2
      exit 1
    fi
    echo "rontolisp-native: $os-$arch pair present in $dest"
  fi
  exit 0
fi

run_tests=false
if [[ ${1:-} == --test ]]; then run_tests=true; shift; fi
out=${1:-target/resources}

stub=$(build_pair false)
install_pair "$stub" "$out/am/ik/rontolisp/native/$os-$arch"

if $run_tests; then
  ./build-sh-test.sh
  RLNATIVE_STUB=$PWD/$stub cargo test --locked --workspace
fi
