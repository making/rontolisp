//! The new `objc` base's primitive layer on a `--native` output: the `rlobjc` `p_*`
//! imports `objc-native-primitives.lisp` declares, which `objc.lisp` -- the whole
//! vocabulary, the same file every target runs -- is written over (`.kb/objc.md`, "The
//! new base"). Nothing here decides a rule: every conversion and every ownership decision
//! is Lisp's. What the host does is what only the host can: look the runtime up, make one
//! call by an encoding the caller hands over, and count the references a pointer value
//! holds, releasing the collector's share when the value dies.
//!
//! The same thread discipline as the rest of this module: the module runs on thread 0, a
//! send runs inside a host call (`Entered`), and the queue of dead values' releases runs
//! only from the outermost host call.

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{CString, c_char};
use std::rc::Rc;
use std::sync::atomic::{AtomicI64, Ordering};

use wasmtime::{Caller, ExternRef, Linker, Rooted};
use wasmtime_wasi::p1::WasiP1Ctx;

use super::call::{Call, Leaf, read_leaf};
use super::encoding::{self, Kind};
use super::{
    Api, Entered, MODULE, PENDING, api, cstring, is_application, memory_string, release_pending, return_string, text,
    with,
};

/// One argument as the library pushed it: raw, marshalled by the encoding at the send.
#[derive(Clone, Debug)]
pub(super) enum Raw {
    Int(i64),
    Float(f64),
    Str(String),
    Leaves(Vec<Leaf>),
}

impl Raw {
    fn describe(&self) -> &'static str {
        match self {
            Raw::Int(_) => "an integer",
            Raw::Float(_) => "a float",
            Raw::Str(_) => "a string",
            Raw::Leaves(_) => "a struct",
        }
    }
}

/// What the last send answered, fetched by the `p_result_*` imports.
#[derive(Default)]
struct Answer {
    int: i64,
    float: f64,
    string: String,
    leaves: Vec<Leaf>,
}

#[derive(Default)]
struct Prim {
    args: Vec<Raw>,
    pending: Option<(usize, Vec<Leaf>)>,
    answer: Answer,
    error: String,
}

thread_local! {
    static PRIM: RefCell<Prim> = RefCell::new(Prim::default());
    /// Parsed encodings by spelling.
    static PARSED: RefCell<HashMap<String, Rc<encoding::Encoding>>> = RefCell::new(HashMap::new());
}

fn prim<T>(f: impl FnOnce(&mut Prim) -> T) -> T {
    PRIM.with(|p| f(&mut p.borrow_mut()))
}

fn push(raw: Raw) {
    prim(|p| {
        if let Some((n, mut leaves)) = p.pending.take() {
            match raw {
                Raw::Int(i) => leaves.push(Leaf::Int(i)),
                Raw::Float(f) => leaves.push(Leaf::Float(f)),
                _ => {}
            }
            if leaves.len() >= n {
                p.args.push(Raw::Leaves(leaves));
            } else {
                p.pending = Some((n, leaves));
            }
            return;
        }
        p.args.push(raw);
    })
}

/// Result kinds `p_send` answers (negative: failed, the reason in `p_error`).
mod kind {
    pub const NIL: i32 = 0;
    pub const INT: i32 = 1;
    pub const FLOAT: i32 = 2;
    pub const STRING: i32 = 3;
    pub const LEAVES: i32 = 4;
    pub const FAILED: i32 = -1;
}

/// `%send` mode bit: retain an object result before the call's pool drains.
const RETAIN_RESULT: i32 = 1;
/// `%send` mode bit: answer a C-string result as its address.
const RAW_CSTRING: i32 = 2;

fn fail(message: String) -> i32 {
    prim(|p| p.error = message);
    kind::FAILED
}

/// The reason the last import failed, for `p_error`.
pub(super) fn set_error(message: String) {
    prim(|p| p.error = message);
}

/// What the library pushed since the last send: the answer of a method it ran.
pub(super) fn take_args() -> Vec<Raw> {
    prim(|p| {
        p.pending = None;
        std::mem::take(&mut p.args)
    })
}

/// Saves the arguments pushed for a send in progress, around a method the send made
/// Objective-C call: that method's own sends push and take their own.
pub(super) fn save_args() -> (Vec<Raw>, Option<(usize, Vec<Leaf>)>) {
    prim(|p| (std::mem::take(&mut p.args), p.pending.take()))
}

pub(super) fn restore_args(saved: (Vec<Raw>, Option<(usize, Vec<Leaf>)>)) {
    prim(|p| {
        p.args = saved.0;
        p.pending = saved.1;
    })
}

