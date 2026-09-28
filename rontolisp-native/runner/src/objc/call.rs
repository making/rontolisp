//! One generic `objc_msgSend` for every shape, on Apple's AArch64 calling convention.
//!
//! A send cannot go through a variadic declaration (an `NSRect` through a `long` shape is a
//! SIGBUS), and Rust, like a native image, can only call shapes it was compiled with. The
//! JVM binding answers with a closed table of shapes; this answers with the convention
//! itself: `rl_objc_call` loads x0-x7, d0-d7, x8 and a stack area from a [`Frame`], calls,
//! and stores x0-x1 and d0-d3 back, and [`Frame::push`] classifies each argument the way
//! AAPCS64 (Apple's variant) does. So any selector whose encoding parses is callable --
//! no shape table to keep in step.
//!
//! Apple's differences from the base AAPCS64 that matter here: an argument on the stack
//! takes its NATURAL size and alignment (not an 8-byte slot), and a variadic argument
//! always goes on the stack in an 8-byte slot, never in a register.
//!
//! `rl_objc_call` is also where an Objective-C exception raised inside the call stops
//! (`.kb/objc.md`, "The new base: exceptions and NSError"): its call site is covered by
//! an LSDA whose one handler catches `OBJC_EHTYPE_id` -- `@catch (id)` -- under
//! `__objc_personality_v0`, so the unwinder never reaches the host's or the module's
//! frames above it. The landing pad hands the exception to [`rl_objc_caught`], which
//! takes it (`objc_begin_catch`), retains it, ends the catch and marks the frame; the
//! call answers nothing. libobjc is dlopened at run time, so the personality named in the
//! CFI is a forwarder and the type table entry an indirection through a slot, both filled
//! by [`install`] once the runtime is open.

use std::ffi::{c_int, c_void};
use std::sync::OnceLock;

use super::encoding::{Kind, Type};

/// What `rl_objc_call` reads and writes; the offsets are the assembly's.
#[repr(C)]
pub struct Frame {
    function: usize,     // 0
    x: [u64; 8],         // 8
    d: [u64; 8],         // 72
    x8: u64,             // 136
    stack_words: u64,    // 144
    stack: *const u64,   // 152
    pub ret_x: [u64; 2], // 160
    pub ret_d: [u64; 4], // 176
    raised: u64,         // 208: 1 when the call raised
    exception: u64,      // 216: what it threw, retained
}

std::arch::global_asm!(
    ".globl _rl_objc_call",
    ".p2align 2",
    "_rl_objc_call:",
    ".cfi_startproc",
    ".cfi_personality 155, _rl_objc_personality",
    ".cfi_lsda 16, Lrl_objc_call_lsda",
    "stp x29, x30, [sp, #-32]!",
    ".cfi_def_cfa_offset 32",
    ".cfi_offset w30, -24",
    ".cfi_offset w29, -32",
    "mov x29, sp",
    ".cfi_def_cfa w29, 32",
    "str x19, [sp, #16]",
    ".cfi_offset w19, -16",
    "mov x19, x0",
    // The stack area, 16-byte aligned.
    "ldr x9, [x19, #144]",
    "add x10, x9, #1",
    "and x10, x10, #-2",
    "lsl x10, x10, #3",
    "sub sp, sp, x10",
    "ldr x11, [x19, #152]",
    "mov x12, #0",
    "1:",
    "cmp x12, x9",
    "b.hs 2f",
    "ldr x13, [x11, x12, lsl #3]",
    "str x13, [sp, x12, lsl #3]",
    "add x12, x12, #1",
    "b 1b",
    "2:",
    "ldp d0, d1, [x19, #72]",
    "ldp d2, d3, [x19, #88]",
    "ldp d4, d5, [x19, #104]",
    "ldp d6, d7, [x19, #120]",
    "ldr x8, [x19, #136]",
    "ldr x16, [x19, #0]",
    "ldp x0, x1, [x19, #8]",
    "ldp x2, x3, [x19, #24]",
    "ldp x4, x5, [x19, #40]",
    "ldp x6, x7, [x19, #56]",
    "Lrl_objc_call_begin:",
    "blr x16",
    "Lrl_objc_call_end:",
    "stp x0, x1, [x19, #160]",
    "stp d0, d1, [x19, #176]",
    "stp d2, d3, [x19, #192]",
    "Lrl_objc_call_after:",
    "mov sp, x29",
    "ldr x19, [sp, #16]",
    "ldp x29, x30, [sp], #32",
    "ret",
    // x0: the unwinder's exception object; x19 is the frame again.
    "Lrl_objc_call_landing:",
    "mov x1, x19",
    "bl _rl_objc_caught",
    "b Lrl_objc_call_after",
    ".cfi_endproc",
    // One call site, one handler: type 1, which the slot below names indirectly.
    ".section __TEXT,__gcc_except_tab",
    ".p2align 2",
    "Lrl_objc_call_lsda:",
    ".byte 255",
    ".byte 155",
    ".uleb128 Lrl_objc_call_ttbase - Lrl_objc_call_ttref",
    "Lrl_objc_call_ttref:",
    ".byte 1",
    ".uleb128 Lrl_objc_call_cst_end - Lrl_objc_call_cst_begin",
    "Lrl_objc_call_cst_begin:",
    ".uleb128 Lrl_objc_call_begin - _rl_objc_call",
    ".uleb128 Lrl_objc_call_end - Lrl_objc_call_begin",
    ".uleb128 Lrl_objc_call_landing - _rl_objc_call",
    ".byte 1",
    "Lrl_objc_call_cst_end:",
    ".byte 1",
    ".byte 0",
    ".p2align 2",
    ".long _rl_objc_ehtype_slot - .",
    "Lrl_objc_call_ttbase:",
    ".text",
);

