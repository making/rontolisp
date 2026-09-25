//! The HTTP host of a `--native` output: the `rlhttp` imports the compiled
//! `rontolisp:fetch` is written over (`eval/HostFetchLibrary`, the runner transport;
//! `.kb/fetch-http.md`, "--native").
//!
//! A fetch is a REQUEST IN FLIGHT when the call returns, as on the interpreter and the
//! JVM: `start` hands the request to a thread of its own and answers a handle at once, so
//! several requests overlap and one that is never awaited is still sent. The module
//! settles its future on the first `await`, which is where `head` blocks for the status
//! and the fields -- and where a transport failure (a refused connection, a certificate
//! nobody vouches for, a malformed URL) arrives as the head's `"error"` member, to be
//! signalled there. After the head the thread goes on reading the body into memory, the
//! way `HttpClient` fills the interpreter's stream, so the connection closes as soon as
//! the origin is done whether or not the program drains it; `readResponseBody` answers
//! what has arrived, blocking only while nothing has.
//!
//! The handle is an `externref` whose host data owns the reply: when the module's last
//! reference to it dies -- the future and the body stream are gone -- wasmtime's
//! collector drops it, the socket is shut down under the thread (which ends even a read
//! a stalled origin left it blocked in) and the buffered octets are freed (the finalizer
//! `.kb/objc.md` releases Objective-C objects with). Host memory does not make the module
//! collect, so `start` asks for a collection itself once the replies alive or the octets
//! they hold pass a threshold.
//!
//! Nothing here runs before the first `start`: the TLS configuration is built on the first
//! HTTPS request, trusting Mozilla's roots (`webpki-roots`) or, when `SSL_CERT_FILE` names
//! a PEM bundle, that bundle's instead (`wire::tls_config`).

mod json;
mod url;
mod wire;

use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};

use wasmtime::{Caller, Extern, ExternRef, Linker, Rooted, TypedFunc};
use wasmtime_wasi::p1::WasiP1Ctx;

use wire::{Head, Request};

/// The import module the library's `rontolisp:wasm-import`s name.
pub const MODULE: &str = "rlhttp";

/// How many octets one read of the origin takes.
const READ_CHUNK: usize = 64 * 1024;

/// Replies alive (handles not yet dropped by the collector) and the octets they hold.
static LIVE: AtomicUsize = AtomicUsize::new(0);
static BUFFERED: AtomicUsize = AtomicUsize::new(0);

/// The counts past which `start` collects first; doubled past what survives a collection.
const LIVE_FLOOR: usize = 256;
const BUFFERED_FLOOR: usize = 64 * 1024 * 1024;
static LIMITS: Mutex<(usize, usize)> = Mutex::new((LIVE_FLOOR, BUFFERED_FLOOR));

/// One request and its reply, shared by the module's handle and the thread serving it.
#[derive(Default)]
struct Reply {
    state: Mutex<State>,
    changed: Condvar,
}

#[derive(Default)]
struct State {
    /// The reply head as the JSON the module parses, or why there is none.
    head: Option<Result<String, String>>,
    /// Body octets read and not yet answered, from `pos`.
    data: Vec<u8>,
    pos: usize,
    /// The body's end: `Ok` when it arrived whole, `Err` when the transfer failed.
    end: Option<Result<(), String>>,
    /// The module dropped its handle: stop reading, keep nothing.
    released: bool,
    /// A second handle on the request's socket while the transfer runs: the release
    /// shuts it down, which ends a read the serving thread is blocked in (a stalled
    /// origin would otherwise hold the thread and the connection for good).
    socket: Option<TcpStream>,
}

impl Reply {
    fn lock(&self) -> MutexGuard<'_, State> {
        // A thread that panicked holding the lock left a state no worse than partial.
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn update(&self, f: impl FnOnce(&mut State)) {
        f(&mut self.lock());
        self.changed.notify_all();
    }
}

/// The host data of a module's handle: dropped by wasmtime's collector when the handle
/// dies, which releases the reply.
struct Handle(Arc<Reply>);

impl Handle {
    /// A handle on `reply`, counted among the replies alive.
    fn new(reply: Arc<Reply>) -> Handle {
        LIVE.fetch_add(1, Ordering::Relaxed);
        Handle(reply)
    }
}

