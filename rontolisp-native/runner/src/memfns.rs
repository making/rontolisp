//! `memcpy`, `memmove` and `memset` for the x86_64 Linux stub, which links musl statically.
//!
//! musl's own x86_64 routines start every copy with `rep movsq`, whose start-up cost
//! dominates the small copies the copying collector makes (one per surviving object, most
//! under 64 bytes): with them the stub ran a GC-heavy program 16-18% slower than against
//! glibc (`.kb/native-output.md`, Traps, "musl"). These follow glibc's shape instead:
//! up to 128 bytes, every load before any store, of overlapping 1/2/4/8/16-byte pieces
//! from both ends, so one branchy path serves `memcpy` and `memmove` alike; above, a
//! 64-byte loop in whichever direction the overlap needs, with the far end loaded before
//! the loop; from 2 KiB, `rep movsb` / `rep stosb` when copying forwards is safe. SSE2
//! only: the x86_64 baseline, so the stub runs on every x86_64 CPU.
//!
//! Defined as `rl_memmove` / `rl_memset`, so that the tests reach them on a glibc host
//! too; only a musl build also exports them as `memcpy`, `memmove` and `memset`, which
//! then keeps libc.a's members out of the static link. Module assembly, not Rust: LLVM
//! may turn a Rust copy loop back into a call to `memcpy`.

use std::arch::global_asm;

/// Copies of at least this many bytes use `rep movsb` / `rep stosb` (forwards only).
pub const REP_THRESHOLD: usize = 2048;