/// `OBJC_EHTYPE_id`, the type the call site catches, once the runtime is open; the LSDA's
/// type table points here (an indirect entry), so the stub links without libobjc.
#[unsafe(no_mangle)]
static mut rl_objc_ehtype_slot: usize = 0;

/// libobjc's pieces the catch uses, filled by [`install`].
struct Catch {
    personality: usize,
    begin_catch: unsafe extern "C" fn(*mut c_void) -> usize,
    end_catch: unsafe extern "C" fn(),
    retain: unsafe extern "C" fn(usize) -> usize,
}

static CATCH: OnceLock<Catch> = OnceLock::new();

/// Arms the catch with libobjc's personality, its `@catch (id)` type and the catch calls.
/// Until then no exception is claimed (the forwarder answers "continue unwinding").
///
/// # Safety
/// Each address is the named libobjc symbol.
pub unsafe fn install(personality: usize, eh_type_id: usize, begin_catch: usize, end_catch: usize, retain: usize) {
    // SAFETY: written once, before any call can raise, and read only by the personality
    // routine of a later unwind.
    unsafe { std::ptr::addr_of_mut!(rl_objc_ehtype_slot).write(eh_type_id) };
    let _ = CATCH.set(Catch {
        personality,
        // SAFETY: the caller vouches for each symbol; these are their declared types.
        begin_catch: unsafe { std::mem::transmute::<usize, unsafe extern "C" fn(*mut c_void) -> usize>(begin_catch) },
        end_catch: unsafe { std::mem::transmute::<usize, unsafe extern "C" fn()>(end_catch) },
        retain: unsafe { std::mem::transmute::<usize, unsafe extern "C" fn(usize) -> usize>(retain) },
    });
}

/// The personality routine the CFI names: `__objc_personality_v0`'s, once installed.
#[unsafe(no_mangle)]
unsafe extern "C" fn rl_objc_personality(
    version: c_int,
    actions: c_int,
    class: u64,
    exception: *mut c_void,
    context: *mut c_void,
) -> c_int {
    const URC_CONTINUE_UNWIND: c_int = 8;
    match CATCH.get() {
        Some(c) => {
            // SAFETY: the address of __objc_personality_v0, whose signature this is.
            let personality = unsafe {
                std::mem::transmute::<usize, unsafe extern "C" fn(c_int, c_int, u64, *mut c_void, *mut c_void) -> c_int>(
                    c.personality,
                )
            };
            // SAFETY: the unwinder's own arguments, passed on.
            unsafe { personality(version, actions, class, exception, context) }
        }
        None => URC_CONTINUE_UNWIND,
    }
}

