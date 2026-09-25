//! Embeds a precompiled module in a macOS runner stub and signs the result ad hoc.
//!
//! The stub reserves the section `rlabi::payload::{SEGMENT, SECTION}` in a segment the
//! linker places last before `__LINKEDIT`. [`embed`] writes the module into that section,
//! grows the segment to hold it, moves `__LINKEDIT` (and every load command that points
//! into it) behind, and replaces the linker's ad-hoc signature with one over the new
//! image: a `SuperBlob` holding one `CodeDirectory` of SHA-256 hashes of each 4 KiB page,
//! flags `adhoc | linker-signed` -- the shape `ld` itself writes, which `codesign -v
//! --strict` accepts. No segment is added, so segment indices (which the dyld binding
//! opcodes and chained fixups refer to) stay as the linker wrote them. Pure byte work: an
//! output for macOS is written on any host, with no `codesign`.

use rlabi::payload;
use sha2::{Digest, Sha256};

const MH_MAGIC_64: u32 = 0xfeed_facf;
const MH_EXECUTE: u32 = 2;
const HEADER_LEN: usize = 32;

const LC_SEGMENT_64: u32 = 0x19;
const LC_SYMTAB: u32 = 0x2;
const LC_DYSYMTAB: u32 = 0xb;
const LC_DYLD_INFO: u32 = 0x22;
const LC_DYLD_INFO_ONLY: u32 = 0x8000_0022;
const LC_CODE_SIGNATURE: u32 = 0x1d;

/// `linkedit_data_command`s other than the signature: `dataoff` points into `__LINKEDIT`.
const LINKEDIT_DATA: &[u32] = &[
    0x1e,        // LC_SEGMENT_SPLIT_INFO
    0x26,        // LC_FUNCTION_STARTS
    0x29,        // LC_DATA_IN_CODE
    0x2b,        // LC_DYLIB_CODE_SIGN_DRS
    0x2e,        // LC_LINKER_OPTIMIZATION_HINT
    0x36,        // LC_ATOM_INFO
    0x8000_0033, // LC_DYLD_EXPORTS_TRIE
    0x8000_0034, // LC_DYLD_CHAINED_FIXUPS
];

/// Load commands that hold no file offset into `__LINKEDIT`. Any command in neither list
/// is refused: moving `__LINKEDIT` under one that points into it would corrupt the output.
const NO_LINKEDIT_OFFSET: &[u32] = &[
    0xc,         // LC_LOAD_DYLIB
    0xe,         // LC_LOAD_DYLINKER
    0x1b,        // LC_UUID
    0x24,        // LC_VERSION_MIN_MACOSX
    0x2a,        // LC_SOURCE_VERSION
    0x2c,        // LC_ENCRYPTION_INFO_64 (its range is in __TEXT)
    0x2d,        // LC_LINKER_OPTION
    0x32,        // LC_BUILD_VERSION
    0x8000_0018, // LC_LOAD_WEAK_DYLIB
    0x8000_001c, // LC_RPATH
    0x8000_001f, // LC_REEXPORT_DYLIB
    0x8000_0028, // LC_MAIN (entryoff is in __TEXT)
];

/// Segment file offsets and addresses: 16 KiB, the arm64 page (and a multiple of x86_64's).
const SEGMENT_ALIGN: u64 = 0x4000;
/// The code signature's page: 4 KiB on both architectures, as `ld` signs.
const PAGE_SHIFT: u8 = 12;
const PAGE: usize = 1 << PAGE_SHIFT;

const CSMAGIC_EMBEDDED_SIGNATURE: u32 = 0xfade_0cc0;
const CSMAGIC_CODEDIRECTORY: u32 = 0xfade_0c02;
const CSSLOT_CODEDIRECTORY: u32 = 0;
/// Adds `execSegBase` / `execSegLimit` / `execSegFlags`, which arm64 requires.
const CD_VERSION: u32 = 0x20400;
const CS_ADHOC: u32 = 0x2;
const CS_LINKER_SIGNED: u32 = 0x2_0000;
const CS_HASHTYPE_SHA256: u8 = 2;
const CS_EXECSEG_MAIN_BINARY: u64 = 0x1;
const CD_HEADER_LEN: usize = 88;
const SUPERBLOB_HEADER_LEN: usize = 12 + 8;
const HASH_LEN: usize = 32;