impl Drop for Handle {
    fn drop(&mut self) {
        self.0.update(|s| {
            s.released = true;
            BUFFERED.fetch_sub(s.data.len() - s.pos, Ordering::Relaxed);
            s.data = Vec::new();
            s.pos = 0;
            if let Some(socket) = s.socket.take() {
                let _ = socket.shutdown(Shutdown::Both);
            }
        });
        LIVE.fetch_sub(1, Ordering::Relaxed);
    }
}

/// The request record the module wrote (`FetchResponseShape`'s `request`).
fn request(text: &str) -> Result<Request, String> {
    let v = json::parse(text)?;
    let field = |name: &str| v.get(name).and_then(json::Value::as_str).map(str::to_owned);
    let mut headers = Vec::new();
    if let Some(json::Value::Array(pairs)) = v.get("headers") {
        for pair in pairs {
            match pair {
                json::Value::Array(nv) if nv.len() == 2 => match (nv[0].as_str(), nv[1].as_str()) {
                    (Some(n), Some(value)) => headers.push((n.to_owned(), value.to_owned())),
                    _ => return Err("a header is not a pair of strings".into()),
                },
                _ => return Err("a header is not a pair of strings".into()),
            }
        }
    }
    Ok(Request {
        method: field("method").unwrap_or_else(|| "GET".into()),
        url: field("url").ok_or("the request names no url")?,
        headers,
        body: field("body").map(String::into_bytes),
    })
}

/// The reply head as the module reads it: `FetchResponseShape`'s `response` record
/// without its `body`, which crosses through `readResponseBody`.
fn head_json(head: &Head) -> String {
    let mut out = format!("{{\"status\":{},\"headers\":[", head.status);
    for (i, (name, value)) in head.headers.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push('[');
        json::write_string(&mut out, name);
        out.push(',');
        json::write_string(&mut out, value);
        out.push(']');
    }
    out.push_str("]}");
    out
}

/// The error arm of the head (`FetchResponseShape.HOST_ENVELOPE_ERROR_KEY`).
fn error_json(message: &str) -> String {
    let mut out = String::from("{\"error\":");
    json::write_string(&mut out, message);
    out.push('}');
    out
}

/// What the thread serving one request does: the exchange, then the body into memory.
fn serve(reply: &Reply, req: &Request) {
    serve_with(reply, req, &wire::tls_config);
}

/// [`serve`] under the TLS configuration `tls` answers.
fn serve_with(reply: &Reply, req: &Request, tls: &dyn Fn() -> wire::TlsConfig) {
    // However the transfer ends, the reply's handle on its socket ends with it.
    let _transfer = Transfer(reply);
    let started = wire::validate(req)
        .and_then(|()| url::parse(&req.url))
        .and_then(|target| {
            let (conn, socket) = wire::connect(&target, tls)?;
            hand_over(reply, socket)?;
            wire::exchange(conn, req, &target)
        });
    let mut body = match started {
        Ok((head, body)) => {
            reply.update(|s| s.head = Some(Ok(head_json(&head))));
            body
        }
        Err(e) => {
            reply.update(|s| s.head = Some(Err(e)));
            return;
        }
    };
    let mut chunk = vec![0u8; READ_CHUNK];
    loop {
        if reply.lock().released {
            return;
        }
        let end = match body.read(&mut chunk) {
            Ok(0) => Some(Ok(())),
            Ok(n) => {
                reply.update(|s| {
                    if !s.released {
                        // What was answered already goes once it is half the buffer, so
                        // a reader keeping pace holds the unread octets and no more.
                        if s.pos > 0 && s.pos >= s.data.len() / 2 {
                            s.data.drain(..s.pos);
                            s.pos = 0;
                        }
                        s.data.extend_from_slice(&chunk[..n]);
                        BUFFERED.fetch_add(n, Ordering::Relaxed);
                    }
                });
                None
            }
            Err(e) => Some(Err(format!("the response body failed: {e}"))),
        };
        if let Some(end) = end {
            reply.update(|s| s.end = Some(end));
            return;
        }
    }
}

