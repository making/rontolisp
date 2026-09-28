//! Blocks a `--native` program makes from Lisp functions (`objc-block.lisp`,
//! `.kb/objc.md`, "Blocks and C functions"), the calls of C functions and of blocks
//! through an address, and `dlsym` for `fli:define-foreign-function`.
//!
//! A block is the Blocks ABI's literal: `isa = &_NSConcreteStackBlock`, the flags
//! `BLOCK_HAS_COPY_DISPOSE | BLOCK_HAS_SIGNATURE`, the invoke function, a descriptor, and
//! after them an ID this runner registers the block under -- carried INSIDE the literal
//! because `_Block_copy` copies it to the heap whenever a callee keeps the block, and the
//! copy must still find its function. The copy and dispose helpers count the HOLDERS of
//! an ID (the Lisp value that made the block, plus every live copy); when none is left
//! the ID is dead and the module forgets the function (`p_block_reap`).
//!
//! The invoke function is the one assembly entry every method IMP runs through
//! (`class::rl_objc_block_imp`), which tells the two apart by the descriptor and hands a
//! program's block to [`invoke`]. The module runs on thread 0 and a wasmtime `Store`
//! cannot be entered from another thread, so a block arriving INSIDE a host call on
//! thread 0 -- `enumerateObjectsUsingBlock:`, `dispatch_sync` -- re-enters the module
//! through that call's `Caller`, while one arriving on another thread (a libdispatch
//! worker: a completion handler, `dispatch_async`) cannot run there. A `void` block is
//! QUEUED for thread 0 -- its object arguments retained, its C strings copied -- and runs
//! at the next turn of thread 0's event loop (`pump`, which `sleep` is); the main
//! dispatch queue is poked so that a turn in progress returns for it. A block answering
//! a value has nobody to answer it on that thread: it is refused, printed, and answers
//! zero.

use std::collections::{HashMap, VecDeque};
use std::ffi::{CStr, CString, c_char, c_void};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard, OnceLock, PoisonError};

use wasmtime::{Caller, Linker};
use wasmtime_wasi::p1::WasiP1Ctx;

use super::call::Leaf;
use super::class::{self, ImpFrame, Reader};
use super::encoding::{self, Encoding, Kind, Type};
use super::prim;
use super::{Api, CALLER, Entered, Id, MODULE, RTLD_DEFAULT, api, cstring, dlsym, memory_string, release_pending};

const BLOCK_HAS_COPY_DISPOSE: i32 = 1 << 25;
const BLOCK_HAS_SIGNATURE: i32 = 1 << 30;

/// A block a program made: the Blocks ABI's header, then this runner's ID.
#[repr(C)]
struct LispBlock {
    isa: usize,
    flags: i32,
    reserved: i32,
    invoke: usize,
    descriptor: *const Descriptor,
    id: u64,
}

/// `Block_descriptor_1`, `_2` (copy / dispose: `BLOCK_HAS_COPY_DISPOSE`) and `_3`
/// (signature / layout: `BLOCK_HAS_SIGNATURE`) laid end to end, as libclosure reads them.
#[repr(C)]
struct Descriptor {
    reserved: u64,
    size: u64,
    copy: extern "C" fn(*mut LispBlock, *const LispBlock),
    dispose: extern "C" fn(*const LispBlock),
    signature: *const c_char,
    layout: usize,
}

/// A registered block: how it is called, the module's function, and its holders.
struct Entry {
    encoding: Arc<Encoding>,
    closure: i32,
    holders: i64,
}

/// A `void` block called on another thread, waiting for thread 0.
struct Queued {
    id: u64,
    closure: i32,
    args: Vec<(Type, Vec<Leaf>)>,
    /// The object arguments, retained while the call waits.
    retained: Vec<Id>,
    /// The C string arguments, copied: the caller's are gone by the time it runs.
    strings: Vec<CString>,
}

static BLOCKS: LazyLock<Mutex<HashMap<u64, Entry>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
/// The functions of the IDs that died, for the module to forget.
static DEAD: Mutex<Vec<i32>> = Mutex::new(Vec::new());
static QUEUE: Mutex<VecDeque<Queued>> = Mutex::new(VecDeque::new());
/// One descriptor per signature, alive for the process (as addresses: the pointers they
/// hold are not `Send`).
static DESCRIPTORS: LazyLock<Mutex<HashMap<String, usize>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// `dispatch_async_f` and `&_dispatch_main_q`, to wake thread 0 for a queued block.
static MAIN_QUEUE: OnceLock<Option<(usize, usize)>> = OnceLock::new();

unsafe extern "C" {
    fn pthread_main_np() -> i32;
}