/// Whether `stub` is a 64-bit little-endian Mach-O image.
pub fn is_macho(stub: &[u8]) -> bool {
    stub.len() >= 4 && u32_at(stub, 0) == MH_MAGIC_64
}

struct Segment {
    /// Offset of the `segment_command_64` in the image.
    cmd: usize,
    name: String,
    vmaddr: u64,
    vmsize: u64,
    fileoff: u64,
    filesize: u64,
    nsects: u32,
}

/// The executable that runs `module` under the macOS runner `stub`.
pub fn embed(stub: &[u8], module: &[u8]) -> Result<Vec<u8>, String> {
    if !is_macho(stub) || stub.len() < HEADER_LEN {
        return Err("the stub is not a 64-bit Mach-O image".into());
    }
    if u32_at(stub, 12) != MH_EXECUTE {
        return Err("the stub is not a Mach-O executable".into());
    }
    let ncmds = u32_at(stub, 16) as usize;
    let sizeofcmds = u32_at(stub, 20) as usize;
    if HEADER_LEN + sizeofcmds > stub.len() {
        return Err("truncated Mach-O load commands".into());
    }

    let mut segments = Vec::new();
    // Positions of u32 file offsets that point into __LINKEDIT.
    let mut linkedit_offsets = Vec::new();
    let mut signature_cmd = None;
    let mut at = HEADER_LEN;
    for _ in 0..ncmds {
        if at + 8 > HEADER_LEN + sizeofcmds {
            return Err("truncated Mach-O load command".into());
        }
        let cmd = u32_at(stub, at);
        let size = u32_at(stub, at + 4) as usize;
        if size < 8 || at + size > HEADER_LEN + sizeofcmds {
            return Err(format!("malformed Mach-O load command 0x{cmd:x}"));
        }
        let min = match cmd {
            LC_SEGMENT_64 => 72,
            LC_SYMTAB => 24,
            LC_DYSYMTAB => 80,
            LC_DYLD_INFO | LC_DYLD_INFO_ONLY => 48,
            LC_CODE_SIGNATURE => 16,
            c if LINKEDIT_DATA.contains(&c) => 16,
            _ => 8,
        };
        if size < min {
            return Err(format!("malformed Mach-O load command 0x{cmd:x}"));
        }
        match cmd {
            LC_SEGMENT_64 => segments.push(Segment {
                cmd: at,
                name: name16(&stub[at + 8..at + 24]),
                vmaddr: u64_at(stub, at + 24),
                vmsize: u64_at(stub, at + 32),
                fileoff: u64_at(stub, at + 40),
                filesize: u64_at(stub, at + 48),
                nsects: u32_at(stub, at + 64),
            }),
            LC_SYMTAB => linkedit_offsets.extend([at + 8, at + 16]),
            LC_DYSYMTAB => linkedit_offsets.extend([32, 40, 48, 56, 64, 72].map(|f| at + f)),
            LC_DYLD_INFO | LC_DYLD_INFO_ONLY => linkedit_offsets.extend([8, 16, 24, 32, 40].map(|f| at + f)),
            LC_CODE_SIGNATURE => signature_cmd = Some(at),
            c if LINKEDIT_DATA.contains(&c) => linkedit_offsets.push(at + 8),
            c if NO_LINKEDIT_OFFSET.contains(&c) => {}
            c => return Err(format!("unsupported load command 0x{c:x} in the stub")),
        }
        at += size;
    }
    let signature_cmd = signature_cmd.ok_or("the stub carries no LC_CODE_SIGNATURE")?;
    let old_sigoff = u32_at(stub, signature_cmd + 8) as u64;

    let text = segments
        .iter()
        .find(|s| s.name == "__TEXT")
        .ok_or("the stub has no __TEXT segment")?;
    let (text_off, text_size) = (text.fileoff, text.filesize);
    let n = segments.len();
    if n < 2 || segments[n - 1].name != "__LINKEDIT" || segments[n - 2].name != payload::SEGMENT {
        return Err(format!(
            "the stub does not end with {} then __LINKEDIT; build it with rontolisp-native/build.sh",
            payload::SEGMENT
        ));
    }
    let (seg, linkedit) = (&segments[n - 2], &segments[n - 1]);
    if seg.nsects != 1 || u32_at(stub, seg.cmd + 4) < 72 + 80 {
        return Err(format!("{} holds {} sections, not one", payload::SEGMENT, seg.nsects));
    }
    let sect = seg.cmd + 72;
    if name16(&stub[sect..sect + 16]) != payload::SECTION {
        return Err(format!("{} has no {} section", payload::SEGMENT, payload::SECTION));
    }
    let sect_off = u32_at(stub, sect + 48) as u64;
    if seg.fileoff + seg.filesize != linkedit.fileoff
        || seg.vmaddr + seg.vmsize != linkedit.vmaddr
        || sect_off < seg.fileoff
        || old_sigoff < linkedit.fileoff
        || old_sigoff > stub.len() as u64
    {
        return Err("unexpected Mach-O layout around __LINKEDIT".into());
    }

    let contents = payload::section(module);
    let seg_size = align(sect_off - seg.fileoff + contents.len() as u64, SEGMENT_ALIGN);
    let new_linkedit_off = seg.fileoff + seg_size;
    let delta = new_linkedit_off - linkedit.fileoff;
    let vm_delta = (seg.vmaddr + seg_size) - linkedit.vmaddr;
    // The linker's signature goes; everything of __LINKEDIT before it moves by `delta`.
    let kept = &stub[linkedit.fileoff as usize..old_sigoff as usize];
    let sigoff = align(new_linkedit_off + kept.len() as u64, 16);
    let identifier = identifier(stub, old_sigoff as usize)?;
    let code_slots = (sigoff as usize).div_ceil(PAGE);
    let cd_len = CD_HEADER_LEN + identifier.len() + 1 + code_slots * HASH_LEN;
    let sig_len = SUPERBLOB_HEADER_LEN + cd_len;
    let end = sigoff + sig_len as u64;
    if end > u32::MAX as u64 {
        return Err(format!(
            "the output would be {end} bytes, beyond Mach-O's 32-bit file offsets"
        ));
    }

    let mut out = vec![0u8; end as usize];
    out[..seg.fileoff as usize].copy_from_slice(&stub[..seg.fileoff as usize]);
    out[sect_off as usize..sect_off as usize + contents.len()].copy_from_slice(&contents);
    let at = new_linkedit_off as usize;
    out[at..at + kept.len()].copy_from_slice(kept);

    // The payload segment and its section.
    put_u64(&mut out, seg.cmd + 32, seg_size);
    put_u64(&mut out, seg.cmd + 48, seg_size);
    put_u64(&mut out, sect + 40, contents.len() as u64);
    // __LINKEDIT and what points into it.
    let linkedit_size = end - new_linkedit_off;
    put_u64(&mut out, linkedit.cmd + 24, linkedit.vmaddr + vm_delta);
    put_u64(&mut out, linkedit.cmd + 32, align(linkedit_size, SEGMENT_ALIGN));
    put_u64(&mut out, linkedit.cmd + 40, new_linkedit_off);
    put_u64(&mut out, linkedit.cmd + 48, linkedit_size);
    for field in linkedit_offsets {
        let off = u32_at(stub, field) as u64;
        if off == 0 {
            continue;
        }
        if off < linkedit.fileoff || off > old_sigoff {
            return Err(format!("a load command points at 0x{off:x}, outside __LINKEDIT"));
        }
        put_u32(&mut out, field, (off + delta) as u32);
    }
    put_u32(&mut out, signature_cmd + 8, sigoff as u32);
    put_u32(&mut out, signature_cmd + 12, sig_len as u32);

    sign(&mut out, sigoff as usize, &identifier, text_off, text_size);
    Ok(out)
}

