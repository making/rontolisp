//! Classes a `--native` program defines on the new `objc` base (`objc-class.lisp`,
//! `.kb/objc.md`, "The new base: class definition"): the class-building imports, and the
//! one IMP every method a program defines runs through, whatever its shape.
//!
//! A method's IMP comes from `imp_implementationWithBlock` over a GLOBAL block whose
//! invoke function is [`rl_objc_block_imp`]: libobjc's trampoline moves the receiver to
//! `x1` and the block to `x0` and leaves every other argument register, `x8` and the
//! stack as the caller laid them out, so one assembly entry saves the whole argument
//! state into an [`ImpFrame`] and [`rl_objc_block_dispatch`] reads each argument back by
//! the method's encoding -- [`Call`]'s classification run in reverse. The block carries
//! the method's index, which is what a super send needs: the superclass's IMP for a
//! receiver whose class also defines the selector must run the SUPERCLASS's body.
//!
//! The method itself runs in the module: the export `rlobjc_method(index, self)` reads
//! the arguments through `p_cb_count` / `p_cb_arg` (which answer like a send, so
//! `p_result_*` fetch them) and pushes its answer with the `p_arg_*` imports. It arrives
//! INSIDE a host call -- a send that made Objective-C call the method, or `pump` -- and
//! re-enters through that call's `Caller`, like the old base's callbacks.

use std::cell::RefCell;
use std::ffi::c_void;
use std::rc::Rc;

use wasmtime::{AsContextMut, Caller, Linker};
use wasmtime_wasi::I32Exit;
use wasmtime_wasi::p1::WasiP1Ctx;

use super::call::{Leaf, read_leaf};
use super::encoding::{self, Kind, Type};
use super::prim::{self, Raw};
use super::{Api, CALLER, Id, MODULE, api, cstring, memory_string, return_string, text, with};

/// The module's export a defined method runs through: `(method-index, self) -> i32`.
pub const METHOD_EXPORT: &str = "rlobjc_method";

/// The instance variable every class the new base defines carries, so a later
/// definition of the same name in this process is recognized as one it may reuse
/// (`ObjcRuntime.DEFINED_MARKER`, the same name).
const DEFINED_MARKER: &str = "rontolispDefinedClass";

/// Result flag: retain an object answer (an owning method's +1).
const RETAIN_RESULT: i32 = 1;
/// Result flag: autorelease the retained object answer.
const AUTORELEASE_RESULT: i32 = 2;

/// A method a program defined.
struct Method {
    closure: i32,
    encoding: Rc<encoding::Encoding>,
    flags: i32,
}

thread_local! {
    static METHODS: RefCell<Vec<Rc<Method>>> = const { RefCell::new(Vec::new()) };
    /// The arguments of the methods running, innermost last.
    static FRAMES: RefCell<Vec<Vec<(Type, Vec<Leaf>)>>> = const { RefCell::new(Vec::new()) };
}

/// The block literal an IMP is made from (the Blocks ABI), global so that `Block_copy`
/// answers it as it is; `method` is this runner's own field after the ABI's.
#[repr(C)]
struct MethodBlock {
    isa: usize,
    flags: i32,
    reserved: i32,
    invoke: usize,
    descriptor: *const BlockDescriptor,
    method: usize,
}

#[repr(C)]
struct BlockDescriptor {
    reserved: u64,
    size: u64,
}

static DESCRIPTOR: BlockDescriptor = BlockDescriptor {
    reserved: 0,
    size: std::mem::size_of::<MethodBlock>() as u64,
};

const BLOCK_IS_GLOBAL: i32 = 1 << 28;

/// What [`rl_objc_block_imp`] saves and restores; the offsets are the assembly's.
#[repr(C)]
pub(super) struct ImpFrame {
    pub(super) x: [u64; 8], // 0
    d: [u64; 8],            // 64
    x8: u64,                // 128
    stack: usize,           // 136: the caller's stack arguments
    _pad: u64,              // 144
    _pad2: u64,             // 152
}

std::arch::global_asm!(
    ".globl _rl_objc_block_imp",
    ".p2align 2",
    "_rl_objc_block_imp:",
    "stp x29, x30, [sp, #-16]!",
    "mov x29, sp",
    "sub sp, sp, #160",
    "stp x0, x1, [sp, #0]",
    "stp x2, x3, [sp, #16]",
    "stp x4, x5, [sp, #32]",
    "stp x6, x7, [sp, #48]",
    "stp d0, d1, [sp, #64]",
    "stp d2, d3, [sp, #80]",
    "stp d4, d5, [sp, #96]",
    "stp d6, d7, [sp, #112]",
    "str x8, [sp, #128]",
    "add x9, x29, #16",
    "str x9, [sp, #136]",
    "mov x0, sp",
    "bl _rl_objc_block_dispatch",
    "ldp x0, x1, [sp, #0]",
    "ldp d0, d1, [sp, #64]",
    "ldp d2, d3, [sp, #80]",
    "mov sp, x29",
    "ldp x29, x30, [sp], #16",
    "ret",
);