/// A lock whose holder panicked is still the table: nothing here may unwind into the
/// helpers' or a block's native caller.
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Adds `delta` to an ID's holders; at none the ID dies and its function is queued for
/// the module to forget.
fn hold(id: u64, delta: i64) {
    let dead = {
        let mut blocks = lock(&BLOCKS);
        match blocks.get_mut(&id) {
            Some(entry) => {
                entry.holders += delta;
                if entry.holders <= 0 {
                    blocks.remove(&id).map(|e| e.closure)
                } else {
                    None
                }
            }
            None => None,
        }
    };
    if let Some(closure) = dead {
        lock(&DEAD).push(closure);
    }
}

extern "C" fn copy_helper(destination: *mut LispBlock, _source: *const LispBlock) {
    if !destination.is_null() {
        // SAFETY: the copy libclosure just made of one of our literals, ID included.
        hold(unsafe { (*destination).id }, 1);
    }
}

extern "C" fn dispose_helper(block: *const LispBlock) {
    if !block.is_null() {
        // SAFETY: a copy of one of our literals libclosure is destroying.
        hold(unsafe { (*block).id }, -1);
    }
}

fn descriptor(signature: &str) -> Result<*const Descriptor, String> {
    let mut descriptors = lock(&DESCRIPTORS);
    if let Some(d) = descriptors.get(signature) {
        return Ok(*d as *const Descriptor);
    }
    let text = cstring(signature)?;
    let d: &'static Descriptor = Box::leak(Box::new(Descriptor {
        reserved: 0,
        size: std::mem::size_of::<LispBlock>() as u64,
        copy: copy_helper,
        dispose: dispose_helper,
        signature: text.into_raw(),
        layout: 0,
    }));
    descriptors.insert(signature.to_owned(), d as *const Descriptor as usize);
    Ok(d)
}

fn make(api: &Api, types: &str, signature: &str, closure: i32) -> Result<usize, String> {
    let encoding = Arc::new(encoding::parse(types)?);
    if !encoding.args.first().is_some_and(|t| t.kind.is_address()) {
        return Err(format!(
            "a block's encoding starts with the block itself, got '{types}'"
        ));
    }
    let descriptor = descriptor(signature)?;
    let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
    lock(&BLOCKS).insert(
        id,
        Entry {
            encoding,
            closure,
            holders: 1,
        },
    );
    let block = Box::new(LispBlock {
        isa: api.stack_block,
        flags: BLOCK_HAS_COPY_DISPOSE | BLOCK_HAS_SIGNATURE,
        reserved: 0,
        invoke: class::block_entry(),
        descriptor,
        id,
    });
    Ok(Box::into_raw(block) as usize)
}

/// Gives up the maker's hold and frees the literal (`BLOCK_NEEDS_FREE` is clear, so
/// libclosure never frees it); a copy keeps the function alive.
fn free(address: usize) {
    if address == 0 {
        return;
    }
    // SAFETY: a literal `make` boxed, handed back once by the library.
    let block = unsafe { Box::from_raw(address as *mut LispBlock) };
    hold(block.id, -1);
}

/// A program's block called, from `class::rl_objc_block_dispatch`, on whatever thread
/// called it.
pub(super) fn invoke(frame: &mut ImpFrame) {
    let block = frame.x[0] as *const LispBlock;
    // SAFETY: one of our literals or a copy of one: the header and the ID.
    let id = unsafe { (*block).id };
    let entry = lock(&BLOCKS).get(&id).map(|e| (e.encoding.clone(), e.closure));
    let Some((encoding, closure)) = entry else {
        eprintln!("objc: error in a callback: a block was called after its last holder let go of it (block {id})");
        frame.x[0] = 0;
        frame.x[1] = 0;
        return;
    };
    let ret = encoding.ret.clone();
    let mut reader = Reader::new(frame, 1);
    let args: Vec<(Type, Vec<Leaf>)> = encoding.args[1..]
        .iter()
        .map(|ty| (ty.clone(), reader.read(ty)))
        .collect();
    if !CALLER.get().is_null() {
        // Thread 0, inside a host call: the module can be entered.
        let leaves = class::run_closure(closure, 0, args);
        if let (Kind::Object, Some(Leaf::Int(a))) = (ret.kind, leaves.first())
            && *a != 0
            && let Ok(api) = api()
        {
            // A block's object answer is its caller's at +0, like a method's outside
            // the owning families.
            // SAFETY: a live object the block answered.
            unsafe {
                (api.retain)(*a as Id);
                (api.autorelease)(*a as Id);
            }
        }
        class::write_result(frame, &ret, &leaves);
    } else if ret.kind == Kind::Void {
        enqueue(id, closure, args);
    } else {
        // SAFETY: a plain query of the calling thread.
        let main = unsafe { pthread_main_np() } == 1;
        eprintln!(
            "objc: error in a callback: a block answering a value was called on {}, where a --native program cannot run; it answers zero",
            if main {
                "the main thread outside the program"
            } else {
                "another thread"
            }
        );
        class::write_result(frame, &ret, &[]);
    }
}