/// The identifier the linker's `CodeDirectory` names the stub by, NUL excluded.
fn identifier(stub: &[u8], sigoff: usize) -> Result<Vec<u8>, String> {
    let bad = || "the stub's code signature is not a linker's ad-hoc one".to_string();
    let blob = stub.get(sigoff..).ok_or_else(bad)?;
    if blob.len() < 12 || be32(blob, 0) != CSMAGIC_EMBEDDED_SIGNATURE {
        return Err(bad());
    }
    for i in 0..be32(blob, 8) as usize {
        let entry = 12 + 8 * i;
        if entry + 8 > blob.len() {
            return Err(bad());
        }
        if be32(blob, entry) != CSSLOT_CODEDIRECTORY {
            continue;
        }
        let cd = be32(blob, entry + 4) as usize;
        if cd + CD_HEADER_LEN > blob.len() || be32(blob, cd) != CSMAGIC_CODEDIRECTORY {
            return Err(bad());
        }
        let ident = blob.get(cd + be32(blob, cd + 20) as usize..).ok_or_else(bad)?;
        let len = ident.iter().position(|&b| b == 0).ok_or_else(bad)?;
        return Ok(ident[..len].to_vec());
    }
    Err(bad())
}

/// Writes the embedded signature at `sigoff` over `out[..sigoff]`, whose load commands
/// already describe it.
fn sign(out: &mut [u8], sigoff: usize, identifier: &[u8], text_off: u64, text_size: u64) {
    let code_slots = sigoff.div_ceil(PAGE);
    let ident_off = CD_HEADER_LEN;
    let hash_off = ident_off + identifier.len() + 1;
    let cd_len = hash_off + code_slots * HASH_LEN;

    let mut sig = Vec::with_capacity(SUPERBLOB_HEADER_LEN + cd_len);
    sig.extend_from_slice(&CSMAGIC_EMBEDDED_SIGNATURE.to_be_bytes());
    sig.extend_from_slice(&((SUPERBLOB_HEADER_LEN + cd_len) as u32).to_be_bytes());
    sig.extend_from_slice(&1u32.to_be_bytes());
    sig.extend_from_slice(&CSSLOT_CODEDIRECTORY.to_be_bytes());
    sig.extend_from_slice(&(SUPERBLOB_HEADER_LEN as u32).to_be_bytes());

    for word in [
        CSMAGIC_CODEDIRECTORY,
        cd_len as u32,
        CD_VERSION,
        CS_ADHOC | CS_LINKER_SIGNED,
        hash_off as u32,
        ident_off as u32,
        0, // nSpecialSlots: no Info.plist, requirements or entitlements to hash
        code_slots as u32,
        sigoff as u32, // codeLimit
    ] {
        sig.extend_from_slice(&word.to_be_bytes());
    }
    sig.extend_from_slice(&[HASH_LEN as u8, CS_HASHTYPE_SHA256, 0, PAGE_SHIFT]);
    sig.extend_from_slice(&[0; 4 * 4]); // spare2, scatterOffset, teamOffset, spare3
    sig.extend_from_slice(&0u64.to_be_bytes()); // codeLimit64: codeLimit fits
    sig.extend_from_slice(&text_off.to_be_bytes());
    sig.extend_from_slice(&text_size.to_be_bytes());
    sig.extend_from_slice(&CS_EXECSEG_MAIN_BINARY.to_be_bytes());
    debug_assert_eq!(sig.len(), SUPERBLOB_HEADER_LEN + CD_HEADER_LEN);
    sig.extend_from_slice(identifier);
    sig.push(0);
    for page in out[..sigoff].chunks(PAGE) {
        sig.extend_from_slice(&Sha256::digest(page));
    }
    out[sigoff..sigoff + sig.len()].copy_from_slice(&sig);
}

fn align(value: u64, to: u64) -> u64 {
    value.div_ceil(to) * to
}

fn name16(bytes: &[u8]) -> String {
    let end = bytes.iter().position(|&b| b == 0).unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).into_owned()
}

fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}

fn u64_at(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

fn be32(b: &[u8], at: usize) -> u32 {
    u32::from_be_bytes(b[at..at + 4].try_into().unwrap())
}

fn put_u32(b: &mut [u8], at: usize, v: u32) {
    b[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

fn put_u64(b: &mut [u8], at: usize, v: u64) {
    b[at..at + 8].copy_from_slice(&v.to_le_bytes());
}