/// Gives the reply its handle on the transfer's socket; when the reply was released
/// already, shuts the socket down instead and answers why the transfer stops.
fn hand_over(reply: &Reply, socket: TcpStream) -> Result<(), String> {
    let mut s = reply.lock();
    if s.released {
        let _ = socket.shutdown(Shutdown::Both);
        return Err("the reply was released".into());
    }
    s.socket = Some(socket);
    Ok(())
}

/// Drops the reply's handle on the socket when the transfer ends, closing the descriptor.
struct Transfer<'a>(&'a Reply);

impl Drop for Transfer<'_> {
    fn drop(&mut self) {
        self.0.lock().socket = None;
    }
}

/// Blocks until `ready` answers, holding the lock only to ask. On macOS a program whose
/// application started keeps its windows live meanwhile: thread 0's event loop turns
/// between the questions, as it does while the program sleeps.
fn wait<T>(caller: &mut Caller<'_, WasiP1Ctx>, reply: &Reply, mut ready: impl FnMut(&mut State) -> Option<T>) -> T {
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    {
        let mut answer = None;
        if crate::objc::pump_until(caller, &mut || {
            answer = ready(&mut reply.lock());
            answer.is_some()
        }) {
            return answer.expect("pump_until returns once the answer is there");
        }
    }
    #[cfg(not(all(target_os = "macos", target_arch = "aarch64")))]
    let _ = caller;
    let mut state = reply.lock();
    loop {
        if let Some(v) = ready(&mut state) {
            return v;
        }
        state = reply.changed.wait(state).unwrap_or_else(|p| p.into_inner());
    }
}

/// Collects first when the replies alive, or the octets they hold, pass the limits: a
/// program that drops replies unread would otherwise keep them until its own allocation
/// happens to trigger a collection, and a reply's memory is invisible to the heap.
fn maybe_collect(caller: &mut Caller<'_, WasiP1Ctx>) -> wasmtime::Result<()> {
    let mut limits = LIMITS.lock().unwrap_or_else(|p| p.into_inner());
    if LIVE.load(Ordering::Relaxed) < limits.0 && BUFFERED.load(Ordering::Relaxed) < limits.1 {
        return Ok(());
    }
    caller.gc(None)?;
    let (live, buffered) = (LIVE.load(Ordering::Relaxed), BUFFERED.load(Ordering::Relaxed));
    *limits = (LIVE_FLOOR.max(live * 2), BUFFERED_FLOOR.max(buffered * 2));
    Ok(())
}

fn reply_of(caller: &Caller<'_, WasiP1Ctx>, handle: Option<Rooted<ExternRef>>) -> wasmtime::Result<Arc<Reply>> {
    let Some(handle) = handle else {
        wasmtime::bail!("rlhttp: no reply handle");
    };
    match handle.data(caller)?.and_then(|d| d.downcast_ref::<Handle>()) {
        Some(h) => Ok(h.0.clone()),
        None => wasmtime::bail!("rlhttp: not a reply handle"),
    }
}

fn memory_string(caller: &mut Caller<'_, WasiP1Ctx>, ptr: i32, len: i32) -> wasmtime::Result<String> {
    let Some(Extern::Memory(memory)) = caller.get_export("memory") else {
        wasmtime::bail!("the module exports no memory");
    };
    let (start, end) = (ptr as u32 as usize, ptr as u32 as usize + len as u32 as usize);
    match memory.data(&caller).get(start..end) {
        Some(bytes) => Ok(String::from_utf8_lossy(bytes).into_owned()),
        None => wasmtime::bail!("a string argument lies outside linear memory"),
    }
}

/// A `:string` result: bytes written into a block `__ronto_alloc` reserves.
fn return_string(caller: &mut Caller<'_, WasiP1Ctx>, s: &str) -> wasmtime::Result<(i32, i32)> {
    let alloc: TypedFunc<i32, i32> = match caller.get_export("__ronto_alloc") {
        Some(Extern::Func(f)) => f.typed(&caller)?,
        _ => wasmtime::bail!("the module exports no __ronto_alloc"),
    };
    let ptr = alloc.call(&mut *caller, s.len() as i32)?;
    let Some(Extern::Memory(memory)) = caller.get_export("memory") else {
        wasmtime::bail!("the module exports no memory");
    };
    memory.write(&mut *caller, ptr as u32 as usize, s.as_bytes())?;
    Ok((ptr, s.len() as i32))
}

