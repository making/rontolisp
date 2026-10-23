//! The TCP host of a `--native` output: the `rltcp` imports `runner-sockets.lisp` -- the
//! runner's provider of `rontolisp:tcp-*` -- is written over (`eval/SocketsLibrary`;
//! `.kb/tcp-sockets.md`, "--native: the runner's sockets").
//!
//! The module owns the handle table, the read buffering and every error text; the runner
//! owns the sockets: one `std::net` stream or listener per number it answered, and every
//! call BLOCKS the module's one thread, as the interpreter's `java.net.Socket` calls do
//! (a Preview 1 module has no scheduler to hand the wait to). A host name resolves as the
//! JDK resolves one. A call that fails leaves the OS's text, worded as the JDK words it
//! (no `(os error N)`; a read past its deadline is `Read timed out`), for `lastError`, and
//! answers -1; the module signals with it under the operator's name.

use std::collections::BTreeMap;
use std::io::{ErrorKind, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use wasmtime::{Caller, Extern, Linker};
use wasmtime_wasi::p1::WasiP1Ctx;

use crate::http::{memory_string, reason, return_string};

/// The import module `runner-sockets.lisp`'s `rontolisp:wasm-import`s name.
pub const MODULE: &str = "rltcp";

/// A socket by the number the module holds: shared with the call blocked in it, so the
/// table is never locked across a read or an accept.
#[derive(Clone)]
enum Socket {
    Stream(Arc<TcpStream>),
    Listener(Arc<TcpListener>),
}

/// The sockets open, by number; numbers are never reused while the process runs.
static SOCKETS: Mutex<(i32, BTreeMap<i32, Socket>)> = Mutex::new((1, BTreeMap::new()));

/// Why the last call that answered -1 failed.
static ERROR: Mutex<String> = Mutex::new(String::new());

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

fn register(socket: Socket) -> i32 {
    let mut table = lock(&SOCKETS);
    let handle = table.0;
    table.0 += 1;
    table.1.insert(handle, socket);
    handle
}

fn socket(handle: i32) -> Result<Socket, String> {
    lock(&SOCKETS)
        .1
        .get(&handle)
        .cloned()
        .ok_or_else(|| "not an open socket".to_owned())
}

fn stream(handle: i32) -> Result<Arc<TcpStream>, String> {
    match socket(handle)? {
        Socket::Stream(s) => Ok(s),
        Socket::Listener(_) => Err("not a connected socket".into()),
    }
}

/// The text of an I/O error the module signals with: the JDK's words for a read that
/// reached its deadline, else the OS's.
fn io_reason(e: &std::io::Error) -> String {
    match e.kind() {
        ErrorKind::WouldBlock | ErrorKind::TimedOut => "Read timed out".into(),
        _ => reason(e),
    }
}

fn port_of(port: i32) -> Result<u16, String> {
    u16::try_from(port).map_err(|_| format!("port {port} is out of range"))
}

/// Connects to `host:port` (a name or an address literal) and answers the socket's number.
fn connect(host: &str, port: i32) -> Result<i32, String> {
    let stream = TcpStream::connect((host, port_of(port)?)).map_err(|e| reason(&e))?;
    Ok(register(Socket::Stream(Arc::new(stream))))
}

/// Binds `host:port` (an empty host is every interface) and answers the listener's number.
fn listen(host: &str, port: i32) -> Result<i32, String> {
    let bind = if host.is_empty() { "0.0.0.0" } else { host };
    let listener = TcpListener::bind((bind, port_of(port)?)).map_err(|e| reason(&e))?;
    Ok(register(Socket::Listener(Arc::new(listener))))
}

/// Waits for the next connection on a listener and answers the socket's number.
fn accept(handle: i32) -> Result<i32, String> {
    let Socket::Listener(listener) = socket(handle)? else {
        return Err("not a listener".into());
    };
    let (stream, _) = listener.accept().map_err(|e| reason(&e))?;
    Ok(register(Socket::Stream(Arc::new(stream))))
}

/// Reads into `buf`: the count, 0 at the peer's close.
fn read(handle: i32, buf: &mut [u8]) -> Result<usize, String> {
    let stream = stream(handle)?;
    loop {
        match (&*stream).read(buf) {
            Ok(n) => return Ok(n),
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => return Err(io_reason(&e)),
        }
    }
}

fn write(handle: i32, bytes: &[u8]) -> Result<(), String> {
    let stream = stream(handle)?;
    (&*stream).write_all(bytes).map_err(|e| io_reason(&e))
}

/// Closes the socket: the last handle on it is dropped here, which sends the FIN.
fn close(handle: i32) {
    lock(&SOCKETS).1.remove(&handle);
}

fn local_addr(handle: i32) -> Result<SocketAddr, String> {
    match socket(handle)? {
        Socket::Stream(s) => s.local_addr(),
        Socket::Listener(l) => l.local_addr(),
    }
    .map_err(|e| reason(&e))
}

fn peer_addr(handle: i32) -> Result<SocketAddr, String> {
    stream(handle)?.peer_addr().map_err(|e| reason(&e))
}

/// The read deadline, in milliseconds; 0 clears it.
fn set_timeout(handle: i32, millis: i32) -> Result<(), String> {
    let deadline = u64::try_from(millis)
        .ok()
        .filter(|&ms| ms > 0)
        .map(Duration::from_millis);
    stream(handle)?.set_read_timeout(deadline).map_err(|e| reason(&e))
}

/// -1 after leaving `e` for `lastError`.
fn fail(e: String) -> i32 {
    *lock(&ERROR) = e;
    -1
}

fn answer(result: Result<i32, String>) -> i32 {
    result.unwrap_or_else(fail)
}

/// Binds every `rltcp` import.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    // connect(host, port) -> socket, or -1.
    linker.func_wrap(
        MODULE,
        "connect",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32, port: i32| -> wasmtime::Result<i32> {
            let host = memory_string(&mut caller, p, n)?;
            Ok(answer(connect(&host, port)))
        },
    )?;
    // listen(host, port) -> listener, or -1.
    linker.func_wrap(
        MODULE,
        "listen",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32, port: i32| -> wasmtime::Result<i32> {
            let host = memory_string(&mut caller, p, n)?;
            Ok(answer(listen(&host, port)))
        },
    )?;
    // accept(listener) -> socket, or -1: blocks until a connection arrives.
    linker.func_wrap(MODULE, "accept", |handle: i32| -> i32 { answer(accept(handle)) })?;
    // read(socket, ptr, cap) -> the octets written at ptr, 0 at the peer's close, -1 on
    // a failure: blocks while nothing has arrived.
    linker.func_wrap(
        MODULE,
        "read",
        |mut caller: Caller<'_, WasiP1Ctx>, handle: i32, ptr: i32, cap: i32| -> wasmtime::Result<i32> {
            let Some(Extern::Memory(memory)) = caller.get_export("memory") else {
                wasmtime::bail!("the module exports no memory");
            };
            let (start, end) = (ptr as u32 as usize, ptr as u32 as usize + cap.max(0) as usize);
            let Some(buf) = memory.data_mut(&mut caller).get_mut(start..end) else {
                wasmtime::bail!("a receive buffer lies outside linear memory");
            };
            Ok(match read(handle, buf) {
                Ok(n) => n as i32,
                Err(e) => fail(e),
            })
        },
    )?;
    // write(socket, octets) -> 0, or -1: blocks until the kernel has taken the bytes.
    linker.func_wrap(
        MODULE,
        "write",
        |mut caller: Caller<'_, WasiP1Ctx>, handle: i32, ptr: i32, len: i32| -> wasmtime::Result<i32> {
            let Some(Extern::Memory(memory)) = caller.get_export("memory") else {
                wasmtime::bail!("the module exports no memory");
            };
            let (start, end) = (ptr as u32 as usize, ptr as u32 as usize + len as u32 as usize);
            let Some(bytes) = memory.data(&caller).get(start..end) else {
                wasmtime::bail!("a write lies outside linear memory");
            };
            Ok(match write(handle, bytes) {
                Ok(()) => 0,
                Err(e) => fail(e),
            })
        },
    )?;
    linker.func_wrap(MODULE, "close", close)?;
    linker.func_wrap(MODULE, "localPort", |handle: i32| -> i32 {
        answer(local_addr(handle).map(|a| i32::from(a.port())))
    })?;
    linker.func_wrap(
        MODULE,
        "localAddress",
        |mut caller: Caller<'_, WasiP1Ctx>, handle: i32| -> wasmtime::Result<(i32, i32)> {
            let text = local_addr(handle).map(|a| a.ip().to_string()).unwrap_or_else(|e| {
                fail(e);
                String::new()
            });
            return_string(&mut caller, &text)
        },
    )?;
    linker.func_wrap(
        MODULE,
        "peerAddress",
        |mut caller: Caller<'_, WasiP1Ctx>, handle: i32| -> wasmtime::Result<(i32, i32)> {
            let text = peer_addr(handle).map(|a| a.ip().to_string()).unwrap_or_else(|e| {
                fail(e);
                String::new()
            });
            return_string(&mut caller, &text)
        },
    )?;
    linker.func_wrap(MODULE, "peerPort", |handle: i32| -> i32 {
        answer(peer_addr(handle).map(|a| i32::from(a.port())))
    })?;
    // setTimeout(socket, millis) -> 0, or -1; 0 ms clears the read deadline.
    linker.func_wrap(MODULE, "setTimeout", |handle: i32, millis: i32| -> i32 {
        answer(set_timeout(handle, millis).map(|()| 0))
    })?;
    // lastError() -> why the last call that answered -1 failed.
    linker.func_wrap(
        MODULE,
        "lastError",
        |mut caller: Caller<'_, WasiP1Ctx>| -> wasmtime::Result<(i32, i32)> {
            let text = lock(&ERROR).clone();
            return_string(&mut caller, &text)
        },
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A loopback exchange through the host functions as the module drives them, with
    /// no module: listen, connect, accept, both directions, the addresses, the deadline
    /// and the close.
    #[test]
    fn a_loopback_connection_carries_bytes_both_ways_and_ends_at_the_close() {
        let listener = listen("127.0.0.1", 0).unwrap();
        let port = local_addr(listener).unwrap().port();
        assert_eq!(local_addr(listener).unwrap().ip().to_string(), "127.0.0.1");
        assert!(peer_addr(listener).is_err(), "a listener has no peer");
        let client = connect("localhost", i32::from(port)).unwrap();
        let server = accept(listener).unwrap();
        assert_eq!(peer_addr(server).unwrap().port(), local_addr(client).unwrap().port());
        write(client, b"hello\n\xff").unwrap();
        let mut buf = [0u8; 16];
        let mut got = Vec::new();
        while got.len() < 7 {
            let n = read(server, &mut buf).unwrap();
            got.extend_from_slice(&buf[..n]);
        }
        assert_eq!(got, b"hello\n\xff");
        write(server, b"back").unwrap();
        let n = read(client, &mut buf).unwrap();
        assert_eq!(&buf[..n], b"back");
        set_timeout(client, 50).unwrap();
        assert_eq!(read(client, &mut buf).unwrap_err(), "Read timed out");
        set_timeout(client, 0).unwrap();
        close(server);
        assert_eq!(read(client, &mut buf).unwrap(), 0, "the peer's close is end of stream");
        close(client);
        close(listener);
        assert_eq!(read(client, &mut buf).unwrap_err(), "not an open socket");
        assert!(accept(listener).is_err());
    }

    #[test]
    fn a_failure_keeps_the_os_text_without_the_errno_and_answers_minus_one() {
        assert_eq!(answer(connect("127.0.0.1", 70000)), -1);
        assert_eq!(lock(&ERROR).as_str(), "port 70000 is out of range");
        let held = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = i32::from(held.local_addr().unwrap().port());
        let why = listen("127.0.0.1", port).unwrap_err();
        assert!(!why.is_empty() && !why.contains("os error"), "{why}");
        // Nothing listens on port 1 of the loopback in a test environment.
        let refused = connect("127.0.0.1", 1).unwrap_err();
        assert!(!refused.is_empty() && !refused.contains("os error"), "{refused}");
        assert_eq!(set_timeout(-5, 1).unwrap_err(), "not an open socket");
    }
}