global_asm!(
    ".text",
    ".p2align 5",
    ".globl rl_memmove",
    ".hidden rl_memmove",
    ".type rl_memmove,@function",
    // rdi = dst, rsi = src, rdx = n; returns dst.
    "rl_memmove:",
    "    mov rax, rdi",
    "    cmp rdx, 16",
    "    jb 10f",
    "    cmp rdx, 32",
    "    ja 20f",
    // 16..=32
    "    movups xmm0, [rsi]",
    "    movups xmm1, [rsi + rdx - 16]",
    "    movups [rdi], xmm0",
    "    movups [rdi + rdx - 16], xmm1",
    "    ret",
    // 0..16
    "10:",
    "    cmp edx, 8",
    "    jb 11f",
    "    mov rcx, [rsi]",
    "    mov r8, [rsi + rdx - 8]",
    "    mov [rdi], rcx",
    "    mov [rdi + rdx - 8], r8",
    "    ret",
    "11:",
    "    cmp edx, 4",
    "    jb 12f",
    "    mov ecx, [rsi]",
    "    mov r8d, [rsi + rdx - 4]",
    "    mov [rdi], ecx",
    "    mov [rdi + rdx - 4], r8d",
    "    ret",
    "12:",
    "    test edx, edx",
    "    je 13f",
    "    movzx ecx, byte ptr [rsi]",
    "    cmp edx, 2",
    "    jb 14f",
    "    movzx r8d, word ptr [rsi + rdx - 2]",
    "    mov [rdi + rdx - 2], r8w",
    "14:",
    "    mov [rdi], cl",
    "13:",
    "    ret",
    // 33..=64
    "20:",
    "    cmp rdx, 64",
    "    ja 30f",
    "    movups xmm0, [rsi]",
    "    movups xmm1, [rsi + 16]",
    "    movups xmm2, [rsi + rdx - 32]",
    "    movups xmm3, [rsi + rdx - 16]",
    "    movups [rdi], xmm0",
    "    movups [rdi + 16], xmm1",
    "    movups [rdi + rdx - 32], xmm2",
    "    movups [rdi + rdx - 16], xmm3",
    "    ret",
    // 65..=128
    "30:",
    "    cmp rdx, 128",
    "    ja 40f",
    "    movups xmm0, [rsi]",
    "    movups xmm1, [rsi + 16]",
    "    movups xmm2, [rsi + 32]",
    "    movups xmm3, [rsi + 48]",
    "    movups xmm4, [rsi + rdx - 64]",
    "    movups xmm5, [rsi + rdx - 48]",
    "    movups xmm6, [rsi + rdx - 32]",
    "    movups xmm7, [rsi + rdx - 16]",
    "    movups [rdi], xmm0",
    "    movups [rdi + 16], xmm1",
    "    movups [rdi + 32], xmm2",
    "    movups [rdi + 48], xmm3",
    "    movups [rdi + rdx - 64], xmm4",
    "    movups [rdi + rdx - 48], xmm5",
    "    movups [rdi + rdx - 32], xmm6",
    "    movups [rdi + rdx - 16], xmm7",
    "    ret",
    // > 128. Backwards when dst lies inside (src, src + n): (dst - src) mod 2^64 < n.
    "40:",
    "    mov rcx, rdi",
    "    sub rcx, rsi",
    "    cmp rcx, rdx",
    "    jb 50f",
    "    cmp rdx, {rep}",
    "    jae 45f",
    // Forwards: the last 64 source bytes first (a dst below src may overwrite them), then
    // 64-byte blocks until the tail's destination, then the tail.
    "    movups xmm4, [rsi + rdx - 64]",
    "    movups xmm5, [rsi + rdx - 48]",
    "    movups xmm6, [rsi + rdx - 32]",
    "    movups xmm7, [rsi + rdx - 16]",
    "    lea r8, [rdi + rdx - 64]",
    "    mov rcx, rdi",
    "41:",
    "    movups xmm0, [rsi]",
    "    movups xmm1, [rsi + 16]",
    "    movups xmm2, [rsi + 32]",
    "    movups xmm3, [rsi + 48]",
    "    movups [rcx], xmm0",
    "    movups [rcx + 16], xmm1",
    "    movups [rcx + 32], xmm2",
    "    movups [rcx + 48], xmm3",
    "    add rsi, 64",
    "    add rcx, 64",
    "    cmp rcx, r8",
    "    jb 41b",
    "    movups [r8], xmm4",
    "    movups [r8 + 16], xmm5",
    "    movups [r8 + 32], xmm6",
    "    movups [r8 + 48], xmm7",
    "    ret",
    "45:",
    "    mov rcx, rdx",
    "    rep movsb",
    "    ret",
    // Backwards: the first 64 source bytes first, then 64-byte blocks from the end down to
    // the first 64 destination bytes, then the head.
    "50:",
    "    movups xmm4, [rsi]",
    "    movups xmm5, [rsi + 16]",
    "    movups xmm6, [rsi + 32]",
    "    movups xmm7, [rsi + 48]",
    "    lea rcx, [rdi + rdx]",
    "    add rsi, rdx",
    "    lea r8, [rdi + 64]",
    "51:",
    "    movups xmm0, [rsi - 16]",
    "    movups xmm1, [rsi - 32]",
    "    movups xmm2, [rsi - 48]",
    "    movups xmm3, [rsi - 64]",
    "    movups [rcx - 16], xmm0",
    "    movups [rcx - 32], xmm1",
    "    movups [rcx - 48], xmm2",
    "    movups [rcx - 64], xmm3",
    "    sub rsi, 64",
    "    sub rcx, 64",
    "    cmp rcx, r8",
    "    ja 51b",
    "    movups [rdi], xmm4",
    "    movups [rdi + 16], xmm5",
    "    movups [rdi + 32], xmm6",
    "    movups [rdi + 48], xmm7",
    "    ret",
    ".size rl_memmove, . - rl_memmove",
    //
    ".p2align 5",
    ".globl rl_memset",
    ".hidden rl_memset",
    ".type rl_memset,@function",
    // rdi = dst, esi = byte, rdx = n; returns dst.
    "rl_memset:",
    "    mov rax, rdi",
    "    movzx esi, sil",
    "    movabs rcx, 0x0101010101010101",
    "    imul rsi, rcx",
    "    cmp rdx, 16",
    "    jb 10f",
    "    movq xmm0, rsi",
    "    punpcklqdq xmm0, xmm0",
    "    cmp rdx, 32",
    "    ja 20f",
    "    movups [rdi], xmm0",
    "    movups [rdi + rdx - 16], xmm0",
    "    ret",
    "10:",
    "    cmp edx, 8",
    "    jb 11f",
    "    mov [rdi], rsi",
    "    mov [rdi + rdx - 8], rsi",
    "    ret",
    "11:",
    "    cmp edx, 4",
    "    jb 12f",
    "    mov [rdi], esi",
    "    mov [rdi + rdx - 4], esi",
    "    ret",
    "12:",
    "    test edx, edx",
    "    je 13f",
    "    mov [rdi], sil",
    "    cmp edx, 2",
    "    jb 13f",
    "    mov [rdi + rdx - 2], si",
    "13:",
    "    ret",
    "20:",
    "    cmp rdx, 64",
    "    ja 30f",
    "    movups [rdi], xmm0",
    "    movups [rdi + 16], xmm0",
    "    movups [rdi + rdx - 32], xmm0",
    "    movups [rdi + rdx - 16], xmm0",
    "    ret",
    "30:",
    "    cmp rdx, {rep}",
    "    jae 45f",
    // 64-byte blocks until the last 64 bytes, which are written unaligned at the end.
    "    lea r8, [rdi + rdx - 64]",
    "    mov rcx, rdi",
    "31:",
    "    movups [rcx], xmm0",
    "    movups [rcx + 16], xmm0",
    "    movups [rcx + 32], xmm0",
    "    movups [rcx + 48], xmm0",
    "    add rcx, 64",
    "    cmp rcx, r8",
    "    jb 31b",
    "    movups [r8], xmm0",
    "    movups [r8 + 16], xmm0",
    "    movups [r8 + 32], xmm0",
    "    movups [r8 + 48], xmm0",
    "    ret",
    "45:",
    "    mov r8, rdi",
    "    mov eax, esi",
    "    mov rcx, rdx",
    "    rep stosb",
    "    mov rax, r8",
    "    ret",
    ".size rl_memset, . - rl_memset",
    rep = const REP_THRESHOLD,
);

