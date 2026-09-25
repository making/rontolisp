//! What the precompile shim and the runner stub must agree on: the one wasmtime `Config`
//! both build, the fingerprint both report, and the layout of an executable.
//!
//! A precompiled module records the engine it was compiled for, and wasmtime refuses to
//! load it under any other: a different wasmtime version, collector, proposal set or GC
//! heap reservation is an error at start-up, not a slower run. Both halves therefore
//! build their engine here and nowhere else, and both report [`FINGERPRINT`] so that the
//! side assembling an executable can refuse a mismatched pair before writing it.

use wasmtime::{Collector, Config};

/// Expands to the wasmtime version the workspace pins (`Cargo.toml`, `=` requirement).
/// A test checks it against `Cargo.lock`, so the two cannot drift.
#[macro_export]
macro_rules! wasmtime_version {
    () => {
        "49.0.0"
    };
}

/// Expands to the fingerprint literal; see [`FINGERPRINT`].
#[macro_export]
macro_rules! fingerprint {
    () => {
        concat!(
            "rlnative-abi=3;wasmtime=",
            $crate::wasmtime_version!(),
            ";wasm=gc,function-references,exceptions,tail-call;collector=copying"
        )
    };
}

/// Everything that decides whether a module the shim precompiled loads in the stub: the
/// payload / C-ABI revision (`rlnative-abi`), the wasmtime version and the settings
/// [`config`] applies. Edit it together with `config`.
pub const FINGERPRINT: &str = fingerprint!();

/// What the side assembling an executable scans a runner stub for: the fingerprint
/// follows it, up to a NUL.
pub const STUB_MARKER_PREFIX: &str = "RLNATIVE-FINGERPRINT=";

/// [`STUB_MARKER_PREFIX`] + [`FINGERPRINT`] + NUL: the stub keeps this in its read-only data.
pub const STUB_MARKER: &str = concat!("RLNATIVE-FINGERPRINT=", fingerprint!(), "\0");

/// The engine configuration of every `--native` output. Copying collector, never DRC:
/// DRC ran the GC corpus 9x slower, and wasmtime 45's copying collector could not
/// satisfy the backend's 16 MiB heap pregrow (fixed in 47).
pub fn config() -> Config {
    let mut c = Config::new();
    c.wasm_gc(true)
        .wasm_function_references(true)
        .wasm_exceptions(true)
        .wasm_tail_call(true)
        .collector(Collector::Copying);
    c
}

/// The executable layouts. On Linux: `stub ++ module ++ u64-le(module length) ++ MAGIC`,
/// the module appended to the ELF stub and found from the file's tail. On macOS the
/// module lives INSIDE the image, in the section [`SEGMENT`]`,`[`SECTION`] the stub
/// reserves at link time, as `u64-le(module length) ++ MAGIC ++ module`; the assembler
/// grows that segment, moves `__LINKEDIT` behind it and re-signs the image ad hoc, so the
/// code signature covers the module (`rlprecomp::macho`).
pub mod payload {
    /// Ends every Linux executable; follows the length in the macOS section header.
    pub const MAGIC: &[u8; 8] = b"RLNATIVE";
    /// Length field + magic: the Linux trailer, the macOS section header.
    pub const TRAILER_LEN: usize = 16;
    /// The Mach-O segment the macOS stub reserves for the module; the last before
    /// `__LINKEDIT`.
    pub const SEGMENT: &str = "__RLPAYLOAD";
    /// The one section of [`SEGMENT`].
    pub const SECTION: &str = "__payload";
    /// What the macOS stub's section holds before a module is embedded: a header naming
    /// an empty module. Not all zeros, which the linker could turn into zero-fill that
    /// takes no file space.
    pub const EMPTY_SECTION: [u8; TRAILER_LEN] = *b"\0\0\0\0\0\0\0\0RLNATIVE";

    /// The Linux executable for `stub` running `module`.
    pub fn append(stub: &[u8], module: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(stub.len() + module.len() + TRAILER_LEN);
        out.extend_from_slice(stub);
        out.extend_from_slice(module);
        out.extend_from_slice(&(module.len() as u64).to_le_bytes());
        out.extend_from_slice(MAGIC);
        out
    }