unsafe extern "C" {
    fn rl_objc_block_imp();
}

/// The invoke function of every block this runner makes: a method's IMP block and a
/// block a program made from a Lisp function ([`super::block`]) alike.
pub(super) fn block_entry() -> usize {
    rl_objc_block_imp as *const () as usize
}

/// Reads the arguments of a call out of the saved registers and stack, the way
/// [`super::call::Call`] lays them in.
pub(super) struct Reader<'a> {
    frame: &'a ImpFrame,
    ngrn: usize,
    nsrn: usize,
    offset: usize,
}

impl<'a> Reader<'a> {
    /// A reader past the first `taken` integer registers (the block, a receiver).
    pub(super) fn new(frame: &'a ImpFrame, taken: usize) -> Reader<'a> {
        Reader {
            frame,
            ngrn: taken,
            nsrn: 0,
            offset: 0,
        }
    }

    fn stack(&mut self, size: usize, align: usize) -> &[u8] {
        self.offset += (align - self.offset % align) % align;
        // SAFETY: the caller's stack argument area, which the convention says holds this
        // argument at this offset.
        let bytes = unsafe { std::slice::from_raw_parts((self.frame.stack + self.offset) as *const u8, size) };
        self.offset += size;
        bytes
    }

    fn int_bits(&mut self, size: usize) -> Vec<u8> {
        if self.ngrn < 8 {
            let bits = self.frame.x[self.ngrn];
            self.ngrn += 1;
            bits.to_le_bytes()[..size].to_vec()
        } else {
            self.stack(size, size).to_vec()
        }
    }

    fn fp_bits(&mut self, size: usize) -> Vec<u8> {
        if self.nsrn < 8 {
            let bits = self.frame.d[self.nsrn];
            self.nsrn += 1;
            bits.to_le_bytes()[..size].to_vec()
        } else {
            self.stack(size, size).to_vec()
        }
    }

    pub(super) fn read(&mut self, ty: &Type) -> Vec<Leaf> {
        match ty.kind {
            Kind::Struct => self.read_struct(ty),
            Kind::Float | Kind::Double => {
                let bytes = self.fp_bits(ty.kind.size());
                vec![read_leaf(ty.kind, false, &bytes)]
            }
            kind => {
                let bytes = self.int_bits(kind.size());
                vec![read_leaf(kind, ty.unsigned, &bytes)]
            }
        }
    }

    fn read_struct(&mut self, ty: &Type) -> Vec<Leaf> {
        let (offsets, size, align) = ty.layout();
        let leaves_of = |bytes: &[u8]| -> Vec<Leaf> {
            ty.leaves
                .iter()
                .zip(&offsets)
                .map(|(k, o)| read_leaf(*k, false, &bytes[*o..*o + k.size()]))
                .collect()
        };
        if let Some(element) = ty.hfa() {
            let n = ty.leaves.len();
            if self.nsrn + n <= 8 {
                let mut out = Vec::with_capacity(n);
                for _ in 0..n {
                    let bits = self.frame.d[self.nsrn];
                    self.nsrn += 1;
                    out.push(read_leaf(element, false, &bits.to_le_bytes()[..element.size()]));
                }
                return out;
            }
            self.nsrn = 8;
            let bytes = self.stack(size, align.max(8)).to_vec();
            return leaves_of(&bytes);
        }
        if size > 16 {
            // Passed by reference: the caller's copy.
            let pointer = u64::from_le_bytes(self.int_bits(8).try_into().unwrap_or([0; 8])) as usize;
            // SAFETY: the struct the caller passed by reference.
            let bytes = unsafe { std::slice::from_raw_parts(pointer as *const u8, size) };
            return leaves_of(bytes);
        }
        let n = size.div_ceil(8);
        let mut bytes = Vec::with_capacity(n * 8);
        if self.ngrn + n <= 8 {
            for _ in 0..n {
                bytes.extend_from_slice(&self.frame.x[self.ngrn].to_le_bytes());
                self.ngrn += 1;
            }
        } else {
            self.ngrn = 8;
            bytes.extend_from_slice(self.stack(n * 8, 8));
        }
        leaves_of(&bytes)
    }
}

/// Writes a method's (or a block's) answer where the caller reads it; no leaves write
/// the type's zero.
pub(super) fn write_result(frame: &mut ImpFrame, ret: &Type, leaves: &[Leaf]) {
    let leaf = |i: usize| leaves.get(i).copied().unwrap_or(Leaf::Int(0));
    match ret.kind {
        Kind::Void => {}
        Kind::Float | Kind::Double => frame.d[0] = leaf(0).bits(ret.kind),
        Kind::Struct => {
            let (offsets, size, _) = ret.layout();
            if let Some(element) = ret.hfa() {
                for i in 0..ret.leaves.len() {
                    frame.d[i] = leaf(i).bits(element);
                }
                return;
            }
            let mut bytes = vec![0u8; size.div_ceil(8) * 8];
            for (i, (kind, offset)) in ret.leaves.iter().zip(&offsets).enumerate() {
                let bits = leaf(i).bits(*kind).to_le_bytes();
                bytes[*offset..*offset + kind.size()].copy_from_slice(&bits[..kind.size()]);
            }
            if size > 16 {
                // SAFETY: the buffer the caller passed in x8 for a struct this wide.
                unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), frame.x8 as *mut u8, size) };
            } else {
                for (i, chunk) in bytes.chunks(8).enumerate().take(2) {
                    frame.x[i] = u64::from_le_bytes(chunk.try_into().unwrap_or([0; 8]));
                }
            }
        }
        kind => {
            let bits = leaf(0).bits(kind);
            frame.x[0] = match (kind, ret.unsigned) {
                (Kind::Int8, false) => bits as i8 as i64 as u64,
                (Kind::Int8, true) => bits as u8 as u64,
                (Kind::Int16, false) => bits as i16 as i64 as u64,
                (Kind::Int16, true) => bits as u16 as u64,
                (Kind::Int32, false) => bits as i32 as i64 as u64,
                (Kind::Int32, true) => bits as u32 as u64,
                (Kind::Bool, _) => (bits != 0) as u64,
                _ => bits,
            };
        }
    }
}