fn parsed(types: &str) -> Result<Rc<encoding::Encoding>, String> {
    if let Some(e) = PARSED.with(|c| c.borrow().get(types).cloned()) {
        return Ok(e);
    }
    let e = Rc::new(encoding::parse(types)?);
    PARSED.with(|c| c.borrow_mut().insert(types.to_owned(), e.clone()));
    Ok(e)
}

fn leaf_of(raw: &Raw) -> Option<Leaf> {
    match raw {
        Raw::Int(i) => Some(Leaf::Int(*i)),
        Raw::Float(f) => Some(Leaf::Float(*f)),
        _ => None,
    }
}

/// The call itself, inside the caller's pool: the encoding describes every argument, the
/// variadic ones included; `fixed` is the number of fixed method arguments of a variadic
/// call, else negative.
fn send(api: &Api, receiver: usize, superclass: usize, sel: usize, types: &str, fixed: i32, mode: i32) -> i32 {
    let args = prim(|p| std::mem::take(&mut p.args));
    let name = text(unsafe { (api.sel_get_name)(sel) });
    let encoding = match parsed(types) {
        Ok(e) => e,
        Err(e) => return fail(e),
    };
    if encoding.args.len() < 2 {
        return fail(format!("type encoding '{types}' has no receiver and selector"));
    }
    let declared = encoding.args.len() - 2;
    if args.len() != declared {
        return fail(format!("{name} takes {declared} argument(s), got {}", args.len()));
    }
    let fixed = if fixed < 0 { declared } else { fixed as usize };
    // A super send passes struct objc_super { receiver, class the lookup starts in }
    // where a send passes the receiver.
    let sup = [receiver, superclass];
    let mut call = if superclass != 0 {
        let mut call = Call::new(api.msg_send_super, &encoding.ret);
        call.push(&encoding.args[0], &[Leaf::Int(sup.as_ptr() as i64)]);
        call
    } else {
        let mut call = Call::new(api.msg_send, &encoding.ret);
        call.push(&encoding.args[0], &[Leaf::Int(receiver as i64)]);
        call
    };
    call.push(&encoding.args[1], &[Leaf::Int(sel as i64)]);
    // Storage an argument points into, alive past the call.
    let mut strings: Vec<CString> = Vec::new();
    for (i, arg) in args.iter().enumerate() {
        let ty = &encoding.args[i + 2];
        let leaves = match (ty.kind, arg) {
            (Kind::Struct, Raw::Leaves(l)) if l.len() == ty.leaves.len() => l.clone(),
            (Kind::Struct, _) => {
                return fail(format!(
                    "{name}: argument {} must be a struct of {} numbers, got {}",
                    i + 1,
                    ty.leaves.len(),
                    arg.describe()
                ));
            }
            (Kind::Object, Raw::Str(s)) => match api.ns_string(s) {
                Ok(o) => vec![Leaf::Int(o as i64)],
                Err(e) => return fail(e),
            },
            (Kind::CString, Raw::Str(s)) => match cstring(s) {
                Ok(c) => {
                    let p = c.as_ptr() as i64;
                    strings.push(c);
                    vec![Leaf::Int(p)]
                }
                Err(e) => return fail(e),
            },
            (_, Raw::Int(_) | Raw::Float(_)) => vec![leaf_of(arg).unwrap_or(Leaf::Int(0))],
            _ => {
                return fail(format!("{name}: argument {} cannot be {}", i + 1, arg.describe()));
            }
        };
        if i < fixed {
            call.push(ty, &leaves);
        } else {
            call.push_variadic(leaves[0]);
        }
    }
    // SAFETY: the shape is the caller's encoding, laid out by the convention.
    let leaves = unsafe { call.invoke() };
    drop(strings);
    let _ = sup;
    answer(api, &encoding.ret, &leaves, mode)
}

/// Makes a value of a type the answer the `p_result_*` imports fetch, and answers its
/// result kind: what a send answers, a memory read, a method's argument.
pub(super) fn answer(api: &Api, ty: &encoding::Type, leaves: &[Leaf], mode: i32) -> i32 {
    let first = leaves.first().copied();
    match (ty.kind, first) {
        (Kind::Void, _) | (_, None) => kind::NIL,
        (Kind::Struct, _) => {
            prim(|p| p.answer.leaves = leaves.to_vec());
            kind::LEAVES
        }
        (Kind::CString, Some(Leaf::Int(p))) if mode & RAW_CSTRING == 0 => {
            if p == 0 {
                return kind::NIL;
            }
            let s = text(p as *const c_char);
            prim(|st| st.answer.string = s);
            kind::STRING
        }
        (Kind::Object, Some(Leaf::Int(a))) => {
            if a != 0 && mode & RETAIN_RESULT != 0 {
                // SAFETY: a live object answered by the send, retained inside its pool.
                unsafe { (api.retain)(a as usize) };
            }
            prim(|p| p.answer.int = a);
            kind::INT
        }
        (_, Some(Leaf::Int(i))) => {
            prim(|p| p.answer.int = i);
            kind::INT
        }
        (_, Some(Leaf::Float(f))) => {
            prim(|p| p.answer.float = f);
            kind::FLOAT
        }
    }
}