/// The landing pad's call: takes the exception, keeps one reference to what was thrown in
/// the frame, and ends the catch.
#[unsafe(no_mangle)]
unsafe extern "C" fn rl_objc_caught(exception: *mut c_void, frame: *mut Frame) {
    let Some(c) = CATCH.get() else { return };
    // SAFETY: the exception the personality claimed for this frame; begin and end pair.
    unsafe {
        let thrown = (c.begin_catch)(exception);
        let kept = if thrown == 0 { 0 } else { (c.retain)(thrown) };
        (c.end_catch)();
        (*frame).raised = 1;
        (*frame).exception = kept as u64;
    }
}

/// An Objective-C exception a call raised: what was thrown (0 for nil), retained once for
/// whoever takes it.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Raised(pub usize);

unsafe extern "C" {
    fn rl_objc_call(frame: *mut Frame);
}

/// A scalar leaf of an argument or result: integers (addresses included) and floats.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Leaf {
    Int(i64),
    Float(f64),
}

impl Leaf {
    pub fn bits(self, kind: Kind) -> u64 {
        match (self, kind) {
            (Leaf::Float(f), Kind::Float) => (f as f32).to_bits() as u64,
            (Leaf::Float(f), Kind::Double) => f.to_bits(),
            (Leaf::Int(i), Kind::Float) => (i as f32).to_bits() as u64,
            (Leaf::Int(i), Kind::Double) => (i as f64).to_bits(),
            (Leaf::Float(f), Kind::Bool) => (f != 0.0) as u64,
            (Leaf::Float(f), _) => f as i64 as u64,
            (Leaf::Int(i), Kind::Bool) => (i != 0) as u64,
            (Leaf::Int(i), _) => i as u64,
        }
    }
}

/// A send being laid out.
pub struct Call {
    frame: Frame,
    ngrn: usize,
    nsrn: usize,
    stack: Vec<u8>,
    /// Memory an argument points into (a struct passed by reference); lives until the call
    /// returns.
    keep: Vec<Vec<u64>>,
    ret: Type,
    ret_buffer: Vec<u64>,
}

impl Call {
    pub fn new(function: usize, ret: &Type) -> Call {
        let mut call = Call {
            frame: Frame {
                function,
                x: [0; 8],
                d: [0; 8],
                x8: 0,
                stack_words: 0,
                stack: std::ptr::null(),
                ret_x: [0; 2],
                ret_d: [0; 4],
                raised: 0,
                exception: 0,
            },
            ngrn: 0,
            nsrn: 0,
            stack: Vec::new(),
            keep: Vec::new(),
            ret: ret.clone(),
            ret_buffer: Vec::new(),
        };
        // A struct result wider than 16 bytes that is not an HFA comes back through the
        // memory x8 points at (arm64 has no objc_msgSend_stret).
        if ret.kind == Kind::Struct && ret.hfa().is_none() && ret.layout().1 > 16 {
            call.ret_buffer = vec![0u64; ret.layout().1.div_ceil(8)];
            call.frame.x8 = call.ret_buffer.as_mut_ptr() as u64;
        }
        call
    }

    fn stack_push(&mut self, bytes: &[u8], align: usize) {
        let pad = (align - self.stack.len() % align) % align;
        self.stack.extend(std::iter::repeat_n(0, pad));
        self.stack.extend_from_slice(bytes);
    }

    fn push_int(&mut self, bits: u64, size: usize) {
        if self.ngrn < 8 {
            self.frame.x[self.ngrn] = bits;
            self.ngrn += 1;
        } else {
            self.stack_push(&bits.to_le_bytes()[..size], size);
        }
    }

    fn push_fp(&mut self, bits: u64, size: usize) {
        if self.nsrn < 8 {
            self.frame.d[self.nsrn] = bits;
            self.nsrn += 1;
        } else {
            self.stack_push(&bits.to_le_bytes()[..size], size);
        }
    }

