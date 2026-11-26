//! The Objective-C host of a `--native` output: the `rlobjc` imports the new `objc`
//! base's primitive layer (`objc-native-primitives.lisp`) is written over -- the `p_*`
//! imports of [`prim`], [`class`] and [`block`] -- and the upcalls back into the module
//! through its `rlobjc_method` export (`.kb/objc.md`, "--native" and "The primitive layer").
//!
//! THE design decision: the module runs ON thread 0. AppKit belongs to the process's first
//! thread and a wasmtime `Store` is not `Sync`, so a callback arriving on thread 0 while
//! the module ran on another thread could not enter it -- the other thread's stack holds
//! the store, and a nested call from a second thread would leave that stack's GC roots
//! unwalked. So there is no hop at all: every send is made from thread 0, and a callback
//! (a button's action, a timer, a delegate method, a block) arrives INSIDE a host call --
//! a send that made AppKit call out, or [`pump`], which is what `sleep` becomes -- and re-enters
//! the module through the `Caller` that host call holds ([`CALLER`]).
//!
//! The consequence the JVM does not have: nothing drains thread 0's run loop while the
//! module computes, so the program's `sleep` IS the event loop (`pump`), and
//! `-[NSApplication run]`, which never returns, is never started: `appkit::%app`'s request
//! for it is answered by marking the application as started, after which `pump` fetches
//! and dispatches events itself.
//!
//! Only on macOS / AArch64 (the one macOS release platform; `call` is Apple's AArch64
//! convention). The frameworks are opened on the first `rlobjc` call, so an output that
//! makes none starts exactly as before.

mod block;
mod call;
mod class;
mod encoding;
mod prim;

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char, c_void};
use std::rc::Rc;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use call::{Call, Leaf, Raised};
use encoding::Kind;
use wasmtime::{Caller, Extern, Instance, Linker, Store, TypedFunc};
use wasmtime_wasi::p1::WasiP1Ctx;

/// The import module the library's `rontolisp:wasm-import`s name.
pub const MODULE: &str = "rlobjc";

type Id = usize;

/// The Objective-C runtime and the few C functions around it, resolved once.
struct Api {
    msg_send: usize,
    msg_send_super: usize,
    /// `&_NSConcreteGlobalBlock`: the isa of the block a defined method's IMP is made from.
    global_block: usize,
    /// `&_NSConcreteStackBlock`: the isa of a block a program makes (`block`).
    stack_block: usize,
    get_class: unsafe extern "C" fn(*const c_char) -> Id,
    sel_register_name: unsafe extern "C" fn(*const c_char) -> Id,
    sel_get_name: unsafe extern "C" fn(Id) -> *const c_char,
    object_get_class: unsafe extern "C" fn(Id) -> Id,
    object_is_class: unsafe extern "C" fn(Id) -> bool,
    class_get_name: unsafe extern "C" fn(Id) -> *const c_char,
    class_get_superclass: unsafe extern "C" fn(Id) -> Id,
    class_get_instance_method: unsafe extern "C" fn(Id, Id) -> Id,
    method_get_type_encoding: unsafe extern "C" fn(Id) -> *const c_char,
    allocate_class_pair: unsafe extern "C" fn(Id, *const c_char, usize) -> Id,
    register_class_pair: unsafe extern "C" fn(Id),
    class_add_protocol: unsafe extern "C" fn(Id, Id) -> bool,
    get_protocol: unsafe extern "C" fn(*const c_char) -> Id,
    retain: unsafe extern "C" fn(Id) -> Id,
    release: unsafe extern "C" fn(Id),
    autorelease: unsafe extern "C" fn(Id) -> Id,
    class_add_ivar: unsafe extern "C" fn(Id, *const c_char, usize, u8, *const c_char) -> bool,
    class_replace_method: unsafe extern "C" fn(Id, Id, usize, *const c_char) -> usize,
    class_get_instance_variable: unsafe extern "C" fn(Id, *const c_char) -> Id,
    ivar_get_offset: unsafe extern "C" fn(Id) -> isize,
    ivar_get_type_encoding: unsafe extern "C" fn(Id) -> *const c_char,
    imp_implementation_with_block: unsafe extern "C" fn(*const c_void) -> usize,
    pool_push: unsafe extern "C" fn() -> Id,
    pool_pop: unsafe extern "C" fn(Id),
    run_loop_run_in_mode: unsafe extern "C" fn(Id, f64, u8) -> i32,
    default_mode: Id,
}