/// The references a pointer value holds, as the host data of the `externref` the value
/// keeps (`p_new_handle`): [collector's, explicit retains, a pool's]. When the value dies
/// wasmtime's collector drops this, which queues the collector's share for release on
/// thread 0 (`release_pending`), as the old base's `Owned` does for its one reference.
struct Handle {
    object: usize,
    counts: [AtomicI64; 3],
}

impl Drop for Handle {
    fn drop(&mut self) {
        let held = self.counts[0].swap(0, Ordering::SeqCst);
        if held > 0
            && let Ok(mut pending) = PENDING.lock()
        {
            for _ in 0..held {
                pending.push(self.object);
            }
        }
    }
}

/// Binds every `p_*` import.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    fn runtime() -> wasmtime::Result<&'static Api> {
        api().map_err(|e| wasmtime::Error::msg(format!("objc: {e}")))
    }
    linker.func_wrap(MODULE, "p_error", |mut caller: Caller<'_, WasiP1Ctx>| {
        let e = prim(|p| std::mem::take(&mut p.error));
        return_string(&mut caller, &e)
    })?;
    linker.func_wrap(
        MODULE,
        "p_get_class",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| -> wasmtime::Result<i64> {
            let name = memory_string(&mut caller, p, n)?;
            Ok(runtime()?.class(&name).map(|c| c as i64).unwrap_or(0))
        },
    )?;
    linker.func_wrap(MODULE, "p_class_name", |mut caller: Caller<'_, WasiP1Ctx>, cls: i64| {
        let api = runtime()?;
        // SAFETY: a class (or metaclass) the library holds.
        let name = text(unsafe { (api.class_get_name)(cls as usize) });
        return_string(&mut caller, &name)
    })?;
    linker.func_wrap(MODULE, "p_object_class", |object: i64| -> wasmtime::Result<i64> {
        // SAFETY: a live object or class.
        Ok(unsafe { (runtime()?.object_get_class)(object as usize) } as i64)
    })?;
    linker.func_wrap(MODULE, "p_class_p", |object: i64| -> wasmtime::Result<i32> {
        // SAFETY: a live object or class.
        Ok(unsafe { (runtime()?.object_is_class)(object as usize) } as i32)
    })?;
    linker.func_wrap(
        MODULE,
        "p_register_selector",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| -> wasmtime::Result<i64> {
            let name = memory_string(&mut caller, p, n)?;
            runtime()?.sel(&name).map(|s| s as i64).map_err(wasmtime::Error::msg)
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_selector_name",
        |mut caller: Caller<'_, WasiP1Ctx>, sel: i64| {
            // SAFETY: an interned selector.
            let name = text(unsafe { (runtime()?.sel_get_name)(sel as usize) });
            return_string(&mut caller, &name)
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_method_types",
        |mut caller: Caller<'_, WasiP1Ctx>, cls: i64, sel: i64| {
            let types = runtime()?.raw_encoding(cls as usize, sel as usize).unwrap_or_default();
            return_string(&mut caller, &types)
        },
    )?;
    linker.func_wrap(MODULE, "p_arg_int", |n: i64| push(Raw::Int(n)))?;
    linker.func_wrap(MODULE, "p_arg_float", |f: f64| push(Raw::Float(f)))?;
    linker.func_wrap(
        MODULE,
        "p_arg_string",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| {
            let text = memory_string(&mut caller, p, n)?;
            push(Raw::Str(text));
            wasmtime::Result::<()>::Ok(())
        },
    )?;
    linker.func_wrap(MODULE, "p_arg_leaves", |n: i32| {
        prim(|p| {
            if n <= 0 {
                p.args.push(Raw::Leaves(Vec::new()));
            } else {
                p.pending = Some((n as usize, Vec::new()));
            }
        })
    })?;
    linker.func_wrap(
        MODULE,
        "p_send",
        |mut caller: Caller<'_, WasiP1Ctx>,
         receiver: i64,
         sel: i64,
         types_p: i32,
         types_n: i32,
         fixed: i32,
         mode: i32|
         -> wasmtime::Result<i32> {
            let types = memory_string(&mut caller, types_p, types_n)?;
            let api = runtime()?;
            let entered = Entered::new(&mut caller);
            let answer = if receiver == 0 {
                prim(|p| p.args.clear());
                kind::NIL
            } else if starts_application(api, receiver as usize, sel as usize) {
                // -[NSApplication run] never returns, and thread 0 is the module's: the
                // host owns the loop instead (`pump`), as for the old base.
                prim(|p| p.args.clear());
                with(|s| s.app_started = true);
                let _ = api.send(receiver as usize, "activateIgnoringOtherApps:", vec![super::Arg::True]);
                kind::NIL
            } else {
                api.with_pool(|| send(api, receiver as usize, 0, sel as usize, &types, fixed, mode))
            };
            if entered.outermost() {
                release_pending(api);
            }
            Ok(answer)
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_send_super",
        |mut caller: Caller<'_, WasiP1Ctx>,
         receiver: i64,
         superclass: i64,
         sel: i64,
         types_p: i32,
         types_n: i32,
         fixed: i32,
         mode: i32|
         -> wasmtime::Result<i32> {
            let types = memory_string(&mut caller, types_p, types_n)?;
            let api = runtime()?;
            let entered = Entered::new(&mut caller);
            let answer = api.with_pool(|| {
                send(api, receiver as usize, superclass as usize, sel as usize, &types, fixed, mode)
            });
            if entered.outermost() {
                release_pending(api);
            }
            Ok(answer)
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_peek",
        |mut caller: Caller<'_, WasiP1Ctx>, address: i64, types_p: i32, types_n: i32| -> wasmtime::Result<i32> {
            let types = memory_string(&mut caller, types_p, types_n)?;
            let api = runtime()?;
            let ty = match parsed(&types) {
                Ok(e) => e.ret.clone(),
                Err(e) => return Ok(fail(e)),
            };
            // SAFETY: the address of a value of this type the library computed (an
            // instance variable of a live object).
            let leaves = unsafe { peek(address as usize, &ty) };
            // An object read is retained for the value the library makes of it.
            Ok(answer(api, &ty, &leaves, RETAIN_RESULT))
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_poke",
        |mut caller: Caller<'_, WasiP1Ctx>, address: i64, types_p: i32, types_n: i32| -> wasmtime::Result<i32> {
            let types = memory_string(&mut caller, types_p, types_n)?;
            let ty = match parsed(&types) {
                Ok(e) => e.ret.clone(),
                Err(e) => return Ok(fail(e)),
            };
            let leaves = match take_args().first() {
                Some(Raw::Leaves(l)) if ty.kind == Kind::Struct && l.len() == ty.leaves.len() => l.clone(),
                Some(raw) if ty.kind != Kind::Struct && leaf_of(raw).is_some() => {
                    vec![leaf_of(raw).unwrap_or(Leaf::Int(0))]
                }
                other => {
                    return Ok(fail(format!(
                        "a memory write of type {types} cannot take {}",
                        other.map(Raw::describe).unwrap_or("nothing")
                    )));
                }
            };
            // SAFETY: as for p_peek.
            unsafe { poke(address as usize, &ty, &leaves) };
            Ok(kind::NIL)
        },
    )?;
    linker.func_wrap(MODULE, "p_result_int", || prim(|p| p.answer.int))?;
    linker.func_wrap(MODULE, "p_result_float", || prim(|p| p.answer.float))?;
    linker.func_wrap(MODULE, "p_result_string", |mut caller: Caller<'_, WasiP1Ctx>| {
        let t = prim(|p| std::mem::take(&mut p.answer.string));
        return_string(&mut caller, &t)
    })?;
    linker.func_wrap(MODULE, "p_result_count", || prim(|p| p.answer.leaves.len() as i32))?;
    linker.func_wrap(MODULE, "p_result_leaf_float_p", |i: i32| {
        prim(|p| matches!(p.answer.leaves.get(i as usize), Some(Leaf::Float(_))) as i32)
    })?;
    linker.func_wrap(MODULE, "p_result_leaf_int", |i: i32| {
        prim(|p| match p.answer.leaves.get(i as usize) {
            Some(Leaf::Int(n)) => *n,
            _ => 0,
        })
    })?;
    linker.func_wrap(MODULE, "p_result_leaf_float", |i: i32| {
        prim(|p| match p.answer.leaves.get(i as usize) {
            Some(Leaf::Float(f)) => *f,
            _ => 0.0,
        })
    })?;
    linker.func_wrap(
        MODULE,
        "p_new_handle",
        |mut caller: Caller<'_, WasiP1Ctx>, object: i64, gc: i64| -> wasmtime::Result<Option<Rooted<ExternRef>>> {
            let handle = Handle {
                object: object as usize,
                counts: [AtomicI64::new(gc), AtomicI64::new(0), AtomicI64::new(0)],
            };
            Ok(Some(ExternRef::new(&mut caller, handle)?))
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_refs",
        |caller: Caller<'_, WasiP1Ctx>,
         handle: Option<Rooted<ExternRef>>,
         which: i32,
         delta: i64|
         -> wasmtime::Result<i64> {
            let Some(handle) = handle else {
                wasmtime::bail!("objc: not a reference handle");
            };
            let Some(handle) = handle.data(&caller)?.and_then(|d| d.downcast_ref::<Handle>()) else {
                wasmtime::bail!("objc: not a reference handle");
            };
            let Some(count) = handle.counts.get(which as usize) else {
                wasmtime::bail!("objc: no count {which}");
            };
            let mut current = count.load(Ordering::SeqCst);
            loop {
                let next = current + delta;
                if next < 0 {
                    return Ok(-1);
                }
                match count.compare_exchange(current, next, Ordering::SeqCst, Ordering::SeqCst) {
                    Ok(_) => return Ok(next),
                    Err(actual) => current = actual,
                }
            }
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_load_module",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| -> wasmtime::Result<i32> {
            let path = memory_string(&mut caller, p, n)?;
            Ok(match super::load_module(&path) {
                Ok(()) => 1,
                Err(e) => {
                    prim(|st| st.error = e);
                    0
                }
            })
        },
    )?;
    linker.func_wrap(MODULE, "p_initialize", || -> i32 {
        match api() {
            Ok(_) => 1,
            Err(e) => {
                prim(|st| st.error = format!("Objective-C is not available: {e}"));
                0
            }
        }
    })?;
    linker.func_wrap(
        MODULE,
        "p_pump",
        |mut caller: Caller<'_, WasiP1Ctx>, seconds: f64| -> wasmtime::Result<()> {
            let api = runtime()?;
            let entered = Entered::new(&mut caller);
            super::pump(api, seconds, entered.outermost());
            Ok(())
        },
    )?;
    Ok(())
}