    /// A declared (register-eligible) argument of the given type.
    pub fn push(&mut self, ty: &Type, leaves: &[Leaf]) {
        match ty.kind {
            Kind::Struct => self.push_struct(ty, leaves),
            Kind::Float | Kind::Double => self.push_fp(leaves[0].bits(ty.kind), ty.kind.size()),
            kind => {
                // Apple's callee expects a narrow integer extended to 32 bits by the
                // caller; the full register is extended here.
                let bits = leaves[0].bits(kind);
                let extended = match (kind, ty.unsigned) {
                    (Kind::Int8, false) => bits as i8 as i64 as u64,
                    (Kind::Int8, true) => bits as u8 as u64,
                    (Kind::Int16, false) => bits as i16 as i64 as u64,
                    (Kind::Int16, true) => bits as u16 as u64,
                    (Kind::Int32, false) => bits as i32 as i64 as u64,
                    (Kind::Int32, true) => bits as u32 as u64,
                    _ => bits,
                };
                self.push_int(extended, kind.size());
            }
        }
    }

    fn push_struct(&mut self, ty: &Type, leaves: &[Leaf]) {
        let (offsets, size, align) = ty.layout();
        let mut bytes = vec![0u8; size.div_ceil(8) * 8];
        for (i, (kind, offset)) in ty.leaves.iter().zip(&offsets).enumerate() {
            let bits = leaves[i].bits(*kind).to_le_bytes();
            bytes[*offset..*offset + kind.size()].copy_from_slice(&bits[..kind.size()]);
        }
        if let Some(element) = ty.hfa() {
            let n = ty.leaves.len();
            if self.nsrn + n <= 8 {
                for leaf in leaves.iter().take(n) {
                    let bits = leaf.bits(element);
                    self.frame.d[self.nsrn] = bits;
                    self.nsrn += 1;
                }
            } else {
                self.nsrn = 8;
                self.stack_push(&bytes[..size], align.max(8));
            }
            return;
        }
        if size > 16 {
            // Passed by reference: a copy the callee may scribble on.
            let words: Vec<u64> = bytes
                .chunks(8)
                .map(|c| u64::from_le_bytes(c.try_into().unwrap()))
                .collect();
            let pointer = words.as_ptr() as u64;
            self.keep.push(words);
            self.push_int(pointer, 8);
            return;
        }
        let n = size.div_ceil(8);
        if self.ngrn + n <= 8 {
            for chunk in bytes.chunks(8) {
                self.frame.x[self.ngrn] = u64::from_le_bytes(chunk.try_into().unwrap());
                self.ngrn += 1;
            }
        } else {
            self.ngrn = 8;
            self.stack_push(&bytes, 8);
        }
    }

    /// An argument past a variadic selector's declared arity: an 8-byte stack slot,
    /// whatever its value.
    pub fn push_variadic(&mut self, leaf: Leaf) {
        let bits = match leaf {
            Leaf::Int(i) => i as u64,
            Leaf::Float(f) => f.to_bits(),
        };
        self.stack_push(&bits.to_le_bytes(), 8);
    }