unsafe extern "C" {
    fn dlopen(path: *const c_char, mode: i32) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlerror() -> *const c_char;
}

const RTLD_NOW: i32 = 2;
const RTLD_GLOBAL: i32 = 8;
const RTLD_DEFAULT: *mut c_void = -2isize as *mut c_void;

static API: OnceLock<Result<Api, String>> = OnceLock::new();

fn api() -> Result<&'static Api, String> {
    API.get_or_init(open).as_ref().map_err(Clone::clone)
}

// Each symbol becomes the function type its header declares, named by the field.
#[allow(clippy::missing_transmute_annotations)]
fn open() -> Result<Api, String> {
    // SAFETY: plain C calls; every symbol is checked before it is transmuted to the type
    // its header declares.
    unsafe {
        for lib in [
            c"/usr/lib/libobjc.A.dylib",
            c"/System/Library/Frameworks/AppKit.framework/AppKit",
        ] {
            if dlopen(lib.as_ptr(), RTLD_NOW | RTLD_GLOBAL).is_null() {
                let why = CStr::from_ptr(dlerror()).to_string_lossy().into_owned();
                return Err(format!("libobjc/AppKit cannot be opened: {why}"));
            }
        }
        fn sym(name: &CStr) -> Result<*mut c_void, String> {
            // SAFETY: a NUL-terminated name looked up in every loaded image.
            let p = unsafe { dlsym(RTLD_DEFAULT, name.as_ptr()) };
            if p.is_null() {
                Err(format!("{} is missing", name.to_string_lossy()))
            } else {
                Ok(p)
            }
        }
        macro_rules! f {
            ($name:literal) => {
                std::mem::transmute(sym($name)?)
            };
        }
        // An exception raised inside a call stops at rl_objc_call (call.rs).
        call::install(
            sym(c"__objc_personality_v0")? as usize,
            sym(c"OBJC_EHTYPE_id")? as usize,
            sym(c"objc_begin_catch")? as usize,
            sym(c"objc_end_catch")? as usize,
            sym(c"objc_retain")? as usize,
        );
        Ok(Api {
            msg_send: sym(c"objc_msgSend")? as usize,
            msg_send_super: sym(c"objc_msgSendSuper")? as usize,
            global_block: sym(c"_NSConcreteGlobalBlock")? as usize,
            stack_block: sym(c"_NSConcreteStackBlock")? as usize,
            get_class: f!(c"objc_getClass"),
            sel_register_name: f!(c"sel_registerName"),
            sel_get_name: f!(c"sel_getName"),
            object_get_class: f!(c"object_getClass"),
            object_is_class: f!(c"object_isClass"),
            class_get_name: f!(c"class_getName"),
            class_get_superclass: f!(c"class_getSuperclass"),
            class_get_instance_method: f!(c"class_getInstanceMethod"),
            method_get_type_encoding: f!(c"method_getTypeEncoding"),
            allocate_class_pair: f!(c"objc_allocateClassPair"),
            register_class_pair: f!(c"objc_registerClassPair"),
            class_add_protocol: f!(c"class_addProtocol"),
            get_protocol: f!(c"objc_getProtocol"),
            retain: f!(c"objc_retain"),
            release: f!(c"objc_release"),
            autorelease: f!(c"objc_autorelease"),
            class_add_ivar: f!(c"class_addIvar"),
            class_replace_method: f!(c"class_replaceMethod"),
            class_get_instance_variable: f!(c"class_getInstanceVariable"),
            ivar_get_offset: f!(c"ivar_getOffset"),
            ivar_get_type_encoding: f!(c"ivar_getTypeEncoding"),
            imp_implementation_with_block: f!(c"imp_implementationWithBlock"),
            pool_push: f!(c"objc_autoreleasePoolPush"),
            pool_pop: f!(c"objc_autoreleasePoolPop"),
            run_loop_run_in_mode: f!(c"CFRunLoopRunInMode"),
            default_mode: *(sym(c"kCFRunLoopDefaultMode")? as *const Id),
        })
    }
}

/// Loads a framework or a dylib for `objc:ensure-objc-initialized`'s `:modules`.
fn load_module(path: &str) -> Result<(), String> {
    let c = cstring(path)?;
    // SAFETY: a NUL-terminated path.
    if unsafe { dlopen(c.as_ptr(), RTLD_NOW | RTLD_GLOBAL) }.is_null() {
        // SAFETY: dlerror's message for the failure above.
        let why = text(unsafe { dlerror() });
        return Err(format!("the module {path} cannot be loaded: {why}"));
    }
    Ok(())
}