/// The leaves of what the module pushed as a method's answer.
fn answer_leaves(raws: &[Raw]) -> Vec<Leaf> {
    match raws.first() {
        Some(Raw::Int(i)) => vec![Leaf::Int(*i)],
        Some(Raw::Float(f)) => vec![Leaf::Float(*f)],
        Some(Raw::Leaves(l)) => l.clone(),
        _ => Vec::new(),
    }
}

/// Every method a program defines lands here, from [`rl_objc_block_imp`].
#[unsafe(no_mangle)]
extern "C" fn rl_objc_block_dispatch(frame: *mut ImpFrame) {
    // SAFETY: the frame rl_objc_block_imp built on its stack for this call.
    let frame = unsafe { &mut *frame };
    let block = frame.x[0] as *const MethodBlock;
    // SAFETY: a block whose invoke function is rl_objc_block_imp: a method's (its
    // descriptor is DESCRIPTOR) or one a program made, whose header is the same.
    if !std::ptr::eq(unsafe { (*block).descriptor }, &DESCRIPTOR) {
        super::block::invoke(frame);
        return;
    }
    // SAFETY: the block imp_implementationWithBlock was made from, which lives forever.
    let index = unsafe { (*block).method };
    let receiver = frame.x[1] as Id;
    let Some(method) = METHODS.with(|m| m.borrow().get(index).cloned()) else {
        return;
    };
    let ret = method.encoding.ret.clone();
    let mut reader = Reader::new(frame, 2);
    let args: Vec<(Type, Vec<Leaf>)> = method.encoding.args[2..]
        .iter()
        .map(|ty| (ty.clone(), reader.read(ty)))
        .collect();
    let leaves = run_closure(method.closure, receiver, args);
    if let (Kind::Object, Some(Leaf::Int(a))) = (ret.kind, leaves.first())
        && *a != 0
        && method.flags & RETAIN_RESULT != 0
        && let Ok(api) = api()
    {
        // SAFETY: a live object the method answered; the caller owns what the flags say.
        unsafe { (api.retain)(*a as Id) };
        if method.flags & AUTORELEASE_RESULT != 0 {
            // SAFETY: as above, into the caller's pool.
            unsafe { (api.autorelease)(*a as Id) };
        }
    }
    write_result(frame, &ret, &leaves);
}