/// Binds every `rlhttp` import.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    // start(request-json) -> handle: the request is in flight when this returns.
    linker.func_wrap(
        MODULE,
        "start",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32| -> wasmtime::Result<Option<Rooted<ExternRef>>> {
            maybe_collect(&mut caller)?;
            let text = memory_string(&mut caller, p, n)?;
            let reply = Arc::new(Reply::default());
            match request(&text) {
                Ok(req) => {
                    let serving = reply.clone();
                    let spawned = std::thread::Builder::new()
                        .name("rlhttp".into())
                        .spawn(move || serve(&serving, &req));
                    if let Err(e) = spawned {
                        reply.update(|s| s.head = Some(Err(format!("cannot start the request: {e}"))));
                    }
                }
                Err(e) => reply.update(|s| s.head = Some(Err(e))),
            }
            Ok(Some(ExternRef::new(&mut caller, Handle::new(reply))?))
        },
    )?;
    // head(handle) -> the reply head JSON, or its error arm: blocks until it is there.
    linker.func_wrap(
        MODULE,
        "head",
        |mut caller: Caller<'_, WasiP1Ctx>, handle: Option<Rooted<ExternRef>>| -> wasmtime::Result<(i32, i32)> {
            let reply = reply_of(&caller, handle)?;
            let head = wait(&mut caller, &reply, |s| s.head.clone());
            let text = match head {
                Ok(json) => json,
                Err(e) => error_json(&e),
            };
            return_string(&mut caller, &text)
        },
    )?;
    // readResponseBody(handle, ptr, cap) -> the octets written at ptr; 0 at the end of
    // the body, -1 when its transfer failed.
    linker.func_wrap(
        MODULE,
        "readResponseBody",
        |mut caller: Caller<'_, WasiP1Ctx>,
         handle: Option<Rooted<ExternRef>>,
         ptr: i32,
         cap: i32|
         -> wasmtime::Result<i32> {
            let reply = reply_of(&caller, handle)?;
            let cap = cap.max(0) as usize;
            let chunk = wait(&mut caller, &reply, |s| {
                if s.pos < s.data.len() {
                    let n = cap.min(s.data.len() - s.pos);
                    let bytes = s.data[s.pos..s.pos + n].to_vec();
                    s.pos += n;
                    BUFFERED.fetch_sub(n, Ordering::Relaxed);
                    Some(Ok(bytes))
                } else {
                    s.end.clone().map(|end| end.map(|()| Vec::new()))
                }
            });
            match chunk {
                Ok(bytes) => {
                    let Some(Extern::Memory(memory)) = caller.get_export("memory") else {
                        wasmtime::bail!("the module exports no memory");
                    };
                    memory.write(&mut caller, ptr as u32 as usize, &bytes)?;
                    Ok(bytes.len() as i32)
                }
                Err(_) => Ok(-1),
            }
        },
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_request_record_the_module_writes() {
        let req = request(
            r#"{"url":"http://127.0.0.1:1/x","method":"POST","headers":[["X-A","1"],["User-Agent","ua"]],"body":"hé"}"#,
        )
        .unwrap();
        assert_eq!(
            (req.url.as_str(), req.method.as_str()),
            ("http://127.0.0.1:1/x", "POST")
        );
        assert_eq!(
            req.headers,
            vec![("X-A".into(), "1".into()), ("User-Agent".into(), "ua".into())]
        );
        assert_eq!(req.body.as_deref(), Some("hé".as_bytes()));
        let bare = request(r#"{"url":"http://h/","headers":[]}"#).unwrap();
        assert_eq!((bare.method.as_str(), bare.body), ("GET", None));
        assert!(request(r#"{"method":"GET"}"#).is_err());
        assert!(request(r#"{"url":"http://h/","headers":[["X"]]}"#).is_err());
    }

    #[test]
    fn writes_the_head_the_module_reads() {
        let head = Head {
            status: 404,
            headers: vec![("x-quote".into(), "a\"b".into())],
        };
        assert_eq!(head_json(&head), r#"{"status":404,"headers":[["x-quote","a\"b"]]}"#);
        assert_eq!(error_json("no \"route\""), r#"{"error":"no \"route\""}"#);
        assert!(json::parse(&head_json(&head)).is_ok());
    }

    /// A reply served by a local origin through the whole thread-and-state path, as the
    /// imports drive it (without a module).
    #[test]
    fn a_served_reply_arrives_whole_and_a_refused_one_as_an_error() {
        use std::io::{Read as _, Write as _};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let origin = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut seen = Vec::new();
            let mut b = [0u8; 1024];
            while !seen.ends_with(b"\r\n\r\nping") {
                let n = s.read(&mut b).unwrap();
                seen.extend_from_slice(&b[..n]);
            }
            let body = vec![b'x'; 300_000];
            write!(
                s,
                "HTTP/1.1 201 Created\r\nX-Seen: {}\r\nContent-Length: {}\r\n\r\n",
                seen.len(),
                body.len()
            )
            .unwrap();
            s.write_all(&body).unwrap();
            String::from_utf8(seen).unwrap()
        });
        let reply = Reply::default();
        let req = request(&format!(
            r#"{{"url":"http://127.0.0.1:{port}/p?q=1","method":"POST","headers":[["X-A","1"]],"body":"ping"}}"#
        ))
        .unwrap();
        serve(&reply, &req);
        let seen = origin.join().unwrap();
        assert!(
            seen.starts_with(&format!("POST /p?q=1 HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nX-A: 1\r\n")),
            "{seen}"
        );
        let s = reply.lock();
        let head = s.head.clone().unwrap().unwrap();
        assert!(
            head.starts_with(r#"{"status":201,"headers":[["content-length","300000"],["x-seen","#),
            "{head}"
        );
        assert_eq!((s.data.len(), s.end.clone()), (300_000, Some(Ok(()))));
        drop(s);

        let refused = Reply::default();
        serve(
            &refused,
            &request(r#"{"url":"http://127.0.0.1:1/","headers":[]}"#).unwrap(),
        );
        let e = refused.lock().head.clone().unwrap().unwrap_err();
        assert!(e.starts_with("cannot connect to 127.0.0.1:1"), "{e}");
        let restricted = Reply::default();
        serve(
            &restricted,
            &request(r#"{"url":"http://h/","headers":[["Host","x"]]}"#).unwrap(),
        );
        assert!(
            restricted
                .lock()
                .head
                .clone()
                .unwrap()
                .unwrap_err()
                .contains("restricted header name")
        );
    }
    /// Dropping the handle ends a transfer the origin stalled mid-body: the serving
    /// thread was blocked in a read, and the release shuts the socket down under it.
    #[test]
    fn a_release_ends_a_transfer_stalled_mid_body() {
        use std::io::Write as _;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (stalled_tx, stalled_rx) = std::sync::mpsc::channel::<std::net::TcpStream>();
        std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            s.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\npartial")
                .unwrap();
            // Kept open, and never written to again.
            stalled_tx.send(s).unwrap();
        });
        let reply = Arc::new(Reply::default());
        let handle = Handle::new(reply.clone());
        let req = request(&format!(r#"{{"url":"http://127.0.0.1:{port}/","headers":[]}}"#)).unwrap();
        let serving = reply.clone();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            serve(&serving, &req);
            done_tx.send(()).unwrap();
        });
        let _origin_side = stalled_rx.recv().unwrap();
        let timeout = std::time::Duration::from_secs(10);
        let deadline = std::time::Instant::now() + timeout;
        while reply.lock().data.len() < b"partial".len() {
            assert!(
                std::time::Instant::now() < deadline,
                "the head and the first octets never arrived"
            );
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        assert!(reply.lock().socket.is_some());
        assert!(done_rx.try_recv().is_err(), "the transfer ended before the origin did");
        drop(handle);
        done_rx
            .recv_timeout(timeout)
            .expect("the release did not end the transfer");
        let s = reply.lock();
        assert!(s.released && s.data.is_empty() && s.socket.is_none());
    }
}