    /// Calls, and answers the result's leaves: one for a scalar, one per leaf for a
    /// struct, none for void -- or what the call raised.
    ///
    /// # Safety
    /// The function is `objc_msgSend` (or any function) whose real signature is what the
    /// pushed arguments and the result type describe.
    pub unsafe fn invoke(mut self) -> Result<Vec<Leaf>, Raised> {
        self.stack.resize(self.stack.len().div_ceil(8) * 8, 0);
        let words: Vec<u64> = self
            .stack
            .chunks(8)
            .map(|c| u64::from_le_bytes(c.try_into().unwrap()))
            .collect();
        self.frame.stack_words = words.len() as u64;
        self.frame.stack = words.as_ptr();
        // SAFETY: the frame is fully initialised; the caller vouches for the shape.
        unsafe { rl_objc_call(&mut self.frame) };
        drop(words);
        if self.frame.raised != 0 {
            return Err(Raised(self.frame.exception as usize));
        }
        let ret = &self.ret;
        Ok(match ret.kind {
            Kind::Void => Vec::new(),
            Kind::Float => vec![Leaf::Float(f32::from_bits(self.frame.ret_d[0] as u32) as f64)],
            Kind::Double => vec![Leaf::Float(f64::from_bits(self.frame.ret_d[0]))],
            Kind::Struct => {
                let (offsets, size, _) = ret.layout();
                let mut bytes = Vec::with_capacity(32);
                if let Some(element) = ret.hfa() {
                    // Each leaf in its own SIMD register.
                    return Ok(self.frame.ret_d[..ret.leaves.len()]
                        .iter()
                        .map(|bits| {
                            Leaf::Float(if element == Kind::Float {
                                f32::from_bits(*bits as u32) as f64
                            } else {
                                f64::from_bits(*bits)
                            })
                        })
                        .collect());
                } else if size > 16 {
                    for w in &self.ret_buffer {
                        bytes.extend_from_slice(&w.to_le_bytes());
                    }
                } else {
                    bytes.extend_from_slice(&self.frame.ret_x[0].to_le_bytes());
                    bytes.extend_from_slice(&self.frame.ret_x[1].to_le_bytes());
                }
                ret.leaves
                    .iter()
                    .zip(offsets)
                    .map(|(k, o)| read_leaf(*k, false, &bytes[o..o + k.size()]))
                    .collect()
            }
            kind => vec![read_leaf(
                kind,
                ret.unsigned,
                &self.frame.ret_x[0].to_le_bytes()[..kind.size()],
            )],
        })
    }
}

pub fn read_leaf(kind: Kind, unsigned: bool, bytes: &[u8]) -> Leaf {
    let mut buf = [0u8; 8];
    buf[..bytes.len()].copy_from_slice(bytes);
    let raw = u64::from_le_bytes(buf);
    match kind {
        Kind::Float => Leaf::Float(f32::from_bits(raw as u32) as f64),
        Kind::Double => Leaf::Float(f64::from_bits(raw)),
        Kind::Bool => Leaf::Int((raw as u8 != 0) as i64),
        Kind::Int8 => Leaf::Int(if unsigned { raw as u8 as i64 } else { raw as i8 as i64 }),
        Kind::Int16 => Leaf::Int(if unsigned { raw as u16 as i64 } else { raw as i16 as i64 }),
        Kind::Int32 => Leaf::Int(if unsigned { raw as u32 as i64 } else { raw as i32 as i64 }),
        _ => Leaf::Int(raw as i64),
    }
}

#[cfg(test)]
mod tests {
    use super::super::encoding::parse;
    use super::*;