fn enqueue(id: u64, closure: i32, mut args: Vec<(Type, Vec<Leaf>)>) {
    let Ok(api) = api() else { return };
    let mut retained = Vec::new();
    let mut strings = Vec::new();
    for (ty, leaves) in args.iter_mut() {
        match (ty.kind, leaves.first().copied()) {
            (Kind::Object, Some(Leaf::Int(a))) if a != 0 => {
                // SAFETY: a live object argument of the call in progress.
                unsafe { (api.retain)(a as Id) };
                retained.push(a as Id);
            }
            (Kind::CString, Some(Leaf::Int(p))) if p != 0 => {
                // SAFETY: a NUL-terminated argument of the call in progress.
                let copy = unsafe { CStr::from_ptr(p as *const c_char) }.to_owned();
                leaves[0] = Leaf::Int(copy.as_ptr() as i64);
                strings.push(copy);
            }
            _ => {}
        }
    }
    hold(id, 1);
    lock(&QUEUE).push_back(Queued {
        id,
        closure,
        args,
        retained,
        strings,
    });
    // Poke thread 0's event loop, so that a turn in progress returns and drains the queue.
    let poke = MAIN_QUEUE.get_or_init(|| {
        // SAFETY: NUL-terminated names looked up in every loaded image.
        let (f, q) = unsafe {
            (
                dlsym(RTLD_DEFAULT, c"dispatch_async_f".as_ptr()),
                dlsym(RTLD_DEFAULT, c"_dispatch_main_q".as_ptr()),
            )
        };
        (!f.is_null() && !q.is_null()).then_some((f as usize, q as usize))
    });
    if let Some((f, q)) = *poke {
        // SAFETY: dispatch_async_f(queue, context, function), as libdispatch declares it.
        let dispatch_async_f: unsafe extern "C" fn(usize, *mut c_void, extern "C" fn(*mut c_void)) =
            unsafe { std::mem::transmute(f) };
        // SAFETY: the main queue, no context, a function that only drains the queue.
        unsafe { dispatch_async_f(q, std::ptr::null_mut(), woken) };
    }
}

/// Runs on thread 0 when the main dispatch queue drains -- inside a turn of the event
/// loop, so inside a host call.
extern "C" fn woken(_context: *mut c_void) {
    if let Ok(api) = api() {
        drain(api);
    }
}

/// Runs the queued `void` blocks, on thread 0, inside a host call (a turn of the event
/// loop). Does nothing outside one: the queue waits for the next.
pub(super) fn drain(api: &Api) {
    if CALLER.get().is_null() {
        return;
    }
    loop {
        let next = lock(&QUEUE).pop_front();
        let Some(queued) = next else { break };
        let _ = class::run_closure(queued.closure, 0, queued.args);
        for object in queued.retained {
            // SAFETY: the reference taken when the call was queued.
            unsafe { (api.release)(object) };
        }
        drop(queued.strings);
        hold(queued.id, -1);
    }
}

/// Binds the blocks' and C functions' imports.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    fn runtime() -> wasmtime::Result<&'static Api> {
        api().map_err(|e| wasmtime::Error::msg(format!("objc: {e}")))
    }
    linker.func_wrap(
        MODULE,
        "p_make_block",
        |mut caller: Caller<'_, WasiP1Ctx>,
         types_p: i32,
         types_n: i32,
         signature_p: i32,
         signature_n: i32,
         closure: i32|
         -> wasmtime::Result<i64> {
            let types = memory_string(&mut caller, types_p, types_n)?;
            let signature = memory_string(&mut caller, signature_p, signature_n)?;
            let api = runtime()?;
            Ok(match make(api, &types, &signature, closure) {
                Ok(address) => address as i64,
                Err(e) => {
                    prim::set_error(e);
                    0
                }
            })
        },
    )?;
    linker.func_wrap(MODULE, "p_free_block", |address: i64| free(address as usize))?;
    linker.func_wrap(MODULE, "p_block_reap", || -> i32 { lock(&DEAD).pop().unwrap_or(-1) })?;
    linker.func_wrap(
        MODULE,
        "p_call_function",
        |mut caller: Caller<'_, WasiP1Ctx>,
         function: i64,
         types_p: i32,
         types_n: i32,
         fixed: i32,
         mode: i32|
         -> wasmtime::Result<i32> {
            let types = memory_string(&mut caller, types_p, types_n)?;
            let api = runtime()?;
            // A block the function calls on this thread (dispatch_sync) re-enters the
            // module through this call.
            let entered = Entered::new(&mut caller);
            let answer = api.with_pool(|| prim::call_function(api, function as usize, &types, fixed, mode));
            if entered.outermost() {
                release_pending(api);
            }
            Ok(answer)
        },
    )?;
    linker.func_wrap(
        MODULE,
        "p_symbol",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| -> wasmtime::Result<i64> {
            let name = memory_string(&mut caller, p, n)?;
            let c = cstring(&name).map_err(wasmtime::Error::msg)?;
            // SAFETY: a NUL-terminated name looked up in every loaded image.
            Ok(unsafe { dlsym(RTLD_DEFAULT, c.as_ptr()) } as i64)
        },
    )?;
    Ok(())
}
