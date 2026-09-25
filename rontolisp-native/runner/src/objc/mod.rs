//! The Objective-C host of a `--native` output: the `rlobjc` imports the compiled
//! `objc:` library (`objc-native.lisp`) is written over, and the upcalls back into the
//! module (`.kb/objc.md`, "--native").
//!
//! THE design decision: the module runs ON thread 0. AppKit belongs to the process's first
//! thread and a wasmtime `Store` is not `Sync`, so a callback arriving on thread 0 while
//! the module ran on another thread could not enter it -- the other thread's stack holds
//! the store, and a nested call from a second thread would leave that stack's GC roots
//! unwalked. So there is no hop at all: every send is made from thread 0, and a callback
//! (a button's action, a timer, a delegate method) arrives INSIDE a host call -- a send
//! that made AppKit call out, or [`pump`], which is what `sleep` becomes -- and re-enters
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

mod call;
mod encoding;

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char, c_void};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use call::{Call, Leaf};
use encoding::{Kind, Type};
use wasmtime::{AsContextMut, Caller, Extern, Instance, Linker, Store, TypedFunc};
use wasmtime_wasi::I32Exit;
use wasmtime_wasi::p1::WasiP1Ctx;

use crate::TRAP_EXIT;

/// The import module the library's `rontolisp:wasm-import`s name.
pub const MODULE: &str = "rlobjc";

/// The module's export the IMPs call: `(closure-id, self, arg1, arg2, argc) -> i64`.
const CALLBACK_EXPORT: &str = "rlobjc_callback";

type Id = usize;

/// The Objective-C runtime and the few C functions around it, resolved once.
struct Api {
    msg_send: usize,
    get_class: unsafe extern "C" fn(*const c_char) -> Id,
    sel_register_name: unsafe extern "C" fn(*const c_char) -> Id,
    sel_get_name: unsafe extern "C" fn(Id) -> *const c_char,
    object_get_class: unsafe extern "C" fn(Id) -> Id,
    object_get_class_name: unsafe extern "C" fn(Id) -> *const c_char,
    class_get_superclass: unsafe extern "C" fn(Id) -> Id,
    class_get_instance_method: unsafe extern "C" fn(Id, Id) -> Id,
    method_get_type_encoding: unsafe extern "C" fn(Id) -> *const c_char,
    allocate_class_pair: unsafe extern "C" fn(Id, *const c_char, usize) -> Id,
    register_class_pair: unsafe extern "C" fn(Id),
    class_add_method: unsafe extern "C" fn(Id, Id, usize, *const c_char) -> bool,
    class_add_protocol: unsafe extern "C" fn(Id, Id) -> bool,
    get_protocol: unsafe extern "C" fn(*const c_char) -> Id,
    protocol_get_method_description: unsafe extern "C" fn(Id, Id, bool, bool) -> MethodDescription,
    retain: unsafe extern "C" fn(Id) -> Id,
    release: unsafe extern "C" fn(Id),
    pool_push: unsafe extern "C" fn() -> Id,
    pool_pop: unsafe extern "C" fn(Id),
    run_loop_run_in_mode: unsafe extern "C" fn(Id, f64, u8) -> i32,
    default_mode: Id,
}