/// Whether a send is `appkit`'s request to start `-[NSApplication run]` on thread 0: a
/// `performSelectorOnMainThread:withObject:waitUntilDone:` of `run` to the application.
fn starts_application(api: &Api, receiver: usize, sel: usize) -> bool {
    // SAFETY: an interned selector.
    let name = text(unsafe { (api.sel_get_name)(sel) });
    if name != "performSelectorOnMainThread:withObject:waitUntilDone:" {
        return false;
    }
    let first = prim(|p| p.args.first().cloned());
    let Some(Raw::Int(action)) = first else {
        return false;
    };
    // SAFETY: the selector argument the library resolved.
    text(unsafe { (api.sel_get_name)(action as usize) }) == "run" && is_application(api, receiver)
}

/// The leaves of a value of a type in memory.
///
/// # Safety
/// `address` holds a value of `ty`.
unsafe fn peek(address: usize, ty: &encoding::Type) -> Vec<Leaf> {
    if ty.kind == Kind::Struct {
        let (offsets, _, _) = ty.layout();
        return ty
            .leaves
            .iter()
            .zip(offsets)
            .map(|(k, o)| {
                // SAFETY: inside the value, per the caller.
                let bytes = unsafe { std::slice::from_raw_parts((address + o) as *const u8, k.size()) };
                read_leaf(*k, false, bytes)
            })
            .collect();
    }
    // SAFETY: the value, per the caller.
    let bytes = unsafe { std::slice::from_raw_parts(address as *const u8, ty.kind.size()) };
    vec![read_leaf(ty.kind, ty.unsigned, bytes)]
}

/// Writes a value of a type to memory, from its leaves.
///
/// # Safety
/// `address` holds a value of `ty`.
unsafe fn poke(address: usize, ty: &encoding::Type, leaves: &[Leaf]) {
    let write = |at: usize, kind: Kind, leaf: Leaf| {
        let bits = leaf.bits(kind).to_le_bytes();
        // SAFETY: inside the value, per the caller.
        unsafe { std::ptr::copy_nonoverlapping(bits.as_ptr(), at as *mut u8, kind.size()) };
    };
    if ty.kind == Kind::Struct {
        let (offsets, _, _) = ty.layout();
        for ((k, o), leaf) in ty.leaves.iter().zip(offsets).zip(leaves) {
            write(address + o, *k, *leaf);
        }
    } else if let Some(leaf) = leaves.first() {
        write(address, ty.kind, *leaf);
    }
}