/// Runs a method's (or a block's) function in the module -- the closure the library
/// registered under that index -- and answers the leaves it answered (none when it
/// failed). Only on thread 0, inside a host call.
pub(super) fn run_closure(closure: i32, receiver: Id, args: Vec<(Type, Vec<Leaf>)>) -> Vec<Leaf> {
    let caller = CALLER.get();
    let callback = with(|s| s.method.clone());
    let (Some(callback), false) = (callback, caller.is_null()) else {
        return Vec::new();
    };
    let saved = prim::save_args();
    FRAMES.with(|f| f.borrow_mut().push(args));
    // SAFETY: CALLER is the Caller of the host call this method runs inside, on this
    // thread, which does not touch it until the call returns.
    let caller = unsafe { &mut *(caller as *mut Caller<'_, WasiP1Ctx>) };
    let status = callback.call(caller.as_context_mut(), (closure, receiver as i64));
    FRAMES.with(|f| f.borrow_mut().pop());
    let answer = prim::take_args();
    prim::restore_args(saved);
    match status {
        Ok(_) => answer_leaves(&answer),
        Err(e) => {
            // Never unwind into the native frame above. A Lisp error was already
            // handled in the library; what arrives here is an exit, or a non-local exit
            // to a tag outside the method (a trap on this backend), printed and contained
            // as the JVM contains any Throwable: the method answers its zero value.
            if let Some(code) = e.downcast_ref::<I32Exit>() {
                std::process::exit(code.0);
            }
            let why = e.root_cause().to_string();
            eprintln!("objc: error in a callback: {}", why.lines().next().unwrap_or(""));
            Vec::new()
        }
    }
}

fn define_method(api: &Api, cls: Id, sel: Id, types: &str, closure: i32, flags: i32) -> Result<(), String> {
    let encoding = Rc::new(encoding::parse(types)?);
    if encoding.args.len() < 2 {
        return Err(format!("type encoding '{types}' has no receiver and selector"));
    }
    let index = METHODS.with(|m| {
        let mut m = m.borrow_mut();
        m.push(Rc::new(Method {
            closure,
            encoding,
            flags,
        }));
        m.len() - 1
    });
    let block: &'static MethodBlock = Box::leak(Box::new(MethodBlock {
        isa: api.global_block,
        flags: BLOCK_IS_GLOBAL,
        reserved: 0,
        invoke: rl_objc_block_imp as *const () as usize,
        descriptor: &DESCRIPTOR,
        method: index,
    }));
    // SAFETY: a global block literal that lives forever.
    let imp = unsafe { (api.imp_implementation_with_block)(block as *const MethodBlock as *const c_void) };
    if imp == 0 {
        return Err("imp_implementationWithBlock answered no IMP".into());
    }
    let c = cstring(types)?;
    // SAFETY: a live class, an interned selector, an IMP of the declared shape. The
    // runtime keeps the encoding's bytes.
    unsafe { (api.class_replace_method)(cls, sel, imp, c.as_ptr()) };
    std::mem::forget(c);
    Ok(())
}

fn ivar(api: &Api, cls: Id, name: &str) -> Result<Id, String> {
    let c = cstring(name)?;
    // SAFETY: a live class and a NUL-terminated name.
    Ok(unsafe { (api.class_get_instance_variable)(cls, c.as_ptr()) })
}

fn add_ivar(api: &Api, cls: Id, name: &str, size: usize, align: usize, types: &str) -> Result<bool, String> {
    let (n, t) = (cstring(name)?, cstring(types)?);
    let log2 = (usize::BITS - 1 - align.max(1).leading_zeros()) as u8;
    // SAFETY: a class pair not yet registered; the runtime copies both strings.
    Ok(unsafe { (api.class_add_ivar)(cls, n.as_ptr(), size, log2, t.as_ptr()) })
}

/// A class pair, or the class of that name a definition in this process made before.
fn allocate_class(api: &Api, superclass: Id, name: &str) -> Result<Id, String> {
    let c = cstring(name)?;
    // SAFETY: a NUL-terminated name.
    let existing = unsafe { (api.get_class)(c.as_ptr()) };
    if existing != 0 {
        return Ok(if ivar(api, existing, DEFINED_MARKER)? != 0 {
            existing
        } else {
            0
        });
    }
    // SAFETY: a live superclass and a fresh name.
    let cls = unsafe { (api.allocate_class_pair)(superclass, c.as_ptr(), 0) };
    if cls != 0 {
        add_ivar(api, cls, DEFINED_MARKER, 1, 1, "c")?;
    }
    Ok(cls)
}