fn cstring(s: &str) -> Result<CString, String> {
    CString::new(s).map_err(|_| format!("a NUL inside {s:?}"))
}

fn text(p: *const c_char) -> String {
    if p.is_null() {
        String::new()
    } else {
        // SAFETY: a NUL-terminated string the runtime owns.
        unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
    }
}

impl Api {
    fn class(&self, name: &str) -> Result<Id, String> {
        let c = cstring(name)?;
        // SAFETY: a NUL-terminated name.
        let cls = unsafe { (self.get_class)(c.as_ptr()) };
        if cls == 0 {
            Err(format!("no Objective-C class named {name}"))
        } else {
            Ok(cls)
        }
    }

    fn sel(&self, name: &str) -> Result<Id, String> {
        let c = cstring(name)?;
        // SAFETY: a NUL-terminated name; selectors are interned forever.
        Ok(unsafe { (self.sel_register_name)(c.as_ptr()) })
    }

    fn raw_encoding(&self, cls: Id, sel: Id) -> Option<String> {
        // SAFETY: a class and an interned selector.
        let method = unsafe { (self.class_get_instance_method)(cls, sel) };
        if method == 0 {
            return None;
        }
        // SAFETY: a method of a live class.
        let types = unsafe { (self.method_get_type_encoding)(method) };
        (!types.is_null()).then(|| text(types))
    }

    /// A send the host makes itself (a turn of the event loop, the `NSString` a string
    /// argument becomes): each argument one scalar leaf, laid out by the method's own
    /// encoding. Answers the result's first leaf (`Int(0)` when there is none), or why the
    /// send could not be made; an exception it raised is released and reported as one.
    fn msg(&self, receiver: Id, selector: &str, args: &[Leaf]) -> Result<Leaf, String> {
        let sel = self.sel(selector)?;
        // SAFETY: a live receiver or a class.
        let cls = unsafe { (self.object_get_class)(receiver) };
        // Parsed once per (class, selector): the event loop sends the same few each turn.
        let cached = ENCODINGS.with(|c| c.borrow().get(&(cls, sel)).cloned());
        let encoding = match cached {
            Some(e) => e,
            None => {
                let raw = self.raw_encoding(cls, sel).ok_or_else(|| {
                    // SAFETY: the receiver's class (Nil for a nil receiver answers "").
                    let name = text(unsafe { (self.class_get_name)(cls) });
                    format!("{name} does not respond to {selector}")
                })?;
                let e = Rc::new(encoding::parse(&raw)?);
                ENCODINGS.with(|c| c.borrow_mut().insert((cls, sel), e.clone()));
                e
            }
        };
        let declared = &encoding.args[2..];
        if declared.len() != args.len() || declared.iter().any(|t| t.kind == Kind::Struct) {
            return Err(format!("{selector} is not a send of {} scalar argument(s)", args.len()));
        }
        let mut call = Call::new(self.msg_send, &encoding.ret);
        call.push(&encoding.args[0], &[Leaf::Int(receiver as i64)]);
        call.push(&encoding.args[1], &[Leaf::Int(sel as i64)]);
        for (ty, leaf) in declared.iter().zip(args) {
            call.push(ty, &[*leaf]);
        }
        // SAFETY: the shape is the method's own encoding, laid out by the convention.
        match unsafe { call.invoke() } {
            Ok(leaves) => Ok(leaves.first().copied().unwrap_or(Leaf::Int(0))),
            Err(Raised(thrown)) => {
                if thrown != 0 {
                    // SAFETY: the one reference the catch kept.
                    unsafe { (self.release)(thrown) };
                }
                Err(format!("{selector} raised"))
            }
        }
    }

    /// An autoreleased `NSString`.
    fn ns_string(&self, s: &str) -> Result<Id, String> {
        let c = cstring(s)?;
        let cls = self.class("NSString")?;
        match self.msg(cls, "stringWithUTF8String:", &[Leaf::Int(c.as_ptr() as i64)])? {
            Leaf::Int(string) if string != 0 => Ok(string as Id),
            _ => Err("stringWithUTF8String: answered nil".into()),
        }
    }

    fn with_pool<T>(&self, body: impl FnOnce() -> T) -> T {
        // SAFETY: a push matched by the pop below, on this thread.
        let pool = unsafe { (self.pool_push)() };
        let value = body();
        // SAFETY: the pool pushed above.
        unsafe { (self.pool_pop)(pool) };
        value
    }
}