// The C names, for the musl link only: a glibc build (the tests) keeps glibc's.
#[cfg(target_env = "musl")]
global_asm!(
    ".globl memcpy",
    ".type memcpy,@function",
    ".set memcpy, rl_memmove",
    ".globl memmove",
    ".type memmove,@function",
    ".set memmove, rl_memmove",
    ".globl memset",
    ".type memset,@function",
    ".set memset, rl_memset",
);

#[cfg(test)]
mod tests {
    use super::REP_THRESHOLD;

    unsafe extern "C" {
        fn rl_memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8;
        fn rl_memset(dst: *mut u8, c: i32, n: usize) -> *mut u8;
    }

    /// Every length up to 300 and a spread around the block and `rep` thresholds.
    fn lengths() -> Vec<usize> {
        let mut v: Vec<usize> = (0..=300).collect();
        for base in [511, 512, 1000, REP_THRESHOLD, 4096, 65536] {
            v.extend(base.saturating_sub(3)..=base + 3);
        }
        v
    }

    /// A buffer of distinct-ish bytes, so a misplaced byte shows.
    fn pattern(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i * 7 + i / 251 + 1) as u8).collect()
    }

    #[test]
    fn copies_between_disjoint_buffers_at_every_alignment() {
        for n in lengths() {
            let aligns = if n <= 300 { 0..16 } else { 0..4 };
            for sa in aligns.clone() {
                for da in aligns.clone() {
                    let src = pattern(n + 64);
                    let mut dst = vec![0xAAu8; n + 64];
                    let mut expected = dst.clone();
                    expected[da + 8..da + 8 + n].copy_from_slice(&src[sa..sa + n]);
                    let ret = unsafe { rl_memmove(dst.as_mut_ptr().add(da + 8), src.as_ptr().add(sa), n) };
                    assert_eq!(ret, unsafe { dst.as_mut_ptr().add(da + 8) });
                    assert!(dst == expected, "n={n} src+{sa} dst+{da}");
                }
            }
        }
    }

    #[test]
    fn moves_within_one_buffer_at_every_overlap() {
        for n in lengths() {
            // Every distance up to 70 either way, then a few far ones.
            let mut shifts: Vec<isize> = (-70..=70).collect();
            shifts.extend([-(n as isize) - 1, -(n as isize), n as isize, n as isize + 1]);
            shifts.extend([-(n as isize) / 2, n as isize / 2, -129, 129, 4096, -4096]);
            for shift in shifts {
                let (src_off, dst_off) = if shift >= 0 {
                    (100, 100 + shift as usize)
                } else {
                    (100 + (-shift) as usize, 100)
                };
                let len = src_off.max(dst_off) + n + 100;
                let mut buf = pattern(len);
                let mut expected = buf.clone();
                expected.copy_within(src_off..src_off + n, dst_off);
                unsafe {
                    let p = buf.as_mut_ptr();
                    rl_memmove(p.add(dst_off), p.add(src_off), n);
                }
                assert!(buf == expected, "n={n} shift={shift}");
            }
        }
    }

    #[test]
    fn sets_every_length_and_alignment() {
        for n in lengths() {
            let aligns = if n <= 300 { 0..16 } else { 0..4 };
            for da in aligns {
                for c in [0x00, 0x5A, 0xFF, 0x1_23] {
                    let mut dst = pattern(n + 64);
                    let mut expected = dst.clone();
                    expected[da + 8..da + 8 + n].fill(c as u8);
                    let ret = unsafe { rl_memset(dst.as_mut_ptr().add(da + 8), c, n) };
                    assert_eq!(ret, unsafe { dst.as_mut_ptr().add(da + 8) });
                    assert!(dst == expected, "n={n} dst+{da} c={c:#x}");
                }
            }
        }
    }
}