/// Binds the class-definition imports.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    fn runtime() -> wasmtime::Result<&'static Api> {
        api().map_err(|e| wasmtime::Error::msg(format!("objc: {e}")))
    }
    fn failed<T>(result: Result<T, String>, zero: T) -> T {
        result.unwrap_or_else(|e| {
            prim::set_error(e);
            zero
        })
    }
    linker.func_wrap(
        MODULE,
        "p_allocate_class",
        |mut caller: Caller<'_, WasiP1Ctx>, superclass: i64, p: i32, n: i32| -> wasmtime::Result<i64> {
            let name = memory_string(&mut caller, p, n)?;
            let api = runtime()?;
            Ok(failed(allocate_class(api, superclass as Id, &name), 0) as i64)
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_add_ivar",
        |mut caller: Caller<'_, WasiP1Ctx>,
         cls: i64,
         name_p: i32,
         name_n: i32,
         size: i64,
         align: i64,
         types_p: i32,
         types_n: i32|
         -> wasmtime::Result<i32> {
            let name = memory_string(&mut caller, name_p, name_n)?;
            let types = memory_string(&mut caller, types_p, types_n)?;
            let api = runtime()?;
            Ok(failed(
                add_ivar(api, cls as Id, &name, size as usize, align as usize, &types),
                false,
            ) as i32)
        },
    )?;
    linker.func_wrap(MODULE, "p_register_class", |cls: i64| -> wasmtime::Result<()> {
        // SAFETY: a class pair allocated and not yet registered.
        unsafe { (runtime()?.register_class_pair)(cls as Id) };
        Ok(())
    })?;
    linker.func_wrap(
        MODULE,
        "p_add_method",
        |mut caller: Caller<'_, WasiP1Ctx>,
         cls: i64,
         sel: i64,
         types_p: i32,
         types_n: i32,
         closure: i32,
         flags: i32|
         -> wasmtime::Result<i32> {
            let types = memory_string(&mut caller, types_p, types_n)?;
            let api = runtime()?;
            Ok(match define_method(api, cls as Id, sel as Id, &types, closure, flags) {
                Ok(()) => 1,
                Err(e) => {
                    prim::set_error(e);
                    0
                }
            })
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_add_protocol",
        |mut caller: Caller<'_, WasiP1Ctx>, cls: i64, p: i32, n: i32| -> wasmtime::Result<i32> {
            let name = memory_string(&mut caller, p, n)?;
            let api = runtime()?;
            let c = cstring(&name).map_err(wasmtime::Error::msg)?;
            // SAFETY: a NUL-terminated name.
            let protocol = unsafe { (api.get_protocol)(c.as_ptr()) };
            if protocol == 0 {
                return Ok(0);
            }
            // SAFETY: a live class and protocol.
            unsafe { (api.class_add_protocol)(cls as Id, protocol) };
            Ok(1)
        },
    )?;
    linker.func_wrap(MODULE, "p_superclass", |cls: i64| -> wasmtime::Result<i64> {
        // SAFETY: a live class.
        Ok(unsafe { (runtime()?.class_get_superclass)(cls as Id) } as i64)
    })?;
    linker.func_wrap(
        MODULE,
        "p_ivar_offset",
        |mut caller: Caller<'_, WasiP1Ctx>, cls: i64, p: i32, n: i32| -> wasmtime::Result<i64> {
            let name = memory_string(&mut caller, p, n)?;
            let api = runtime()?;
            let v = ivar(api, cls as Id, &name).map_err(wasmtime::Error::msg)?;
            // SAFETY: an instance variable of a live class.
            Ok(if v == 0 {
                -1
            } else {
                (unsafe { (api.ivar_get_offset)(v) }) as i64
            })
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_ivar_types",
        |mut caller: Caller<'_, WasiP1Ctx>, cls: i64, p: i32, n: i32| {
            let name = memory_string(&mut caller, p, n)?;
            let api = runtime()?;
            let v = ivar(api, cls as Id, &name).map_err(wasmtime::Error::msg)?;
            // SAFETY: an instance variable of a live class.
            let types = if v == 0 {
                String::new()
            } else {
                text(unsafe { (api.ivar_get_type_encoding)(v) })
            };
            return_string(&mut caller, &types)
        },
    )?;
    linker.func_wrap(MODULE, "p_cb_count", || -> i32 {
        FRAMES.with(|f| f.borrow().last().map(|a| a.len() as i32).unwrap_or(0))
    })?;
    linker.func_wrap(MODULE, "p_cb_arg", |i: i32| -> wasmtime::Result<i32> {
        let api = runtime()?;
        let arg = FRAMES.with(|f| f.borrow().last().and_then(|a| a.get(i as usize).cloned()));
        let Some((ty, leaves)) = arg else {
            wasmtime::bail!("objc: a method has no argument {i}");
        };
        // An object argument is retained for the value the library makes of it.
        Ok(prim::answer(api, &ty, &leaves, RETAIN_RESULT))
    })?;
    Ok(())
}