/// The module's `rlobjc_method(method-id, self) -> i32`: a method the program defined,
/// its arguments in [`class`]'s frame.
type MethodCallback = TypedFunc<(i32, i64), i32>;

#[derive(Default)]
struct State {
    /// `appkit::%app` asked for `-[NSApplication run]`: `pump` dispatches events.
    app_started: bool,
    method: Option<MethodCallback>,
}

thread_local! {
    static STATE: RefCell<State> = RefCell::new(State::default());
    /// The `Caller` of the innermost host call in progress, which a callback re-enters
    /// the module through; null outside one.
    static CALLER: Cell<*mut c_void> = const { Cell::new(std::ptr::null_mut()) };
    /// Parsed method encodings of the host's own sends, by (class, selector).
    static ENCODINGS: RefCell<HashMap<(Id, Id), Rc<encoding::Encoding>>> = RefCell::new(HashMap::new());
    /// How many sends / pumps are in progress on this thread (callbacks nest them).
    static DEPTH: Cell<usize> = const { Cell::new(0) };
}

fn with<T>(f: impl FnOnce(&mut State) -> T) -> T {
    STATE.with(|s| f(&mut s.borrow_mut()))
}

/// Publishes a host call's `Caller` for the callbacks it may cause; restores the outer one
/// on drop.
struct Entered(*mut c_void);

