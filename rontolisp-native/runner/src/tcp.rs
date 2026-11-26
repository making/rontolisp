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
//!
//! A socket can also be made before its connect (`Fresh`): bound first or not, its connect
//! begun with no wait (`begin`), waited for among the program's threads until it can be
//! written to (`wait`) or a time limit has come, then ended (`end`) -- the Java subset's
//! `Socket` (`.kb/java-frontend.md`, "java.net.Socket and ServerSocket").
//!
//! A connected socket BUFFERS its writes (`Conn`): written through, each piece of a
//! message waited for the peer's delayed ACK of the one before under the kernel's Nagle.
//! The buffer goes out before every call that waits on ANY socket (`read`, `readable`,
//! `accept`, `connect`, `wait`: a single-threaded program writes on one socket and then
//! waits on its peer's end, which may be another socket of the same program), at `flush`
//! (`force-output`/`finish-output`), at `close`, when full, and at the program's end
//! (`flush_all` from `main`).

use std::collections::BTreeMap;
use std::ffi::{c_int, c_short, c_ulong};
use std::io::{ErrorKind, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::os::fd::{AsRawFd, RawFd};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use socket2::{Domain, Protocol, Type};

use wasmtime::{Caller, Extern, Linker};
use wasmtime_wasi::p1::WasiP1Ctx;

use crate::http::{memory_string, reason, return_string};

/// The import module `runner-sockets.lisp`'s `rontolisp:wasm-import`s name.
pub const MODULE: &str = "rltcp";

/// A socket by the number the module holds: shared with the call blocked in it, so the
/// table is never locked across a read or an accept.
#[derive(Clone)]
enum Socket {
    Stream(Arc<Conn>),
    Listener(Arc<Listen>),
    Fresh(Arc<Fresh>),
}

/// A listener and its accept deadline: an accept that gets no connection within it fails
/// with the JDK's words, `Accept timed out`.
struct Listen {
    listener: TcpListener,
    deadline: Mutex<Option<Duration>>,
}

/// A socket not connected yet: the OS socket once a bind or a connect has made one, and
/// whether a connect is under way. Its connect made, it becomes a `Stream` under the same
/// number.
struct Fresh {
    state: Mutex<FreshState>,
}

struct FreshState {
    raw: Option<socket2::Socket>,
    connecting: bool,
}

impl Fresh {
    fn new(raw: Option<socket2::Socket>) -> Arc<Fresh> {
        Arc::new(Fresh {
            state: Mutex::new(FreshState {
                raw,
                connecting: false,
            }),
        })
    }
}

/// `EINPROGRESS`: a connect begun with no wait goes on (`asm-generic/errno.h`, macOS's
/// `sys/errno.h`); `ErrorKind::InProgress` is not stable.
#[cfg(target_os = "linux")]
const EINPROGRESS: i32 = 115;
#[cfg(target_os = "macos")]
const EINPROGRESS: i32 = 36;

/// The size of a connected socket's output buffer: the interpreter's and the component's.
/// A write that does not fit sends the buffer first; one of this size or more goes out as
/// it is.
const OUTPUT_BUFFER: usize = 8192;

/// A connected socket and the bytes written to it that have not gone out yet.
struct Conn {
    stream: TcpStream,
    out: Mutex<Vec<u8>>,
}

impl Conn {
    fn new(stream: TcpStream) -> Arc<Conn> {
        Arc::new(Conn {
            stream,
            out: Mutex::new(Vec::new()),
        })
    }

    /// Sends what the buffer holds; on a failure the bytes stay for the next attempt.
    fn flush(&self) -> std::io::Result<()> {
        let mut out = lock(&self.out);
        if !out.is_empty() {
            (&self.stream).write_all(&out)?;
            out.clear();
        }
        Ok(())
    }

    fn write(&self, bytes: &[u8]) -> std::io::Result<()> {
        let mut out = lock(&self.out);
        if out.len() + bytes.len() > OUTPUT_BUFFER && !out.is_empty() {
            (&self.stream).write_all(&out)?;
            out.clear();
        }
        if bytes.len() >= OUTPUT_BUFFER {
            (&self.stream).write_all(bytes)
        } else {
            out.extend_from_slice(bytes);
            Ok(())
        }
    }
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

fn stream(handle: i32) -> Result<Arc<Conn>, String> {
    match socket(handle)? {
        Socket::Stream(s) => Ok(s),
        _ => Err("not a connected socket".into()),
    }
}

fn fresh(handle: i32) -> Result<Arc<Fresh>, String> {
    match socket(handle)? {
        Socket::Fresh(f) => Ok(f),
        _ => Err("not a socket that is not connected".into()),
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

/// Sends what every connected socket's buffer holds: before a wait on any socket and at
/// the program's end. A failure is skipped; the socket's own next write or flush reports
/// it.
pub fn flush_all() {
    let conns: Vec<Arc<Conn>> = lock(&SOCKETS)
        .1
        .values()
        .filter_map(|s| match s {
            Socket::Stream(c) => Some(Arc::clone(c)),
            _ => None,
        })
        .collect();
    for conn in conns {
        let _ = conn.flush();
    }
}

/// Connects to `host:port` (a name or an address literal) and answers the socket's number.
fn connect(host: &str, port: i32) -> Result<i32, String> {
    let port = port_of(port)?;
    flush_all();
    let stream = TcpStream::connect((host, port)).map_err(|e| reason(&e))?;
    Ok(register(Socket::Stream(Conn::new(stream))))
}

/// The address `host:port` names -- a name is looked up -- the JDK's choice among several:
/// the first IPv4 one, else the first, or with `family` the first of it.
fn address(host: &str, port: u16, family: Option<Domain>) -> Result<SocketAddr, String> {
    let addrs: Vec<SocketAddr> = (host, port).to_socket_addrs().map_err(|e| reason(&e))?.collect();
    let of = |a: &&SocketAddr| family.is_none_or(|f| Domain::for_address(**a) == f);
    addrs
        .iter()
        .filter(of)
        .find(|a| a.is_ipv4())
        .or_else(|| addrs.iter().find(of))
        .copied()
        .ok_or_else(|| format!("no address of {host}"))
}

/// A socket not connected yet, bound to `host:port` (an empty host is every interface),
/// or for a negative port not bound: its number.
fn open(host: &str, port: i32) -> Result<i32, String> {
    if port < 0 {
        return Ok(register(Socket::Fresh(Fresh::new(None))));
    }
    let at = address(if host.is_empty() { "0.0.0.0" } else { host }, port_of(port)?, None)?;
    let raw = socket2::Socket::new(Domain::for_address(at), Type::STREAM, Some(Protocol::TCP))
        .map_err(|e| reason(&e))?;
    raw.bind(&at.into()).map_err(|e| reason(&e))?;
    Ok(register(Socket::Fresh(Fresh::new(Some(raw)))))
}

/// The socket `handle` names, connected: a `Stream` under its number from now on. A number
/// closed meanwhile keeps none.
fn connected(handle: i32, fresh: &Arc<Fresh>) -> Result<(), String> {
    let raw = lock(&fresh.state).raw.take().ok_or("the socket is gone")?;
    raw.set_nonblocking(false).map_err(|e| reason(&e))?;
    let mut table = lock(&SOCKETS);
    if matches!(table.1.get(&handle), Some(Socket::Fresh(f)) if Arc::ptr_eq(f, fresh)) {
        table.1.insert(handle, Socket::Stream(Conn::new(TcpStream::from(raw))));
    }
    Ok(())
}

/// Begins the connect of a socket `open` made to `host:port` (a name or an address
/// literal) with no wait: 1 once connected, 0 while the connect goes on, which `wait`
/// answers for once it has ended and `end` then finishes.
fn begin(handle: i32, host: &str, port: i32) -> Result<i32, String> {
    let fresh = fresh(handle)?;
    let port = port_of(port)?;
    flush_all();
    let mut state = lock(&fresh.state);
    if state.connecting {
        return Err("a connect is under way".into());
    }
    let family = match &state.raw {
        Some(raw) => Some(match raw.local_addr().map_err(|e| reason(&e))?.as_socket() {
            Some(SocketAddr::V6(_)) => Domain::IPV6,
            _ => Domain::IPV4,
        }),
        None => None,
    };
    let to = address(host, port, family)?;
    if state.raw.is_none() {
        state.raw = Some(
            socket2::Socket::new(Domain::for_address(to), Type::STREAM, Some(Protocol::TCP))
                .map_err(|e| reason(&e))?,
        );
    }
    let Some(raw) = state.raw.as_ref() else {
        return Err("the socket is gone".into());
    };
    raw.set_nonblocking(true).map_err(|e| reason(&e))?;
    match raw.connect(&to.into()) {
        Ok(()) => {
            drop(state);
            connected(handle, &fresh)?;
            Ok(1)
        }
        Err(e) if e.raw_os_error() == Some(EINPROGRESS) => {
            state.connecting = true;
            Ok(0)
        }
        Err(e) => Err(reason(&e)),
    }
}

/// Ends a connect `begin` left going on once `wait` answered for its socket: why it
/// failed, or a `Stream` under the number from now on.
fn end(handle: i32) -> Result<(), String> {
    let fresh = fresh(handle)?;
    {
        let mut state = lock(&fresh.state);
        state.connecting = false;
        let Some(raw) = state.raw.as_ref() else {
            return Err("the socket is gone".into());
        };
        if let Some(e) = raw.take_error().map_err(|e| reason(&e))? {
            return Err(reason(&e));
        }
        raw.peer_addr().map_err(|e| reason(&e))?;
    }
    connected(handle, &fresh)
}

/// Binds `host:port` (an empty host is every interface) and answers the listener's number.
fn listen(host: &str, port: i32) -> Result<i32, String> {
    let bind = if host.is_empty() { "0.0.0.0" } else { host };
    let listener = TcpListener::bind((bind, port_of(port)?)).map_err(|e| reason(&e))?;
    Ok(register(Socket::Listener(Arc::new(Listen {
        listener,
        deadline: Mutex::new(None),
    }))))
}

/// Waits for the next connection on a listener, for its accept deadline at most, and
/// answers the socket's number.
fn accept(handle: i32) -> Result<i32, String> {
    let Socket::Listener(listen) = socket(handle)? else {
        return Err("not a listener".into());
    };
    flush_all();
    let deadline = *lock(&listen.deadline);
    if let Some(limit) = deadline {
        let start = std::time::Instant::now();
        let mut fds = [PollFd {
            fd: listen.listener.as_raw_fd(),
            events: POLLIN,
            revents: 0,
        }];
        loop {
            let left = c_int::try_from(limit.saturating_sub(start.elapsed()).as_millis()).unwrap_or(c_int::MAX);
            // SAFETY: fds is a live array of one pollfd struct, which poll reads and whose
            // revents it writes; nothing else is touched.
            let ready = unsafe { poll(fds.as_mut_ptr(), 1, left) };
            if ready > 0 {
                break;
            }
            if ready == 0 {
                return Err("Accept timed out".into());
            }
            let e = std::io::Error::last_os_error();
            if e.kind() != ErrorKind::Interrupted {
                return Err(reason(&e));
            }
        }
    }
    let (stream, _) = listen.listener.accept().map_err(|e| reason(&e))?;
    Ok(register(Socket::Stream(Conn::new(stream))))
}

/// Reads into `buf`: the count, 0 at the peer's close.
fn read(handle: i32, buf: &mut [u8]) -> Result<usize, String> {
    let conn = stream(handle)?;
    flush_all();
    loop {
        match (&conn.stream).read(buf) {
            Ok(n) => return Ok(n),
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => return Err(io_reason(&e)),
        }
    }
}

/// `FIONREAD`, the bytes waiting in a socket's receive buffer: `asm-generic/ioctls.h`
/// (every Linux architecture the runner is built for) and `sys/filio.h` (macOS).
#[cfg(target_os = "linux")]
const FIONREAD: c_ulong = 0x541B;
#[cfg(target_os = "macos")]
const FIONREAD: c_ulong = 0x4004_667F;

unsafe extern "C" {
    fn ioctl(fd: c_int, request: c_ulong, ...) -> c_int;
}

/// Whether bytes wait in the receive buffer, which a read would hand over without
/// blocking: `listen`'s question, asked as the JDK's `available()` asks it (`FIONREAD`),
/// so nothing is consumed, a pending error included. False at the peer's close.
fn readable(handle: i32) -> Result<bool, String> {
    let conn = stream(handle)?;
    flush_all();
    let mut waiting: c_int = 0;
    // SAFETY: FIONREAD writes one int through the pointer and touches nothing else.
    if unsafe { ioctl(conn.stream.as_raw_fd(), FIONREAD, &raw mut waiting) } < 0 {
        return Err(reason(&std::io::Error::last_os_error()));
    }
    Ok(waiting > 0)
}

/// `struct pollfd` of `poll.h`, the same on Linux and macOS.
#[repr(C)]
struct PollFd {
    fd: c_int,
    events: c_short,
    revents: c_short,
}

/// `POLLIN`: data to read, or for a listener a connection to accept (Linux and macOS).
const POLLIN: c_short = 0x0001;

/// `POLLOUT`: room to write, which a socket whose connect has ended has (Linux and macOS).
const POLLOUT: c_short = 0x0004;

/// `nfds_t`: `unsigned long` on Linux, `unsigned int` on macOS.
#[cfg(target_os = "linux")]
type Nfds = c_ulong;
#[cfg(target_os = "macos")]
type Nfds = std::ffi::c_uint;

unsafe extern "C" {
    fn poll(fds: *mut PollFd, nfds: Nfds, timeout: c_int) -> c_int;
}

/// What a wait on sockets can end at besides them: a server's next request
/// (`http/serve.rs`), rung by another thread of the runner's.
pub(crate) trait Bell {
    /// A descriptor that turns readable when the bell may have rung.
    fn fd(&self) -> RawFd;

    /// Whether what the bell rings for is there now; what the descriptor holds is taken
    /// first, so a ring after this answer leaves it readable again.
    fn rung(&self) -> bool;
}

/// Waits until one of the sockets has something for a read or an accept -- bytes, the
/// peer's close, a connection, a failure -- or its connect has ended (it can be written
/// to), or the bell has rung, or `millis` have passed (negative: no limit), and answers
/// the index of a socket that has, the count of the sockets for the bell (a socket
/// first), or -1 once the time has passed. A number that is no open socket, or one not
/// connected with no connect under way, is one that has, at once: its waiter finds out
/// why. What the Java subset's scheduler waits in where no thread of the program can go on
/// and threads wait for sockets, its server's next request with them: one wait covers them
/// all.
pub(crate) fn wait(handles: &[i32], bell: Option<&dyn Bell>, millis: i32) -> Result<i32, String> {
    flush_all();
    let rang = i32::try_from(handles.len()).unwrap_or(i32::MAX);
    let mut fds = Vec::with_capacity(handles.len() + 1);
    for (i, &handle) in handles.iter().enumerate() {
        let (fd, events) = match socket(handle) {
            Ok(Socket::Stream(conn)) => (conn.stream.as_raw_fd(), POLLIN),
            Ok(Socket::Listener(listen)) => (listen.listener.as_raw_fd(), POLLIN),
            Ok(Socket::Fresh(fresh)) => match &*lock(&fresh.state) {
                FreshState {
                    raw: Some(raw),
                    connecting: true,
                } => (raw.as_raw_fd(), POLLOUT),
                _ => return Ok(i32::try_from(i).unwrap_or(i32::MAX)),
            },
            Err(_) => return Ok(i32::try_from(i).unwrap_or(i32::MAX)),
        };
        fds.push(PollFd {
            fd,
            events,
            revents: 0,
        });
    }
    // a bell rung already is answered unless a socket has something now
    let mut there = false;
    if let Some(bell) = bell {
        there = bell.rung();
        fds.push(PollFd {
            fd: bell.fd(),
            events: POLLIN,
            revents: 0,
        });
    }
    let deadline = u64::try_from(millis)
        .ok()
        .map(|ms| std::time::Instant::now() + Duration::from_millis(ms));
    loop {
        let timeout = match deadline {
            _ if there => 0,
            None => -1,
            Some(at) => c_int::try_from(at.saturating_duration_since(std::time::Instant::now()).as_millis())
                .unwrap_or(c_int::MAX),
        };
        let count = fds.len() as Nfds;
        // SAFETY: fds is a live array of `count` pollfd structs, which poll reads and
        // whose revents it writes; nothing else is touched.
        let ready = unsafe { poll(fds.as_mut_ptr(), count, timeout) };
        if ready < 0 {
            let e = std::io::Error::last_os_error();
            if e.kind() == ErrorKind::Interrupted {
                continue;
            }
            return Err(reason(&e));
        }
        if let Some(i) = fds[..handles.len()].iter().position(|fd| fd.revents != 0) {
            return Ok(i32::try_from(i).unwrap_or(i32::MAX));
        }
        match bell {
            _ if there => return Ok(rang),
            // a ring whose request an earlier answer took already: the wait goes on
            Some(bell) if ready > 0 => there = bell.rung(),
            _ => return Ok(-1),
        }
    }
}

fn write(handle: i32, bytes: &[u8]) -> Result<(), String> {
    stream(handle)?.write(bytes).map_err(|e| io_reason(&e))
}

/// Sends what the socket's buffer holds: `force-output`/`finish-output`.
fn flush(handle: i32) -> Result<(), String> {
    stream(handle)?.flush().map_err(|e| io_reason(&e))
}

/// Ends half of a connected socket: its output after what its buffer holds has gone out
/// (the peer reads its end), or its input (a read under way answers the end).
fn shutdown(handle: i32, output: bool) -> Result<(), String> {
    let conn = stream(handle)?;
    if output {
        conn.flush().map_err(|e| io_reason(&e))?;
    }
    conn.stream
        .shutdown(if output {
            std::net::Shutdown::Write
        } else {
            std::net::Shutdown::Read
        })
        .map_err(|e| reason(&e))
}

/// Gives an option of a connected socket to the host: 0 `TCP_NODELAY` and 1
/// `SO_KEEPALIVE` (1 on, 0 off), 2 `SO_LINGER` (seconds, -1 off), 3 `SO_OOBINLINE` (1 on,
/// 0 off), 4 `IP_TOS`; or, for 5, sends the byte `value` as urgent data after what the
/// buffer holds.
fn option(handle: i32, option: i32, value: i32) -> Result<(), String> {
    let conn = stream(handle)?;
    let raw = socket2::SockRef::from(&conn.stream);
    match option {
        0 => raw.set_tcp_nodelay(value != 0),
        1 => raw.set_keepalive(value != 0),
        2 => raw.set_linger((value >= 0).then(|| Duration::from_secs(value as u64))),
        3 => raw.set_out_of_band_inline(value != 0),
        4 => raw.set_tos_v4(value as u32),
        5 => {
            conn.flush().map_err(|e| io_reason(&e))?;
            raw.send_out_of_band(&[value as u8]).map(|_| ())
        }
        _ => return Err(format!("no option {option}")),
    }
    .map_err(|e| reason(&e))
}

/// Closes the socket after sending what its buffer holds (a failure there -- the peer
/// reset the connection -- does not stop the close): the last handle on it is dropped
/// here, which sends the FIN.
fn close(handle: i32) {
    if let Some(Socket::Stream(conn)) = lock(&SOCKETS).1.remove(&handle) {
        let _ = conn.flush();
    }
}

fn local_addr(handle: i32) -> Result<SocketAddr, String> {
    match socket(handle)? {
        Socket::Stream(c) => c.stream.local_addr(),
        Socket::Listener(l) => l.listener.local_addr(),
        Socket::Fresh(f) => {
            let state = lock(&f.state);
            let Some(raw) = state.raw.as_ref() else {
                return Err("not bound".into());
            };
            let at = raw.local_addr().map_err(|e| reason(&e))?;
            return at.as_socket().ok_or_else(|| "not an internet address".into());
        }
    }
    .map_err(|e| reason(&e))
}

fn peer_addr(handle: i32) -> Result<SocketAddr, String> {
    stream(handle)?.stream.peer_addr().map_err(|e| reason(&e))
}

/// The read deadline of a connected socket, or the accept deadline of a listener, in
/// milliseconds; 0 clears it.
fn set_timeout(handle: i32, millis: i32) -> Result<(), String> {
    let deadline = u64::try_from(millis)
        .ok()
        .filter(|&ms| ms > 0)
        .map(Duration::from_millis);
    match socket(handle)? {
        Socket::Stream(conn) => conn.stream.set_read_timeout(deadline).map_err(|e| reason(&e)),
        Socket::Listener(listen) => {
            *lock(&listen.deadline) = deadline;
            Ok(())
        }
        Socket::Fresh(_) => Err("not a connected socket".into()),
    }
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
    // socket(host, port) -> a socket not connected yet, bound to host:port (an empty host
    // is every interface) unless the port is -1, or -1.
    linker.func_wrap(
        MODULE,
        "socket",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32, port: i32| -> wasmtime::Result<i32> {
            let host = memory_string(&mut caller, p, n)?;
            Ok(answer(open(&host, port)))
        },
    )?;
    // begin(socket, host, port) -> 1 once connected, 0 while the connect goes on (wait
    // answers for the socket once it has ended, and end finishes it), or -1.
    linker.func_wrap(
        MODULE,
        "begin",
        |mut caller: Caller<'_, WasiP1Ctx>, handle: i32, p: i32, n: i32, port: i32| -> wasmtime::Result<i32> {
            let host = memory_string(&mut caller, p, n)?;
            Ok(answer(begin(handle, &host, port)))
        },
    )?;
    // end(socket) -> 0 once the connect begin left going on has succeeded, or -1.
    linker.func_wrap(MODULE, "end", |handle: i32| -> i32 { answer(end(handle).map(|()| 0)) })?;
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
    // write(socket, octets) -> 0, or -1: into the socket's buffer, or, when it is full,
    // blocks until the kernel has taken the bytes.
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
    // flush(socket) -> 0, or -1: blocks until the kernel has taken the buffered bytes.
    linker.func_wrap(MODULE, "flush", |handle: i32| -> i32 {
        answer(flush(handle).map(|()| 0))
    })?;
    // shutdown(socket, output) -> 0, or -1: ends the output (1), after what is buffered,
    // or the input (0).
    linker.func_wrap(MODULE, "shutdown", |handle: i32, output: i32| -> i32 {
        answer(shutdown(handle, output != 0).map(|()| 0))
    })?;
    // option(socket, option, value) -> 0, or -1: an option of a connected socket given to
    // the host, or a byte of urgent data sent after what is buffered.
    linker.func_wrap(MODULE, "option", |handle: i32, which: i32, value: i32| -> i32 {
        answer(option(handle, which, value).map(|()| 0))
    })?;
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
    // setTimeout(socket, millis) -> 0, or -1; 0 ms clears the read deadline, or a
    // listener's accept deadline.
    linker.func_wrap(MODULE, "setTimeout", |handle: i32, millis: i32| -> i32 {
        answer(set_timeout(handle, millis).map(|()| 0))
    })?;
    // readable(socket) -> 1 when bytes wait to be read, 0 when none do (the peer's close
    // included), -1 on a failure: never blocks.
    linker.func_wrap(MODULE, "readable", |handle: i32| -> i32 {
        answer(readable(handle).map(i32::from))
    })?;
    // wait(sockets, server, millis) -> the index of a socket that has something for a
    // read or an accept, the count of the sockets once the server's next request has
    // arrived (which its nextRequest answers; -1: no server), or -1 once millis have
    // passed (negative: no limit) or on a failure: the sockets are numbers, four octets
    // each, the least significant first.
    linker.func_wrap(
        MODULE,
        "wait",
        |mut caller: Caller<'_, WasiP1Ctx>, ptr: i32, len: i32, server: i32, millis: i32| -> wasmtime::Result<i32> {
            let Some(Extern::Memory(memory)) = caller.get_export("memory") else {
                wasmtime::bail!("the module exports no memory");
            };
            let (start, end) = (ptr as u32 as usize, ptr as u32 as usize + len as u32 as usize);
            let Some(bytes) = memory.data(&caller).get(start..end) else {
                wasmtime::bail!("the sockets to wait on lie outside linear memory");
            };
            let handles: Vec<i32> = bytes
                .chunks_exact(4)
                .map(|four| i32::from_le_bytes([four[0], four[1], four[2], four[3]]))
                .collect();
            let bell = if server < 0 { None } else { Some(crate::http::bell_of(server)?) };
            Ok(answer(wait(&handles, bell.as_deref(), millis)))
        },
    )?;
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

    /// Polls `readable` until it answers yes, as a program polling `listen` does.
    fn wait_readable(handle: i32) {
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        while !readable(handle).unwrap() {
            assert!(std::time::Instant::now() < deadline, "the bytes never arrived");
            std::thread::sleep(Duration::from_millis(1));
        }
    }

    /// The connect of a socket made before it: `begin`, then once `wait` answers for the
    /// socket, `end`.
    fn connect_made(socket: i32, host: &str, port: i32) -> Result<(), String> {
        if begin(socket, host, port)? == 0 {
            assert_eq!(wait(&[socket], None, 10_000).unwrap(), 0, "the connect ended");
            end(socket)?;
        }
        Ok(())
    }

    /// A loopback exchange through the host functions as the module drives them, with
    /// no module: listen, a socket bound before its connect and connected from there by
    /// name, accept, both directions, the output buffer a wait sends, what `readable`
    /// answers before, while and after bytes wait, the addresses, the deadline, half the
    /// connection ended and the close.
    #[test]
    fn a_loopback_connection_carries_bytes_both_ways_and_ends_at_the_close() {
        let listener = listen("127.0.0.1", 0).unwrap();
        let port = local_addr(listener).unwrap().port();
        assert_eq!(local_addr(listener).unwrap().ip().to_string(), "127.0.0.1");
        assert!(peer_addr(listener).is_err(), "a listener has no peer");
        let client = open("127.0.0.1", 0).unwrap();
        let bound = local_addr(client).unwrap();
        assert!(bound.port() > 0, "bound to a port of the host's choosing");
        assert!(peer_addr(client).is_err() && read(client, &mut [0u8; 1]).is_err(), "not connected");
        connect_made(client, "localhost", i32::from(port)).unwrap();
        assert_eq!(local_addr(client).unwrap(), bound, "connected from where it was bound");
        let server = accept(listener).unwrap();
        assert_eq!(peer_addr(server).unwrap().port(), local_addr(client).unwrap().port());
        assert!(!readable(server).unwrap(), "nothing has been written yet");
        write(client, b"hello\n").unwrap();
        write(client, b"\xff").unwrap();
        assert_eq!(
            lock(&stream(client).unwrap().out).len(),
            7,
            "the writes wait in the buffer"
        );
        wait_readable(server);
        assert!(lock(&stream(client).unwrap().out).is_empty(), "a wait sent them");
        let mut buf = [0u8; 16];
        let mut got = Vec::new();
        while got.len() < 7 {
            let n = read(server, &mut buf).unwrap();
            got.extend_from_slice(&buf[..n]);
        }
        assert_eq!(got, b"hello\n\xff");
        assert!(!readable(server).unwrap(), "every byte has been read");
        write(server, b"back").unwrap();
        let n = read(client, &mut buf).unwrap();
        assert_eq!(&buf[..n], b"back");
        set_timeout(client, 50).unwrap();
        assert_eq!(read(client, &mut buf).unwrap_err(), "Read timed out");
        set_timeout(client, 0).unwrap();
        // The options reach the host's socket, and urgent data goes after what was
        // buffered: inline at a peer that takes it so.
        for (which, value) in [(0, 1), (1, 1), (2, 7), (3, 1), (4, 0x20)] {
            option(client, which, value).unwrap();
        }
        let conn = stream(client).unwrap();
        let raw = socket2::SockRef::from(&conn.stream);
        assert!(raw.tcp_nodelay().unwrap() && raw.keepalive().unwrap() && raw.out_of_band_inline().unwrap());
        assert_eq!(raw.linger().unwrap(), Some(Duration::from_secs(7)));
        assert_eq!(raw.tos_v4().unwrap(), 0x20);
        option(client, 2, -1).unwrap();
        assert_eq!(raw.linger().unwrap(), None, "-1 is off");
        option(server, 3, 1).unwrap();
        write(client, b"x").unwrap();
        option(client, 5, i32::from(b'U')).unwrap();
        let mut got = Vec::new();
        while got.len() < 2 {
            let n = read(server, &mut buf).unwrap();
            got.extend_from_slice(&buf[..n]);
        }
        assert_eq!(got, b"xU");
        assert_eq!(option(listener, 0, 1).unwrap_err(), "not a connected socket");
        // Half a connection: the output ended after what was buffered, the peer reading
        // its end, and the other direction still carrying bytes.
        write(server, b"last").unwrap();
        shutdown(server, true).unwrap();
        let mut got = Vec::new();
        loop {
            let n = read(client, &mut buf).unwrap();
            if n == 0 {
                break;
            }
            got.extend_from_slice(&buf[..n]);
        }
        assert_eq!(got, b"last", "what was buffered, then the end");
        write(client, b"on").unwrap();
        flush(client).unwrap();
        wait_readable(server);
        assert_eq!(read(server, &mut buf).unwrap(), 2, "the other half still carries bytes");
        close(server);
        assert!(!readable(client).unwrap(), "the peer's close is no byte to read");
        assert_eq!(read(client, &mut buf).unwrap(), 0, "the peer's close is end of stream");
        assert!(readable(listener).is_err(), "a listener reads nothing");
        close(client);
        close(listener);
        assert_eq!(read(client, &mut buf).unwrap_err(), "not an open socket");
        assert_eq!(readable(client).unwrap_err(), "not an open socket");
        assert!(accept(listener).is_err());
    }

    /// A bell rung by another thread: `rung` holds while it has something.
    struct TestBell {
        heard: std::os::unix::net::UnixStream,
        ring: std::os::unix::net::UnixStream,
        there: std::sync::atomic::AtomicBool,
    }

    impl Bell for TestBell {
        fn fd(&self) -> RawFd {
            self.heard.as_raw_fd()
        }

        fn rung(&self) -> bool {
            let mut rings = [0u8; 8];
            while matches!((&self.heard).read(&mut rings), Ok(n) if n > 0) {}
            self.there.load(std::sync::atomic::Ordering::SeqCst)
        }
    }

    /// What `wait` answers: -1 while nothing has come within the time (and an accept past
    /// the listener's deadline fails), the index of a listener a connection waits on, of a stream bytes or the peer's close have come
    /// to, of a number that is no open socket; and a buffered write goes out first. A
    /// bell is the index past the sockets once it has something, at once or rung during
    /// a wait with no limit; a ring for nothing does not end the wait.
    #[test]
    fn a_wait_answers_the_first_socket_that_has_something() {
        let listener = listen("127.0.0.1", 0).unwrap();
        let port = i32::from(local_addr(listener).unwrap().port());
        assert_eq!(wait(&[listener], None, 0).unwrap(), -1, "no connection yet");
        set_timeout(listener, 30).unwrap();
        assert_eq!(accept(listener).unwrap_err(), "Accept timed out", "the accept deadline");
        set_timeout(listener, 0).unwrap();
        let client = connect("127.0.0.1", port).unwrap();
        assert_eq!(wait(&[listener], None, 10_000).unwrap(), 0, "a connection waits");
        let server = accept(listener).unwrap();
        assert_eq!(wait(&[listener, server], None, 50).unwrap(), -1, "the time passed");
        write(client, b"x").unwrap();
        assert_eq!(wait(&[listener, server], None, 10_000).unwrap(), 1, "the wait sent the byte");
        let mut buf = [0u8; 4];
        assert_eq!(read(server, &mut buf).unwrap(), 1);

        let (heard, ring) = std::os::unix::net::UnixStream::pair().unwrap();
        heard.set_nonblocking(true).unwrap();
        let bell = Arc::new(TestBell {
            heard,
            ring,
            there: std::sync::atomic::AtomicBool::new(false),
        });
        (&bell.ring).write_all(&[1]).unwrap();
        assert_eq!(wait(&[server], Some(&*bell), 50).unwrap(), -1, "a ring for nothing");
        let ringer = {
            let bell = Arc::clone(&bell);
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(50));
                bell.there.store(true, std::sync::atomic::Ordering::SeqCst);
                (&bell.ring).write_all(&[1]).unwrap();
            })
        };
        assert_eq!(wait(&[server], Some(&*bell), -1).unwrap(), 1, "rung while waiting");
        ringer.join().unwrap();
        assert_eq!(wait(&[], Some(&*bell), 0).unwrap(), 0, "there at once");
        // The byte is at the socket before the wait: a bell rung already ends a wait at
        // once when nothing else is ready, and a lone byte stays in the sender until the
        // peer's delayed ACK, which macOS's loopback sends late.
        write(client, b"y").unwrap();
        flush(client).unwrap();
        wait_readable(server);
        assert_eq!(wait(&[server], Some(&*bell), 10_000).unwrap(), 0, "a socket first");
        assert_eq!(read(server, &mut buf).unwrap(), 1);

        close(client);
        assert_eq!(wait(&[server], None, 10_000).unwrap(), 0, "the peer's close");
        assert_eq!(wait(&[server, client], None, 0).unwrap(), 1, "no open socket");
        close(server);
        close(listener);

        // A connect under way: a listener whose queue is full holds it until the time has
        // passed, and its close ends the wait at once; a socket with no connect under way
        // has nothing to wait for.
        let full = socket2::Socket::new(Domain::IPV4, Type::STREAM, None).unwrap();
        full.bind(&SocketAddr::from(([127, 0, 0, 1], 0)).into()).unwrap();
        full.listen(1).unwrap();
        let at = full.local_addr().unwrap().as_socket().unwrap();
        let mut queued = Vec::new();
        while let Ok(s) = TcpStream::connect_timeout(&at, Duration::from_millis(300)) {
            queued.push(s);
            assert!(queued.len() < 64, "the queue never filled");
        }
        let held = open("", -1).unwrap();
        assert_eq!(wait(&[held], None, 0).unwrap(), 0, "no connect under way");
        assert_eq!(begin(held, "127.0.0.1", i32::from(at.port())).unwrap(), 0);
        assert_eq!(wait(&[held], None, 50).unwrap(), -1, "the connect goes on");
        close(held);
        assert_eq!(wait(&[held], None, -1).unwrap(), 0, "closed under its connect");
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
        let made = open("", -1).unwrap();
        assert_eq!(connect_made(made, "127.0.0.1", 1).unwrap_err(), refused, "refused at begin or at end");
        close(made);
        let taken = open("127.0.0.1", port).unwrap_err();
        assert!(!taken.is_empty() && !taken.contains("os error"), "{taken}");
        assert_eq!(set_timeout(-5, 1).unwrap_err(), "not an open socket");
    }
}