    /// Reads a trailer (the file's last [`TRAILER_LEN`] bytes) of a file of `file_len`
    /// bytes: the module's offset and length, or why there is no payload.
    pub fn locate(trailer: &[u8; TRAILER_LEN], file_len: u64) -> Result<(u64, u64), String> {
        if &trailer[8..] != MAGIC {
            return Err("no module appended to this executable (missing RLNATIVE trailer)".into());
        }
        let len = u64::from_le_bytes(trailer[..8].try_into().unwrap());
        let room = file_len.saturating_sub(TRAILER_LEN as u64);
        if len > room {
            return Err(format!(
                "corrupt trailer: module length {len} exceeds the {room} bytes before it"
            ));
        }
        Ok((room - len, len))
    }

    /// The contents of the macOS payload section: `header ++ module`.
    pub fn section(module: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(TRAILER_LEN + module.len());
        out.extend_from_slice(&(module.len() as u64).to_le_bytes());
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(module);
        out
    }

    /// The module a macOS payload section holds, or why it holds none.
    pub fn embedded(section: &[u8]) -> Result<&[u8], String> {
        if section.len() < TRAILER_LEN || &section[8..TRAILER_LEN] != MAGIC {
            return Err(format!("corrupt {SEGMENT},{SECTION} section (no RLNATIVE header)"));
        }
        let len = u64::from_le_bytes(section[..8].try_into().unwrap());
        let room = (section.len() - TRAILER_LEN) as u64;
        if len == 0 {
            return Err("no module embedded in this executable (empty RLNATIVE section)".into());
        }
        if len > room {
            return Err(format!(
                "corrupt {SEGMENT},{SECTION} section: module length {len} exceeds its {room} bytes"
            ));
        }
        Ok(&section[TRAILER_LEN..TRAILER_LEN + len as usize])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pinned_version_matches_the_lock_file() {
        let lock = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../Cargo.lock"))
            .expect("Cargo.lock beside the workspace manifest");
        let entry = format!("name = \"wasmtime\"\nversion = \"{}\"\n", wasmtime_version!());
        assert!(
            lock.contains(&entry),
            "Cargo.lock does not resolve wasmtime {}",
            wasmtime_version!()
        );
    }

    #[test]
    fn stub_marker_is_prefix_fingerprint_nul() {
        assert_eq!(STUB_MARKER, format!("{STUB_MARKER_PREFIX}{FINGERPRINT}\0"));
    }

    #[test]
    fn engine_accepts_the_config() {
        wasmtime::Engine::new(&config()).expect("engine");
    }

    #[test]
    fn payload_round_trips() {
        let exe = payload::append(b"STUB", b"module");
        let trailer: [u8; 16] = exe[exe.len() - 16..].try_into().unwrap();
        let (off, len) = payload::locate(&trailer, exe.len() as u64).unwrap();
        assert_eq!(&exe[off as usize..(off + len) as usize], b"module");
        assert_eq!(off, 4);
    }

    #[test]
    fn payload_refuses_a_bare_stub_and_an_oversized_length() {
        assert!(payload::locate(&[0; 16], 100).unwrap_err().contains("no module"));
        let mut t = [0u8; 16];
        t[..8].copy_from_slice(&1000u64.to_le_bytes());
        t[8..].copy_from_slice(payload::MAGIC);
        assert!(payload::locate(&t, 100).unwrap_err().contains("corrupt"));
    }

    #[test]
    fn section_round_trips_and_the_empty_one_names_no_module() {
        assert_eq!(payload::embedded(&payload::section(b"module")).unwrap(), b"module");
        // Room past the module (the segment is page-rounded) is not part of it.
        let mut padded = payload::section(b"module");
        padded.extend_from_slice(&[0; 100]);
        assert_eq!(payload::embedded(&padded).unwrap(), b"module");
        assert!(
            payload::embedded(&payload::EMPTY_SECTION)
                .unwrap_err()
                .contains("no module")
        );
        assert!(payload::embedded(&[0; 16]).unwrap_err().contains("corrupt"));
        let mut short = payload::section(b"module");
        short.truncate(20);
        assert!(payload::embedded(&short).unwrap_err().contains("exceeds"));
    }
}