impl Entered {
    fn new(caller: &mut Caller<'_, WasiP1Ctx>) -> Entered {
        DEPTH.set(DEPTH.get() + 1);
        Entered(CALLER.replace(caller as *mut Caller<'_, WasiP1Ctx> as *mut c_void))
    }

    /// Whether this is the outermost host call: no Lisp frame below it is in the middle
    /// of a send, so no address a dead value handed out can still be in flight.
    fn outermost(&self) -> bool {
        DEPTH.get() == 1
    }
}

impl Drop for Entered {
    fn drop(&mut self) {
        DEPTH.set(DEPTH.get() - 1);
        CALLER.set(self.0);
    }
}

/// The references whose values died, queued by the collector (`prim`'s `Handle`) for
/// release on thread 0.
static PENDING: Mutex<Vec<Id>> = Mutex::new(Vec::new());

/// Releases the references whose values died. Only ever from the OUTERMOST host call
/// ([`Entered::outermost`]): the end of a send (whose receiver and arguments may be among
/// them) or a turn of the event loop. A release inside a callback could free the very
/// object whose method is running.
fn release_pending(api: &Api) {
    let released = match PENDING.lock() {
        Ok(mut pending) if !pending.is_empty() => std::mem::take(&mut *pending),
        _ => return,
    };
    api.with_pool(|| {
        for object in released {
            // SAFETY: one reference a value held, released once.
            unsafe { (api.release)(object) };
        }
    });
}

/// `len` bytes of linear memory at `ptr`.
fn memory_slice<'a>(caller: &'a mut Caller<'_, WasiP1Ctx>, ptr: i32, len: i32) -> wasmtime::Result<&'a [u8]> {
    let memory = match caller.get_export("memory") {
        Some(Extern::Memory(m)) => m,
        _ => wasmtime::bail!("the module exports no memory"),
    };
    let caller: &'a Caller<'_, WasiP1Ctx> = caller;
    let (start, end) = (ptr as u32 as usize, ptr as u32 as usize + len as u32 as usize);
    match memory.data(caller).get(start..end) {
        Some(bytes) => Ok(bytes),
        None => wasmtime::bail!("a byte argument lies outside linear memory"),
    }
}

fn memory_string(caller: &mut Caller<'_, WasiP1Ctx>, ptr: i32, len: i32) -> wasmtime::Result<String> {
    Ok(String::from_utf8_lossy(memory_slice(caller, ptr, len)?).into_owned())
}

/// A `:string` result: bytes the host writes into a block `__ronto_alloc` reserves.
fn return_string(caller: &mut Caller<'_, WasiP1Ctx>, s: &str) -> wasmtime::Result<(i32, i32)> {
    let alloc: TypedFunc<i32, i32> = match caller.get_export("__ronto_alloc") {
        Some(Extern::Func(f)) => f.typed(&caller)?,
        _ => wasmtime::bail!("the module exports no __ronto_alloc"),
    };
    let ptr = alloc.call(&mut *caller, s.len() as i32)?;
    let memory = match caller.get_export("memory") {
        Some(Extern::Memory(m)) => m,
        _ => wasmtime::bail!("the module exports no memory"),
    };
    memory.write(&mut *caller, ptr as u32 as usize, s.as_bytes())?;
    Ok((ptr, s.len() as i32))
}

/// A `:bytes` result: up to `cap` bytes at `ptr`, answering the full length.
fn return_bytes(caller: &mut Caller<'_, WasiP1Ctx>, bytes: &[u8], ptr: i32, cap: i32) -> wasmtime::Result<i32> {
    let n = bytes.len().min(cap.max(0) as usize);
    let memory = match caller.get_export("memory") {
        Some(Extern::Memory(m)) => m,
        _ => wasmtime::bail!("the module exports no memory"),
    };
    memory.write(&mut *caller, ptr as u32 as usize, &bytes[..n])?;
    Ok(bytes.len() as i32)
}

fn is_application(api: &Api, receiver: Id) -> bool {
    let Ok(cls) = api.class("NSApplication") else {
        return false;
    };
    api.msg(receiver, "isKindOfClass:", &[Leaf::Int(cls as i64)]) == Ok(Leaf::Int(1))
}

/// `sleep` in a program that uses the runtime: thread 0's event loop for that long.
fn pump(api: &Api, seconds: f64, outermost: bool) {
    let deadline = Instant::now() + Duration::from_secs_f64(seconds.max(0.0));
    let started = with(|s| s.app_started);
    // An object answer's address, 0 for nil or a failed send.
    let object = |answer: Result<Leaf, String>| match answer {
        Ok(Leaf::Int(a)) => a as Id,
        _ => 0,
    };
    loop {
        // The void blocks another thread called, queued for this one (`block`).
        block::drain(api);
        let now = Instant::now();
        if now >= deadline {
            break;
        }
        let remaining = (deadline - now).as_secs_f64();
        if outermost {
            release_pending(api);
        }
        api.with_pool(|| {
            if started {
                let Ok(cls) = api.class("NSApplication") else { return };
                let app = object(api.msg(cls, "sharedApplication", &[]));
                if app == 0 {
                    return;
                }
                let date = api.class("NSDate").map_or(0, |d| {
                    object(api.msg(d, "dateWithTimeIntervalSinceNow:", &[Leaf::Float(remaining)]))
                });
                let event = object(api.msg(
                    app,
                    "nextEventMatchingMask:untilDate:inMode:dequeue:",
                    &[
                        Leaf::Int(-1),
                        Leaf::Int(date as i64),
                        Leaf::Int(api.default_mode as i64),
                        Leaf::Int(1),
                    ],
                ));
                if event != 0 {
                    let _ = api.msg(app, "sendEvent:", &[Leaf::Int(event as i64)]);
                    let _ = api.msg(app, "updateWindows", &[]);
                }
            } else {
                // SAFETY: the default mode on this thread's run loop.
                unsafe { (api.run_loop_run_in_mode)(api.default_mode, remaining, 1) };
            }
        });
    }
}

/// For a host call that waits on another thread (a fetch's head or body): runs thread 0's
/// event loop in short turns until `done` answers true, so the windows of a program whose
/// application started stay live while it waits, and a callback arriving meanwhile
/// re-enters the module through this call's `Caller`, as during `sleep`. Answers false at
/// once, without waiting, when the application has not started: nothing is on screen,
/// and the caller blocks as any program would.
#[cfg(feature = "net")]
pub fn pump_until(caller: &mut Caller<'_, WasiP1Ctx>, done: &mut dyn FnMut() -> bool) -> bool {
    if !with(|s| s.app_started) {
        return false;
    }
    let Ok(api) = api() else { return false };
    let entered = Entered::new(caller);
    while !done() {
        pump(api, 0.02, entered.outermost());
    }
    true
}

/// Binds every `rlobjc` import.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    prim::add_to_linker(linker)?;
    class::add_to_linker(linker)?;
    block::add_to_linker(linker)
}

/// Finds the module's method export once it is instantiated; a module that defines no
/// Objective-C method or block exports none.
pub fn bind(instance: &Instance, store: &mut Store<WasiP1Ctx>) -> wasmtime::Result<()> {
    if instance.get_export(&mut *store, class::METHOD_EXPORT).is_some() {
        let method = instance.get_typed_func::<(i32, i64), i32>(&mut *store, class::METHOD_EXPORT)?;
        with(|s| s.method = Some(method));
    }
    Ok(())
}