    extern "C" fn mixed(a: i32, b: f64, c: u8, d: f32, e: i64) -> f64 {
        a as f64 + b + c as f64 + d as f64 + e as f64
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    struct Rect {
        x: f64,
        y: f64,
        w: f64,
        h: f64,
    }

    extern "C" fn rect_scale(r: Rect, k: f64) -> Rect {
        Rect {
            x: r.x * k,
            y: r.y * k,
            w: r.w * k,
            h: r.h * k,
        }
    }

    #[repr(C)]
    struct Big {
        a: i64,
        b: i32,
        c: i64,
    }

    extern "C" fn big_swap(v: Big) -> Big {
        Big {
            a: v.c,
            b: v.b + 1,
            c: v.a,
        }
    }

    #[allow(clippy::too_many_arguments)]
    extern "C" fn many(a: i64, b: i64, c: i64, d: i64, e: i64, f: i64, g: i64, h: i64, i: i32, j: i8, k: i64) -> i64 {
        a + b + c + d + e + f + g + h + i as i64 * 1000 + j as i64 * 100_000 + k * 10_000_000
    }

    fn ty(enc: &str) -> Vec<Type> {
        let e = parse(enc).unwrap();
        let mut all = vec![e.ret];
        all.extend(e.args);
        all
    }

    #[test]
    fn scalars_of_every_class_reach_their_registers() {
        let t = ty("didCfq");
        let mut call = Call::new(mixed as *const () as usize, &t[0]);
        call.push(&t[1], &[Leaf::Int(-3)]);
        call.push(&t[2], &[Leaf::Float(0.5)]);
        call.push(&t[3], &[Leaf::Int(200)]);
        call.push(&t[4], &[Leaf::Float(0.25)]);
        call.push(&t[5], &[Leaf::Int(1 << 40)]);
        let out = unsafe { call.invoke() }.unwrap();
        assert_eq!(out, vec![Leaf::Float(-3.0 + 0.5 + 200.0 + 0.25 + (1u64 << 40) as f64)]);
    }

    #[test]
    fn an_hfa_goes_in_and_out_through_simd_registers() {
        let t = ty("{CGRect=dddd}{CGRect=dddd}d");
        let mut call = Call::new(rect_scale as *const () as usize, &t[0]);
        call.push(
            &t[1],
            &[Leaf::Float(1.0), Leaf::Float(2.0), Leaf::Float(3.0), Leaf::Float(4.0)],
        );
        call.push(&t[2], &[Leaf::Float(2.0)]);
        let out = unsafe { call.invoke() }.unwrap();
        assert_eq!(
            out,
            vec![Leaf::Float(2.0), Leaf::Float(4.0), Leaf::Float(6.0), Leaf::Float(8.0)]
        );
    }

    #[test]
    fn a_wide_struct_goes_by_reference_and_comes_back_through_x8() {
        let t = ty("{B=qiq}{B=qiq}");
        let mut call = Call::new(big_swap as *const () as usize, &t[0]);
        call.push(&t[1], &[Leaf::Int(7), Leaf::Int(41), Leaf::Int(-9)]);
        let out = unsafe { call.invoke() }.unwrap();
        assert_eq!(out, vec![Leaf::Int(-9), Leaf::Int(42), Leaf::Int(7)]);
    }

    unsafe extern "C" {
        fn dlopen(path: *const std::ffi::c_char, mode: i32) -> *mut c_void;
        fn dlsym(handle: *mut c_void, symbol: *const std::ffi::c_char) -> *mut c_void;
    }

    #[test]
    fn an_objective_c_exception_stops_at_the_call() {
        // SAFETY: libobjc's own symbols, called by their declared types.
        unsafe {
            let objc = dlopen(c"/usr/lib/libobjc.A.dylib".as_ptr(), 2);
            assert!(!objc.is_null());
            let sym = |name: &std::ffi::CStr| dlsym(objc, name.as_ptr()) as usize;
            install(
                sym(c"__objc_personality_v0"),
                sym(c"OBJC_EHTYPE_id"),
                sym(c"objc_begin_catch"),
                sym(c"objc_end_catch"),
                sym(c"objc_retain"),
            );
            let get_class = std::mem::transmute::<usize, unsafe extern "C" fn(*const std::ffi::c_char) -> usize>(sym(
                c"objc_getClass",
            ));
            let thrown = get_class(c"NSObject".as_ptr());
            let t = ty("v@");
            let mut call = Call::new(sym(c"objc_exception_throw"), &t[0]);
            call.push(&t[1], &[Leaf::Int(thrown as i64)]);
            assert_eq!(call.invoke(), Err(Raised(thrown)));
            // The call after it runs as usual.
            let t = ty("qqq");
            let mut call = Call::new(add as *const () as usize, &t[0]);
            call.push(&t[1], &[Leaf::Int(2)]);
            call.push(&t[2], &[Leaf::Int(3)]);
            assert_eq!(call.invoke(), Ok(vec![Leaf::Int(5)]));
        }
    }

    extern "C" fn add(a: i64, b: i64) -> i64 {
        a + b
    }

    #[test]
    fn arguments_past_the_eighth_register_are_packed_on_the_stack() {
        let t = ty("qqqqqqqqqicq");
        let mut call = Call::new(many as *const () as usize, &t[0]);
        for (i, arg) in t[1..].iter().enumerate() {
            let v = match i {
                8 => 5,
                9 => -2,
                10 => 3,
                _ => 1,
            };
            call.push(arg, &[Leaf::Int(v)]);
        }
        let out = unsafe { call.invoke() }.unwrap();
        assert_eq!(out, vec![Leaf::Int(8 + 5000 - 200_000 + 30_000_000)]);
    }
}