#[repr(C)]
struct MethodDescription {
    name: Id,
    types: *const c_char,
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
        Ok(Api {
            msg_send: sym(c"objc_msgSend")? as usize,
            get_class: f!(c"objc_getClass"),
            sel_register_name: f!(c"sel_registerName"),
            sel_get_name: f!(c"sel_getName"),
            object_get_class: f!(c"object_getClass"),
            object_get_class_name: f!(c"object_getClassName"),
            class_get_superclass: f!(c"class_getSuperclass"),
            class_get_instance_method: f!(c"class_getInstanceMethod"),
            method_get_type_encoding: f!(c"method_getTypeEncoding"),
            allocate_class_pair: f!(c"objc_allocateClassPair"),
            register_class_pair: f!(c"objc_registerClassPair"),
            class_add_method: f!(c"class_addMethod"),
            class_add_protocol: f!(c"class_addProtocol"),
            get_protocol: f!(c"objc_getProtocol"),
            protocol_get_method_description: f!(c"protocol_getMethodDescription"),
            retain: f!(c"objc_retain"),
            release: f!(c"objc_release"),
            pool_push: f!(c"objc_autoreleasePoolPush"),
            pool_pop: f!(c"objc_autoreleasePoolPop"),
            run_loop_run_in_mode: f!(c"CFRunLoopRunInMode"),
            default_mode: *(sym(c"kCFRunLoopDefaultMode")? as *const Id),
        })
    }
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

    fn class_name(&self, object: Id) -> String {
        // SAFETY: a live object or class.
        text(unsafe { (self.object_get_class_name)(object) })
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

    /// A send whose shape the host itself knows: object arguments, an object answer.
    fn send_objects(&self, receiver: Id, selector: &str, args: &[Leaf]) -> Result<Id, String> {
        let args = args
            .iter()
            .map(|l| match l {
                Leaf::Int(n) => Arg::Raw(*n),
                Leaf::Float(_) => Arg::Leaf(*l),
            })
            .collect();
        match self.send(receiver, selector, args)? {
            (_, leaves) => Ok(match leaves.first() {
                Some(Leaf::Int(i)) => *i as Id,
                _ => 0,
            }),
        }
    }

    /// The generic send: marshals each argument by the selector's declared type and
    /// answers the declared result type with its leaves.
    fn send(&self, receiver: Id, selector: &str, args: Vec<Arg>) -> Result<(Type, Vec<Leaf>), String> {
        let sel = self.sel(selector)?;
        // SAFETY: a live receiver (the caller holds a reference) or a class.
        let cls = unsafe { (self.object_get_class)(receiver) };
        let raw = self
            .raw_encoding(cls, sel)
            .ok_or_else(|| format!("{} does not respond to {selector}", self.class_name(receiver)))?;
        let encoding = encoding::parse(&raw)?;
        let declared = encoding.args.len() - 2;
        let variadic = VARIADIC.contains(&selector);
        if if variadic {
            args.len() < declared
        } else {
            args.len() != declared
        } {
            return Err(format!(
                "{selector} takes {}{declared} argument(s), got {}",
                if variadic { "at least " } else { "" },
                args.len()
            ));
        }
        let mut call = Call::new(self.msg_send, &encoding.ret);
        call.push(&encoding.args[0], &[Leaf::Int(receiver as i64)]);
        call.push(&encoding.args[1], &[Leaf::Int(sel as i64)]);
        // Storage an argument points into (C strings, out slots), alive past the call.
        let mut strings: Vec<CString> = Vec::new();
        let mut slots: Vec<Box<Id>> = Vec::new();
        for (i, arg) in args.iter().enumerate() {
            if i < declared {
                let ty = &encoding.args[i + 2];
                let leaves = self.marshal(ty, arg, selector, i, &mut strings, &mut slots)?;
                call.push(ty, &leaves);
            } else {
                let leaf = match arg {
                    Arg::Nil => Leaf::Int(0),
                    Arg::Leaf(Leaf::Float(f)) => Leaf::Float(*f),
                    Arg::Leaf(Leaf::Int(n)) | Arg::Raw(n) => Leaf::Int(*n),
                    Arg::Object(a) => Leaf::Int(*a as i64),
                    Arg::Str(s) => Leaf::Int(self.ns_string(s)? as i64),
                    _ => {
                        return Err(format!(
                            "{selector}: argument {} is past the declared arity, so it is a variadic argument, \
                             which takes an object, a string, an integer or a float",
                            i + 1
                        ));
                    }
                };
                call.push_variadic(leaf);
            }
        }
        if variadic {
            // The terminator the nil-terminated constructors need and the format family
            // never reads.
            call.push_variadic(Leaf::Int(0));
        }
        // SAFETY: the shape is the method's own encoding, laid out by the convention.
        let leaves = unsafe { call.invoke() };
        drop(strings);
        let ret = encoding.ret;
        if let Some(slot) = slots.first() {
            let error = **slot;
            let failed = match (ret.kind, leaves.first()) {
                (_, None) => true,
                (_, Some(Leaf::Int(0))) => true,
                _ => false,
            };
            if error != 0 && failed {
                return Err(self.error_text(error, selector));
            }
        }
        Ok((ret, leaves))
    }

    fn error_text(&self, error: Id, selector: &str) -> String {
        let string = |object: Id| -> String {
            match self.send(object, "UTF8String", Vec::new()) {
                Ok((_, leaves)) => match leaves.first() {
                    Some(Leaf::Int(p)) => text(*p as *const c_char),
                    _ => String::new(),
                },
                Err(_) => String::new(),
            }
        };
        let description = self.send_objects(error, "localizedDescription", &[]).unwrap_or(0);
        let domain = self.send_objects(error, "domain", &[]).unwrap_or(0);
        let code = match self.send(error, "code", Vec::new()) {
            Ok((_, leaves)) => match leaves.first() {
                Some(Leaf::Int(n)) => *n,
                _ => 0,
            },
            Err(_) => 0,
        };
        let reason = if description != 0 {
            string(description)
        } else {
            "no reason given".into()
        };
        let r#where = if domain != 0 {
            format!(" [{} {code}]", string(domain))
        } else {
            String::new()
        };
        format!("{selector}: {reason}{where}")
    }

    fn marshal(
        &self,
        ty: &Type,
        arg: &Arg,
        selector: &str,
        index: usize,
        strings: &mut Vec<CString>,
        slots: &mut Vec<Box<Id>>,
    ) -> Result<Vec<Leaf>, String> {
        let mismatch = |expected: &str| {
            Err(format!(
                "{selector}: argument {} must be {expected}, got {}",
                index + 1,
                arg.describe()
            ))
        };
        let address = |a: Id| Ok(vec![Leaf::Int(a as i64)]);
        match (ty.kind, arg) {
            (_, Arg::Raw(n)) => Ok(vec![Leaf::Int(*n)]),
            (_, Arg::Error) if ty.kind != Kind::Pointer => Err(format!(
                "{selector}: argument {} is declared {}, not a pointer, so it takes no out slot",
                index + 1,
                format!("{:?}", ty.kind).to_lowercase()
            )),
            (Kind::Pointer, Arg::Error) => {
                let slot = Box::new(0usize);
                let p = &*slot as *const Id as i64;
                slots.push(slot);
                Ok(vec![Leaf::Int(p)])
            }
            (Kind::Struct, Arg::Struct(leaves)) if leaves.len() == ty.leaves.len() => Ok(leaves.clone()),
            (Kind::Struct, _) => mismatch(&format!("a struct of {} numbers", ty.leaves.len())),
            (k, Arg::Nil) if k.is_address() => address(0),
            (Kind::Object | Kind::Class | Kind::Pointer, Arg::Object(a)) => address(*a),
            (Kind::Object, Arg::Str(s)) => address(self.ns_string(s)?),
            (Kind::Object, _) => mismatch("an object"),
            (Kind::Class, Arg::Str(s)) => address(self.class(s)?),
            (Kind::Class, _) => mismatch("a class"),
            (Kind::Pointer, Arg::Leaf(Leaf::Int(n))) => Ok(vec![Leaf::Int(*n)]),
            (Kind::Pointer, _) => mismatch("a pointer"),
            (Kind::Selector, Arg::Str(s)) => address(self.sel(s)?),
            (Kind::Selector, _) => mismatch("a selector name"),
            (Kind::CString, Arg::Str(s)) => {
                let c = cstring(s)?;
                let p = c.as_ptr() as i64;
                strings.push(c);
                Ok(vec![Leaf::Int(p)])
            }
            (Kind::CString, _) => mismatch("a string"),
            (Kind::Bool, Arg::Nil) => Ok(vec![Leaf::Int(0)]),
            (Kind::Bool, Arg::True) => Ok(vec![Leaf::Int(1)]),
            (Kind::Bool, _) => mismatch("a boolean"),
            (Kind::Float | Kind::Double, Arg::Leaf(l)) => Ok(vec![*l]),
            (Kind::Float | Kind::Double, _) => mismatch("a number"),
            (_, Arg::Leaf(l)) => Ok(vec![*l]),
            _ => mismatch("an integer"),
        }
    }

    /// An autoreleased `NSString`.
    fn ns_string(&self, s: &str) -> Result<Id, String> {
        let c = cstring(s)?;
        let cls = self.class("NSString")?;
        let string = self.send_objects(cls, "stringWithUTF8String:", &[Leaf::Int(c.as_ptr() as i64)])?;
        if string == 0 {
            Err("stringWithUTF8String: answered nil".into())
        } else {
            Ok(string)
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

/// The selectors declared exactly like a fixed-arity twin but variadic
/// (`am.ik.objc.VariadicSelectors`, the same table).
const VARIADIC: &[&str] = &[
    "arrayWithObjects:",
    "initWithObjects:",
    "setWithObjects:",
    "orderedSetWithObjects:",
    "dictionaryWithObjectsAndKeys:",
    "initWithObjectsAndKeys:",
    "stringWithFormat:",
    "initWithFormat:",
    "localizedStringWithFormat:",
    "stringByAppendingFormat:",
    "appendFormat:",
    "predicateWithFormat:",
    "raise:format:",
];

/// One argument as the library pushed it; marshalled by the declared type at the send.
#[derive(Clone, Debug)]
enum Arg {
    /// A word the host itself passes (a C string it built, a length): never from Lisp.
    Raw(i64),
    Nil,
    True,
    Leaf(Leaf),
    Object(Id),
    Str(String),
    Struct(Vec<Leaf>),
    Error,
}

impl Arg {
    fn describe(&self) -> &'static str {
        match self {
            Arg::Raw(_) => "Long",
            Arg::Nil => "nil",
            Arg::True => "Boolean",
            Arg::Leaf(Leaf::Int(_)) => "Long",
            Arg::Leaf(Leaf::Float(_)) => "Double",
            Arg::Object(_) => "MemorySegment",
            Arg::Str(_) => "String",
            Arg::Struct(_) => "Number[]",
            Arg::Error => "Out",
        }
    }
}

/// What the last send answered, fetched by the `result_*` imports.
#[derive(Default)]
struct Answer {
    int: i64,
    float: f64,
    string: String,
    leaves: Vec<Leaf>,
}

/// A method a program defined: which closure it calls, with how many object arguments,
/// answering what.
#[derive(Clone, Copy)]
struct Bound {
    closure: i32,
    argc: i32,
    ret: Kind,
}

#[derive(Default)]
struct State {
    args: Vec<Arg>,
    pending_struct: Option<(usize, Vec<Leaf>)>,
    answer: Answer,
    error: String,
    /// By class, then selector.
    handlers: HashMap<(Id, Id), Bound>,
    defined: HashMap<String, Id>,
    /// `appkit::%app` asked for `-[NSApplication run]`: `pump` dispatches events.
    app_started: bool,
    callback: Option<TypedFunc<(i32, i64, i64, i64, i32), i64>>,
}

thread_local! {
    static STATE: RefCell<State> = RefCell::new(State::default());
    /// The `Caller` of the innermost host call in progress, which a callback re-enters
    /// the module through; null outside one.
    static CALLER: Cell<*mut c_void> = const { Cell::new(std::ptr::null_mut()) };
}

fn with<T>(f: impl FnOnce(&mut State) -> T) -> T {
    STATE.with(|s| f(&mut s.borrow_mut()))
}

/// Publishes a host call's `Caller` for the callbacks it may cause; restores the outer one
/// on drop.
struct Entered(*mut c_void);

impl Entered {
    fn new(caller: &mut Caller<'_, WasiP1Ctx>) -> Entered {
        Entered(CALLER.replace(caller as *mut Caller<'_, WasiP1Ctx> as *mut c_void))
    }
}

impl Drop for Entered {
    fn drop(&mut self) {
        CALLER.set(self.0);
    }
}

fn memory_bytes(caller: &mut Caller<'_, WasiP1Ctx>, ptr: i32, len: i32) -> wasmtime::Result<Vec<u8>> {
    let memory = match caller.get_export("memory") {
        Some(Extern::Memory(m)) => m,
        _ => wasmtime::bail!("the module exports no memory"),
    };
    let data = memory.data(&caller);
    let (start, end) = (ptr as u32 as usize, ptr as u32 as usize + len as u32 as usize);
    match data.get(start..end) {
        Some(bytes) => Ok(bytes.to_vec()),
        None => wasmtime::bail!("a string argument lies outside linear memory"),
    }
}

fn memory_string(caller: &mut Caller<'_, WasiP1Ctx>, ptr: i32, len: i32) -> wasmtime::Result<String> {
    Ok(String::from_utf8_lossy(&memory_bytes(caller, ptr, len)?).into_owned())
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

/// Result kinds `send` answers (negative: failed, the reason in `error`).
mod answer {
    pub const NIL: i32 = 0;
    pub const OBJECT: i32 = 1;
    pub const CLASS: i32 = 2;
    pub const INTEGER: i32 = 3;
    pub const STRING: i32 = 4;
    pub const TRUE: i32 = 5;
    pub const FLOAT: i32 = 6;
    pub const STRUCT: i32 = 7;
    pub const FAILED: i32 = -1;
}

fn fail(message: String) -> i32 {
    with(|s| s.error = message);
    answer::FAILED
}

/// The Cocoa ownership convention: these families answer an object the caller owns.
fn hands_ownership(selector: &str) -> bool {
    selector.starts_with("alloc")
        || selector.starts_with("new")
        || selector.starts_with("copy")
        || selector.starts_with("mutableCopy")
        || selector == "retain"
}

fn send(api: &Api, receiver: Id, selector: &str) -> i32 {
    let args = with(|s| std::mem::take(&mut s.args));
    if receiver == 0 {
        // Objective-C answers nil to a message sent to nil.
        return answer::NIL;
    }
    if selector == "performSelectorOnMainThread:withObject:waitUntilDone:"
        && matches!(args.first(), Some(Arg::Str(s)) if s == "run")
        && is_application(api, receiver)
    {
        // -[NSApplication run] never returns, and thread 0 is the module's: the host
        // owns the loop instead (`pump`). The answer is discarded like every
        // performSelector answer.
        with(|s| s.app_started = true);
        let _ = api.send(receiver, "activateIgnoringOtherApps:", vec![Arg::True]);
        return answer::NIL;
    }
    api.with_pool(|| {
        let (ret, leaves) = match api.send(receiver, selector, args) {
            Ok(r) => r,
            Err(e) => return fail(e),
        };
        if selector.starts_with("performSelector") {
            // The answer is the TARGET method's, whose type the encoding cannot see.
            return answer::NIL;
        }
        let first = leaves.first().copied();
        let int = |i: i64| {
            with(|s| s.answer.int = i);
        };
        match (ret.kind, first) {
            (Kind::Void, _) | (_, None) => answer::NIL,
            (Kind::Object | Kind::Class, Some(Leaf::Int(0))) => answer::NIL,
            (Kind::Object, Some(Leaf::Int(a))) => {
                if !hands_ownership(selector) {
                    // Retained INSIDE the pool that would otherwise free it: the wrapper
                    // owns one reference either way.
                    // SAFETY: a live object answered by the send.
                    unsafe { (api.retain)(a as Id) };
                }
                int(a);
                answer::OBJECT
            }
            (Kind::Class, Some(Leaf::Int(a))) => {
                int(a);
                answer::CLASS
            }
            (Kind::Selector, Some(Leaf::Int(p))) | (Kind::CString, Some(Leaf::Int(p))) => {
                if p == 0 {
                    return answer::NIL;
                }
                let s = if ret.kind == Kind::Selector {
                    // SAFETY: a selector the method answered.
                    text(unsafe { (api.sel_get_name)(p as Id) })
                } else {
                    text(p as *const c_char)
                };
                with(|st| st.answer.string = s);
                answer::STRING
            }
            (Kind::Pointer, Some(Leaf::Int(p))) => {
                if p == 0 {
                    return answer::NIL;
                }
                int(p);
                answer::INTEGER
            }
            (Kind::Bool, Some(Leaf::Int(b))) => {
                if b != 0 {
                    answer::TRUE
                } else {
                    answer::NIL
                }
            }
            (Kind::Float | Kind::Double, Some(Leaf::Float(f))) => {
                with(|s| s.answer.float = f);
                answer::FLOAT
            }
            (Kind::Struct, _) => {
                with(|s| s.answer.leaves = leaves.clone());
                answer::STRUCT
            }
            (_, Some(Leaf::Int(i))) => {
                int(i);
                answer::INTEGER
            }
            (_, Some(Leaf::Float(f))) => {
                with(|s| s.answer.float = f);
                answer::FLOAT
            }
        }
    })
}

fn is_application(api: &Api, receiver: Id) -> bool {
    let Ok(cls) = api.class("NSApplication") else {
        return false;
    };
    matches!(api.send(receiver, "isKindOfClass:", vec![Arg::Object(cls)]), Ok((_, l)) if l.first() == Some(&Leaf::Int(1)))
}

/// `sleep` in a program that uses the runtime: thread 0's event loop for that long.
fn pump(api: &Api, seconds: f64) {
    let deadline = Instant::now() + Duration::from_secs_f64(seconds.max(0.0));
    let started = with(|s| s.app_started);
    loop {
        let now = Instant::now();
        if now >= deadline {
            break;
        }
        let remaining = (deadline - now).as_secs_f64();
        api.with_pool(|| {
            if started {
                let Ok(cls) = api.class("NSApplication") else { return };
                let app = api.send_objects(cls, "sharedApplication", &[]).unwrap_or(0);
                let date = api
                    .class("NSDate")
                    .and_then(|d| {
                        api.send(
                            d,
                            "dateWithTimeIntervalSinceNow:",
                            vec![Arg::Leaf(Leaf::Float(remaining))],
                        )
                    })
                    .ok()
                    .and_then(|(_, l)| match l.first() {
                        Some(Leaf::Int(a)) => Some(*a as Id),
                        _ => None,
                    })
                    .unwrap_or(0);
                let event = api
                    .send(
                        app,
                        "nextEventMatchingMask:untilDate:inMode:dequeue:",
                        vec![
                            Arg::Leaf(Leaf::Int(-1)),
                            Arg::Object(date),
                            Arg::Object(api.default_mode),
                            Arg::True,
                        ],
                    )
                    .ok()
                    .and_then(|(_, l)| match l.first() {
                        Some(Leaf::Int(a)) if *a != 0 => Some(*a as Id),
                        _ => None,
                    });
                if let Some(event) = event {
                    let _ = api.send(app, "sendEvent:", vec![Arg::Object(event)]);
                    let _ = api.send(app, "updateWindows", Vec::new());
                }
            } else {
                // SAFETY: the default mode on this thread's run loop.
                unsafe { (api.run_loop_run_in_mode)(api.default_mode, remaining, 1) };
            }
        });
    }
}

/// The encoding a defined method gets: the superclass's, an adopted protocol's, else the
/// target/action default (`ObjcClasses.encodingFor`).
fn encoding_for(api: &Api, superclass: Id, protocols: &[String], selector: &str) -> Result<String, String> {
    let sel = api.sel(selector)?;
    if let Some(raw) = api.raw_encoding(superclass, sel) {
        return Ok(raw);
    }
    for protocol in protocols {
        let c = cstring(protocol)?;
        // SAFETY: a NUL-terminated name.
        let p = unsafe { (api.get_protocol)(c.as_ptr()) };
        if p == 0 {
            return Err(format!("no Objective-C protocol named {protocol}"));
        }
        for required in [true, false] {
            // SAFETY: a protocol and an interned selector.
            let d = unsafe { (api.protocol_get_method_description)(p, sel, required, true) };
            if !d.types.is_null() {
                return Ok(text(d.types));
            }
        }
    }
    Ok(format!("v@:{}", "@".repeat(selector.matches(':').count())))
}

/// The callback shapes `ObjcClasses` serves, the same closed set: `v@:`, `v@:@`,
/// `v@:@@`, `B@:@`, `@@:@`, `q@:@` -- all of them one IMP here, [`imp`].
fn shape(encoding: &encoding::Encoding) -> Option<(i32, Kind)> {
    let args = &encoding.args[2..];
    if args.len() > 2 || !args.iter().all(|a| a.kind.is_address()) {
        return None;
    }
    let ret = encoding.ret.kind;
    let ok = match args.len() {
        0 => ret == Kind::Void,
        1 => ret == Kind::Void || ret == Kind::Bool || ret.is_address() || ret == Kind::Int64,
        _ => ret == Kind::Void,
    };
    ok.then_some((args.len() as i32, ret))
}

const SUPPORTED_SHAPES: &str = "void(void*,void*), void(void*,void*,void*), void(void*,void*,void*,void*), jboolean(void*,void*,void*), \
     void*(void*,void*,void*), jlong(void*,void*,void*)";

fn define_class(
    api: &Api,
    name: &str,
    superclass: &str,
    protocols: &[String],
    methods: &[String],
    first: i32,
) -> Result<Id, String> {
    let sup = api.class(superclass)?;
    let mut protos = Vec::new();
    for protocol in protocols {
        let c = cstring(protocol)?;
        // SAFETY: a NUL-terminated name.
        let p = unsafe { (api.get_protocol)(c.as_ptr()) };
        if p == 0 {
            return Err(format!("no Objective-C protocol named {protocol}"));
        }
        protos.push(p);
    }
    let existing = with(|s| s.defined.get(name).copied());
    let cls = match existing {
        Some(cls) => cls,
        None => {
            let c = cstring(name)?;
            // SAFETY: a NUL-terminated name.
            if unsafe { (api.get_class)(c.as_ptr()) } != 0 {
                return Err(format!(
                    "the class {name} already exists and was not defined by this process"
                ));
            }
            // SAFETY: a live superclass and a fresh name.
            let cls = unsafe { (api.allocate_class_pair)(sup, c.as_ptr(), 0) };
            if cls == 0 {
                return Err(format!("objc_allocateClassPair refused the name {name}"));
            }
            cls
        }
    };
    for (i, selector) in methods.iter().enumerate() {
        let raw = encoding_for(api, sup, protocols, selector)?;
        let encoding = encoding::parse(&raw)?;
        let (argc, ret) = shape(&encoding).ok_or_else(|| {
            format!(
                "{name} {selector}: the callback shape {} (encoding {raw}) is outside the supported set: \
                 {SUPPORTED_SHAPES}",
                encoding.spelling()
            )
        })?;
        let sel = api.sel(selector)?;
        let bound = Bound {
            closure: first + i as i32,
            argc,
            ret,
        };
        let fresh = with(|s| s.handlers.insert((cls, sel), bound).is_none());
        if fresh {
            let types = cstring(&raw)?;
            // SAFETY: the IMP's convention serves every shape `shape` accepts.
            if !unsafe {
                (api.class_add_method)(
                    cls,
                    sel,
                    imp as extern "C" fn(Id, Id, Id, Id) -> u64 as usize,
                    types.as_ptr(),
                )
            } {
                return Err(format!("class_addMethod refused {name} {selector}"));
            }
            // The runtime keeps the pointer.
            std::mem::forget(types);
        }
    }
    if existing.is_none() {
        for p in protos {
            // SAFETY: a class being defined and a protocol.
            unsafe { (api.class_add_protocol)(cls, p) };
        }
        // SAFETY: allocated above, registered once.
        unsafe { (api.register_class_pair)(cls) };
        with(|s| s.defined.insert(name.to_owned(), cls));
    }
    Ok(cls)
}

/// Every method a program defines has this IMP: on AArch64 `(self, _cmd, a, b) -> x0`
/// serves each shape `shape` admits (a void method's x0 is ignored, BOOL is its low
/// byte, and a missing argument is a register nobody reads).
extern "C" fn imp(this: Id, cmd: Id, a: Id, b: Id) -> u64 {
    let Ok(api) = api() else { return 0 };
    let Some(bound) = lookup(api, this, cmd) else { return 0 };
    let caller = CALLER.get();
    let callback = with(|s| s.callback.clone());
    let (Some(callback), false) = (callback, caller.is_null()) else {
        return 0;
    };
    // The wrappers the callback makes each own one reference.
    let retained = |o: Id| {
        if o != 0 {
            // SAFETY: a live object the runtime handed the method.
            unsafe { (api.retain)(o) };
        }
        o as i64
    };
    let args = (
        bound.closure,
        retained(this),
        if bound.argc >= 1 { retained(a) } else { 0 },
        if bound.argc >= 2 { retained(b) } else { 0 },
        bound.argc,
    );
    // SAFETY: CALLER is the Caller of the host call this callback happens inside, on this
    // thread, which does not touch it until the call returns.
    let caller = unsafe { &mut *(caller as *mut Caller<'_, WasiP1Ctx>) };
    match callback.call(caller.as_context_mut(), args) {
        Ok(v) => match bound.ret {
            Kind::Bool => (v != 0) as u64,
            _ => v as u64,
        },
        Err(e) => {
            // Never unwind into the native frame above: the module has already printed a
            // Lisp error it did not handle, so this is an exit or a trap.
            if let Some(code) = e.downcast_ref::<I32Exit>() {
                std::process::exit(code.0);
            }
            eprintln!("Error: {e:?}");
            std::process::exit(TRAP_EXIT);
        }
    }
}

fn lookup(api: &Api, this: Id, cmd: Id) -> Option<Bound> {
    // SAFETY: the receiver of the method being run.
    let mut cls = unsafe { (api.object_get_class)(this) };
    while cls != 0 {
        if let Some(b) = with(|s| s.handlers.get(&(cls, cmd)).copied()) {
            return Some(b);
        }
        // SAFETY: a live class.
        cls = unsafe { (api.class_get_superclass)(cls) };
    }
    None
}

/// Binds every `rlobjc` import.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    fn runtime() -> wasmtime::Result<&'static Api> {
        api().map_err(|e| wasmtime::Error::msg(format!("objc: {e}")))
    }
    linker.func_wrap(MODULE, "available", || -> i32 { api().is_ok() as i32 })?;
    linker.func_wrap(MODULE, "error", |mut caller: Caller<'_, WasiP1Ctx>| {
        let e = with(|s| std::mem::take(&mut s.error));
        return_string(&mut caller, &e)
    })?;
    linker.func_wrap(
        MODULE,
        "class",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| -> wasmtime::Result<i64> {
            let name = memory_string(&mut caller, p, n)?;
            let api = runtime()?;
            Ok(match api.class(&name) {
                Ok(c) => c as i64,
                Err(e) => {
                    with(|s| s.error = e);
                    0
                }
            })
        },
    )?;
    linker.func_wrap(
        MODULE,
        "class_name",
        |mut caller: Caller<'_, WasiP1Ctx>, object: i64| {
            let name = runtime()?.class_name(object as Id);
            return_string(&mut caller, &name)
        },
    )?;
    linker.func_wrap(MODULE, "arg_nil", || with(|s| s.push(Arg::Nil)))?;
    linker.func_wrap(MODULE, "arg_true", || with(|s| s.push(Arg::True)))?;
    linker.func_wrap(MODULE, "arg_error", || with(|s| s.push(Arg::Error)))?;
    linker.func_wrap(MODULE, "arg_int", |n: i64| with(|s| s.push(Arg::Leaf(Leaf::Int(n)))))?;
    linker.func_wrap(MODULE, "arg_float", |f: f64| {
        with(|s| s.push(Arg::Leaf(Leaf::Float(f))))
    })?;
    linker.func_wrap(MODULE, "arg_object", |a: i64| with(|s| s.push(Arg::Object(a as Id))))?;
    linker.func_wrap(
        MODULE,
        "arg_string",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| {
            let text = memory_string(&mut caller, p, n)?;
            with(|s| s.push(Arg::Str(text)));
            wasmtime::Result::<()>::Ok(())
        },
    )?;
    linker.func_wrap(MODULE, "arg_struct", |n: i32| {
        with(|s| {
            if n <= 0 {
                s.args.push(Arg::Struct(Vec::new()));
            } else {
                s.pending_struct = Some((n as usize, Vec::new()));
            }
        })
    })?;
    linker.func_wrap(
        MODULE,
        "send",
        |mut caller: Caller<'_, WasiP1Ctx>, receiver: i64, p: i32, n: i32| {
            let selector = memory_string(&mut caller, p, n)?;
            let api = runtime()?;
            let _entered = Entered::new(&mut caller);
            wasmtime::Result::<i32>::Ok(send(api, receiver as Id, &selector))
        },
    )?;
    linker.func_wrap(MODULE, "result_int", || with(|s| s.answer.int))?;
    linker.func_wrap(MODULE, "result_float", || with(|s| s.answer.float))?;
    linker.func_wrap(MODULE, "result_string", |mut caller: Caller<'_, WasiP1Ctx>| {
        let t = with(|s| std::mem::take(&mut s.answer.string));
        return_string(&mut caller, &t)
    })?;
    linker.func_wrap(MODULE, "result_count", || with(|s| s.answer.leaves.len() as i32))?;
    linker.func_wrap(MODULE, "result_leaf_float_p", |i: i32| {
        with(|s| matches!(s.answer.leaves.get(i as usize), Some(Leaf::Float(_))) as i32)
    })?;
    linker.func_wrap(MODULE, "result_leaf_int", |i: i32| {
        with(|s| match s.answer.leaves.get(i as usize) {
            Some(Leaf::Int(n)) => *n,
            _ => 0,
        })
    })?;
    linker.func_wrap(MODULE, "result_leaf_float", |i: i32| {
        with(|s| match s.answer.leaves.get(i as usize) {
            Some(Leaf::Float(f)) => *f,
            _ => 0.0,
        })
    })?;
    linker.func_wrap(
        MODULE,
        "define_class",
        |mut caller: Caller<'_, WasiP1Ctx>,
         name_p: i32,
         name_n: i32,
         sup_p: i32,
         sup_n: i32,
         protocols_p: i32,
         protocols_n: i32,
         methods_p: i32,
         methods_n: i32,
         first: i32|
         -> wasmtime::Result<i64> {
            let name = memory_string(&mut caller, name_p, name_n)?;
            let sup = memory_string(&mut caller, sup_p, sup_n)?;
            let lines = |t: String| {
                t.lines()
                    .filter(|l| !l.is_empty())
                    .map(str::to_owned)
                    .collect::<Vec<_>>()
            };
            let protocols = lines(memory_string(&mut caller, protocols_p, protocols_n)?);
            let methods = lines(memory_string(&mut caller, methods_p, methods_n)?);
            let api = runtime()?;
            Ok(match define_class(api, &name, &sup, &protocols, &methods, first) {
                Ok(cls) => cls as i64,
                Err(e) => {
                    with(|s| s.error = e);
                    0
                }
            })
        },
    )?;
    // An NSString / NSMutableData the wrapper owns (+1).
    linker.func_wrap(
        MODULE,
        "string",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| -> wasmtime::Result<i64> {
            let text = memory_string(&mut caller, p, n)?;
            let api = runtime()?;
            Ok(api.with_pool(|| match api.ns_string(&text) {
                // SAFETY: a live autoreleased string, retained before its pool drains.
                Ok(s) => unsafe { (api.retain)(s) as i64 },
                Err(e) => {
                    with(|st| st.error = e);
                    0
                }
            }))
        },
    )?;
    linker.func_wrap(
        MODULE,
        "data",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| -> wasmtime::Result<i64> {
            let bytes = memory_bytes(&mut caller, p, n)?;
            let api = runtime()?;
            Ok(api.with_pool(|| {
                let made = api.class("NSMutableData").and_then(|cls| {
                    api.send_objects(
                        cls,
                        "dataWithBytes:length:",
                        &[
                            Leaf::Int(if bytes.is_empty() { 0 } else { bytes.as_ptr() as i64 }),
                            Leaf::Int(bytes.len() as i64),
                        ],
                    )
                });
                match made {
                    // SAFETY: a live autoreleased data, retained before its pool drains.
                    Ok(d) if d != 0 => unsafe { (api.retain)(d) as i64 },
                    Ok(_) => {
                        with(|st| st.error = "dataWithBytes:length: answered nil".into());
                        0
                    }
                    Err(e) => {
                        with(|st| st.error = e);
                        0
                    }
                }
            }))
        },
    )?;
    // The bytes of an NSData, copied out: the length (negative: failed), then the copy.
    linker.func_wrap(MODULE, "data_length", |object: i64| -> wasmtime::Result<i64> {
        let api = runtime()?;
        Ok(api.with_pool(|| match api.send(object as Id, "length", Vec::new()) {
            Ok((ty, leaves)) if ty.kind == Kind::Int64 => match leaves.first() {
                Some(Leaf::Int(n)) => *n,
                _ => 0,
            },
            Ok(_) => {
                with(|s| s.error = "length answered no integer; the receiver is not an NSData".into());
                -1
            }
            Err(e) => {
                with(|s| s.error = e);
                -1
            }
        }))
    })?;
    linker.func_wrap(
        MODULE,
        "data_bytes",
        |mut caller: Caller<'_, WasiP1Ctx>, object: i64, ptr: i32, cap: i32| {
            let api = runtime()?;
            let block = api.with_pool(|| -> Result<Vec<u8>, String> {
                let (_, l) = api.send(object as Id, "length", Vec::new())?;
                let n = match l.first() {
                    Some(Leaf::Int(n)) => *n as usize,
                    _ => 0,
                };
                if n == 0 {
                    return Ok(Vec::new());
                }
                let (_, l) = api.send(object as Id, "bytes", Vec::new())?;
                match l.first() {
                    // SAFETY: the data's own block of `n` bytes, alive while it is.
                    Some(Leaf::Int(p)) if *p != 0 => {
                        Ok(unsafe { std::slice::from_raw_parts(*p as *const u8, n) }.to_vec())
                    }
                    _ => Err(format!("bytes answered NULL for a data of {n} byte(s)")),
                }
            });
            match block {
                Ok(bytes) => return_bytes(&mut caller, &bytes, ptr, cap),
                Err(e) => {
                    with(|s| s.error = e);
                    Ok(-1)
                }
            }
        },
    )?;
    linker.func_wrap(MODULE, "release", |object: i64| -> wasmtime::Result<()> {
        if object != 0 {
            // SAFETY: a reference the library owns.
            unsafe { (runtime()?.release)(object as Id) };
        }
        Ok(())
    })?;
    linker.func_wrap(
        MODULE,
        "pump",
        |mut caller: Caller<'_, WasiP1Ctx>, seconds: f64| -> wasmtime::Result<()> {
            let api = runtime()?;
            let _entered = Entered::new(&mut caller);
            pump(api, seconds);
            Ok(())
        },
    )?;
    Ok(())
}

/// Finds the module's callback export once it is instantiated.
pub fn bind(instance: &Instance, store: &mut Store<WasiP1Ctx>) -> wasmtime::Result<()> {
    let callback = instance.get_typed_func::<(i32, i64, i64, i64, i32), i64>(&mut *store, CALLBACK_EXPORT)?;
    with(|s| s.callback = Some(callback));
    Ok(())
}

impl State {
    /// An argument for the next send; a struct's leaves are collected first.
    fn push(&mut self, arg: Arg) {
        if let Some((n, mut leaves)) = self.pending_struct.take() {
            if let Arg::Leaf(l) = arg {
                leaves.push(l);
            }
            if leaves.len() >= n {
                self.args.push(Arg::Struct(leaves));
            } else {
                self.pending_struct = Some((n, leaves));
            }
            return;
        }
        self.args.push(arg);
    }
}
