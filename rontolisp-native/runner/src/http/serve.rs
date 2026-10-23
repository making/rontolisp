//! The HTTP server of a `--native` output: the `rlhttp` imports the `--native` leg of
//! `http-serve.lisp` is written over, which is what `clack:clackup :server :rontolisp`
//! and `ring.adapter.rontolisp/run-server` reach (`.kb/http-server.md`, "--native").
//!
//! The runner owns the sockets and the protocol; the module owns the application. `serve`
//! binds and starts accepting on threads of the runner's own: a thread per connection
//! reads each request -- the head, then the whole body, `Content-Length` or chunked --
//! and queues it. The module takes the requests one at a time (`nextRequest`, a handle
//! whose host data owns the exchange), reads the head as the host-driven reactor's JSON
//! envelope (`http-reactor.lisp`: `method`, `target`, `headers`, `scheme`, and the
//! `protocol`, address and port keys a socket can fill), pulls the body
//! (`readRequestBody`), hands the response body over (`writeResponseBody`) and answers
//! the response head (`respond`), which is when the connection's thread writes the
//! response. So one slow or idle connection never holds the others up, and the
//! application runs on the module's one thread, as WASM has no other.
//!
//! The response goes out whole, with a `Content-Length`: the reactor contract hands the
//! body over BEFORE the head, and a head whose `"body"` key is present replaces whatever
//! crossed already (a handler that failed mid-body answers its 500 that way). Connections
//! are kept alive as HTTP/1.1 says (an HTTP/1.0 client asks with `Connection:
//! keep-alive`); one idle for [`IDLE`] is closed.

use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use wasmtime::{Caller, ExternRef, Linker, Rooted};
use wasmtime_wasi::p1::WasiP1Ctx;

use super::{MODULE, json, memory_string, return_string};

/// How long a kept-alive connection may wait for its next request.
const IDLE: Duration = Duration::from_secs(60);

/// The largest request head read; a longer one is answered 431.
const MAX_HEAD: usize = 64 * 1024;

/// The servers `serve` started, by the number it answered.
static SERVERS: Mutex<Vec<Arc<Server>>> = Mutex::new(Vec::new());

/// Why the last `serve` that answered -1 failed.
static ERROR: Mutex<String> = Mutex::new(String::new());

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

struct Server {
    requests: Mutex<Receiver<Exchange>>,
    /// The port bound, which `serve` was asked for unless that was 0.
    #[cfg_attr(not(test), allow(dead_code))]
    port: u16,
}

/// One request, from the connection's thread to the module and its response back.
struct Exchange {
    head: String,
    body: Vec<u8>,
    pos: usize,
    /// The response body as the module handed it over.
    out: Vec<u8>,
    head_request: bool,
    keep_alive: bool,
    /// Where the response goes: the connection's thread, waiting. `None` once answered.
    reply: Option<Sender<Vec<u8>>>,
}

/// The host data of the module's handle on an exchange. Dropped unanswered -- the module
/// trapped, or let the handle go -- it answers 500, so no client waits for ever.
struct Handle(Mutex<Exchange>);

impl Drop for Handle {
    fn drop(&mut self) {
        let mut ex = lock(&self.0);
        if let Some(reply) = ex.reply.take() {
            let head = ex.head_request;
            let _ = reply.send(response(500, &[], b"the application answered nothing\n", head, false));
        }
    }
}

/// An I/O error as the JDK words one: the OS's text, without Rust's `(os error N)`.
pub(crate) fn reason(e: &io::Error) -> String {
    let text = e.to_string();
    match text.rfind(" (os error ") {
        Some(at) if text.ends_with(')') => text[..at].to_owned(),
        _ => text,
    }
}

/// Binds `host:port` (an empty host is every interface) and starts accepting.
fn serve(host: &str, port: i32) -> Result<i32, String> {
    let bind = if host.is_empty() { "0.0.0.0" } else { host };
    let shown = if host.is_empty() { "*" } else { host };
    let listener = u16::try_from(port)
        .map_err(|_| format!("port {port} is out of range"))
        .and_then(|p| TcpListener::bind((bind, p)).map_err(|e| reason(&e)))
        .map_err(|e| format!("cannot listen on {shown}:{port}: {e}"))?;
    let bound = listener.local_addr().map(|a| a.port()).unwrap_or(0);
    let (queue, requests) = mpsc::channel();
    std::thread::Builder::new()
        .name("rlhttp-accept".into())
        .spawn(move || accept(listener, queue))
        .map_err(|e| format!("cannot start the server: {e}"))?;
    let mut servers = lock(&SERVERS);
    servers.push(Arc::new(Server {
        requests: Mutex::new(requests),
        port: bound,
    }));
    Ok(servers.len() as i32 - 1)
}

fn accept(listener: TcpListener, queue: Sender<Exchange>) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let queue = queue.clone();
        let _ = std::thread::Builder::new()
            .name("rlhttp-conn".into())
            .spawn(move || connection(stream, &queue));
    }
}

/// Serves one connection: each request queued for the module, each response written.
fn connection(stream: TcpStream, queue: &Sender<Exchange>) {
    let (Ok(local), Ok(peer)) = (stream.local_addr(), stream.peer_addr()) else {
        return;
    };
    let _ = stream.set_read_timeout(Some(IDLE));
    let _ = stream.set_nodelay(true);
    let Ok(clone) = stream.try_clone() else { return };
    let mut reader = BufReader::new(clone);
    let mut writer = stream;
    loop {
        let request = match read_request(&mut reader, &mut writer) {
            Ok(Some(r)) => r,
            Ok(None) => return,
            Err(Refusal::Status(status, why)) => {
                let _ = writer.write_all(&response(status, &[], why.as_bytes(), false, false));
                return;
            }
            Err(Refusal::Io) => return,
        };
        let (reply, answer) = mpsc::channel();
        let keep_alive = request.keep_alive;
        let exchange = Exchange {
            head: head_json(&request, local, peer),
            body: request.body,
            pos: 0,
            out: Vec::new(),
            head_request: request.method == "HEAD",
            keep_alive,
            reply: Some(reply),
        };
        if queue.send(exchange).is_err() {
            return;
        }
        let Ok(bytes) = answer.recv() else { return };
        if writer.write_all(&bytes).and_then(|()| writer.flush()).is_err() || !keep_alive {
            return;
        }
    }
}

struct Request {
    method: String,
    target: String,
    protocol: String,
    /// Lower-cased names in arrival order, values as ISO-8859-1 (what the JDK server
    /// decodes them as).
    headers: Vec<(String, String)>,
    body: Vec<u8>,
    keep_alive: bool,
}

enum Refusal {
    /// Answer this status, then close.
    Status(u16, String),
    /// The connection ended or failed: close.
    Io,
}

impl From<io::Error> for Refusal {
    fn from(_: io::Error) -> Refusal {
        Refusal::Io
    }
}

fn latin1(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| b as char).collect()
}

/// The next request on the connection; `None` when the client closed it between requests.
fn read_request(reader: &mut BufReader<TcpStream>, writer: &mut TcpStream) -> Result<Option<Request>, Refusal> {
    let mut head = Vec::new();
    loop {
        let start = head.len();
        if reader
            .by_ref()
            .take((MAX_HEAD + 1 - start) as u64)
            .read_until(b'\n', &mut head)?
            == 0
        {
            return if head.is_empty() { Ok(None) } else { Err(Refusal::Io) };
        }
        if head.len() > MAX_HEAD {
            return Err(Refusal::Status(431, "request head too large\n".into()));
        }
        let line = &head[start..];
        if line == b"\r\n" || line == b"\n" {
            if start == 0 {
                // An empty line before the request line (RFC 9112, section 2.2).
                head.clear();
                continue;
            }
            break;
        }
    }
    let mut slots = [httparse::EMPTY_HEADER; 128];
    let mut parsed = httparse::Request::new(&mut slots);
    match parsed.parse(&head) {
        Ok(httparse::Status::Complete(_)) => {}
        Err(httparse::Error::TooManyHeaders) => return Err(Refusal::Status(431, "too many header fields\n".into())),
        _ => return Err(Refusal::Status(400, "malformed request\n".into())),
    }
    let method = parsed.method.unwrap_or("GET").to_owned();
    let target = parsed.path.unwrap_or("/").to_owned();
    let protocol = format!("HTTP/1.{}", parsed.version.unwrap_or(1));
    let mut headers: Vec<(String, String)> = parsed
        .headers
        .iter()
        .map(|h| (h.name.to_ascii_lowercase(), latin1(h.value)))
        .collect();
    let field = |name: &str| -> Vec<String> {
        headers
            .iter()
            .filter(|(n, _)| n == name)
            .map(|(_, v)| v.to_ascii_lowercase())
            .collect()
    };
    let connection = field("connection").join(",");
    let tokens: Vec<&str> = connection.split(',').map(str::trim).collect();
    let keep_alive = if protocol == "HTTP/1.0" {
        tokens.contains(&"keep-alive")
    } else {
        !tokens.contains(&"close")
    };
    let chunked = field("transfer-encoding")
        .last()
        .is_some_and(|te| te.rsplit(',').next().unwrap_or("").trim() == "chunked");
    let length = match field("content-length").last() {
        Some(v) => Some(
            v.trim()
                .parse::<usize>()
                .map_err(|_| Refusal::Status(400, "invalid content-length\n".into()))?,
        ),
        None => None,
    };
    if (chunked || length.is_some_and(|n| n > 0)) && field("expect").iter().any(|e| e == "100-continue") {
        writer.write_all(b"HTTP/1.1 100 Continue\r\n\r\n")?;
    }
    let body = if chunked {
        let body = read_chunked(reader)?;
        // The application sees the length it can read, as the reactor contract asks: a
        // chunked request carries none.
        headers.retain(|(n, _)| n != "transfer-encoding" && n != "content-length");
        headers.push(("content-length".into(), body.len().to_string()));
        body
    } else {
        let mut body = vec![0; length.unwrap_or(0)];
        reader.read_exact(&mut body)?;
        body
    };
    Ok(Some(Request {
        method,
        target,
        protocol,
        headers,
        body,
        keep_alive,
    }))
}

/// A chunked body (RFC 9112, section 7.1), its trailer fields read and dropped.
fn read_chunked(reader: &mut BufReader<TcpStream>) -> Result<Vec<u8>, Refusal> {
    let mut body = Vec::new();
    let bad = || Refusal::Status(400, "malformed chunked body\n".into());
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 {
            return Err(Refusal::Io);
        }
        let size = line.split(';').next().unwrap_or("").trim();
        let size = usize::from_str_radix(size, 16).map_err(|_| bad())?;
        if size == 0 {
            loop {
                line.clear();
                if reader.read_line(&mut line)? == 0 || line.trim().is_empty() {
                    return Ok(body);
                }
            }
        }
        let at = body.len();
        body.resize(at + size, 0);
        reader.read_exact(&mut body[at..])?;
        let mut crlf = [0; 2];
        reader.read_exact(&mut crlf)?;
        if &crlf != b"\r\n" {
            return Err(bad());
        }
    }
}

/// The request head as the reactor envelope the module parses.
fn head_json(req: &Request, local: SocketAddr, peer: SocketAddr) -> String {
    let mut out = String::from("{\"method\":");
    json::write_string(&mut out, &req.method);
    out.push_str(",\"target\":");
    json::write_string(&mut out, &req.target);
    out.push_str(",\"protocol\":");
    json::write_string(&mut out, &req.protocol);
    out.push_str(",\"scheme\":\"http\",\"remote-addr\":");
    json::write_string(&mut out, &peer.ip().to_string());
    out.push_str(&format!(",\"remote-port\":{},\"local-addr\":", peer.port()));
    json::write_string(&mut out, &local.ip().to_string());
    out.push_str(&format!(",\"local-port\":{},\"headers\":{{", local.port()));
    // A repeated field is one member, its values joined as the JDK server joins them.
    let mut names: Vec<&str> = Vec::new();
    for (name, _) in &req.headers {
        if !names.contains(&name.as_str()) {
            names.push(name);
        }
    }
    for (i, name) in names.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json::write_string(&mut out, name);
        out.push(':');
        let values: Vec<&str> = req
            .headers
            .iter()
            .filter(|(n, _)| n == name)
            .map(|(_, v)| v.as_str())
            .collect();
        json::write_string(&mut out, &values.join(", "));
    }
    out.push_str("}}");
    out
}

fn reason_phrase(status: u16) -> &'static str {
    match status {
        100 => "Continue",
        200 => "OK",
        201 => "Created",
        202 => "Accepted",
        204 => "No Content",
        206 => "Partial Content",
        301 => "Moved Permanently",
        302 => "Found",
        303 => "See Other",
        304 => "Not Modified",
        307 => "Temporary Redirect",
        308 => "Permanent Redirect",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        413 => "Content Too Large",
        415 => "Unsupported Media Type",
        422 => "Unprocessable Content",
        429 => "Too Many Requests",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        501 => "Not Implemented",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        _ => "",
    }
}

/// A whole response: the status line, the fields, the framing and the body (none for a
/// HEAD request, nor for a status that carries none).
fn response(status: u16, headers: &[(String, String)], body: &[u8], head_request: bool, keep_alive: bool) -> Vec<u8> {
    let mut out = format!("HTTP/1.1 {status} {}\r\n", reason_phrase(status));
    for (name, value) in headers {
        // The transport frames the body; a field that would contradict it is dropped.
        if name.eq_ignore_ascii_case("content-length")
            || name.eq_ignore_ascii_case("transfer-encoding")
            || name.eq_ignore_ascii_case("connection")
        {
            continue;
        }
        out.push_str(&format!("{name}: {value}\r\n"));
    }
    let bodiless = (100..200).contains(&status) || status == 204 || status == 304;
    if !bodiless {
        out.push_str(&format!("content-length: {}\r\n", body.len()));
    }
    if !keep_alive {
        out.push_str("connection: close\r\n");
    }
    out.push_str("\r\n");
    // Field values are ISO-8859-1 on the wire, as the JDK server writes them.
    let mut bytes: Vec<u8> = out
        .chars()
        .map(|c| if (c as u32) < 256 { c as u32 as u8 } else { b'?' })
        .collect();
    if !bodiless && !head_request {
        bytes.extend_from_slice(body);
    }
    bytes
}

/// The module's response head (the reactor envelope's `out` shape) as the response.
fn respond(ex: &mut Exchange, head: &str) -> Result<(), String> {
    let v = json::parse(head)?;
    let status = match v.get("status") {
        Some(json::Value::Number(n)) if (100.0..1000.0).contains(n) => *n as u16,
        _ => return Err("the response head has no status".into()),
    };
    let mut headers = Vec::new();
    if let Some(json::Value::Array(pairs)) = v.get("headers") {
        for pair in pairs {
            if let json::Value::Array(nv) = pair
                && let [json::Value::String(n), json::Value::String(value)] = nv.as_slice()
            {
                headers.push((n.clone(), value.clone()));
            }
        }
    }
    // An in-band body wins over what crossed already: that is how a handler that failed
    // mid-body answers one clean 500.
    let body = match v.get("body").and_then(json::Value::as_str) {
        Some(text) => text.as_bytes().to_vec(),
        None => std::mem::take(&mut ex.out),
    };
    let bytes = response(status, &headers, &body, ex.head_request, ex.keep_alive);
    if let Some(reply) = ex.reply.take() {
        let _ = reply.send(bytes);
    }
    Ok(())
}

fn exchange_of(
    caller: &Caller<'_, WasiP1Ctx>,
    handle: &Option<Rooted<ExternRef>>,
) -> wasmtime::Result<Rooted<ExternRef>> {
    match handle {
        Some(h) if h.data(caller)?.is_some_and(|d| d.is::<Handle>()) => Ok(*h),
        _ => wasmtime::bail!("rlhttp: not a request handle"),
    }
}

fn with_exchange<T>(
    caller: &Caller<'_, WasiP1Ctx>,
    handle: Option<Rooted<ExternRef>>,
    f: impl FnOnce(&mut Exchange) -> T,
) -> wasmtime::Result<T> {
    let handle = exchange_of(caller, &handle)?;
    let data = handle
        .data(caller)?
        .and_then(|d| d.downcast_ref::<Handle>())
        .expect("checked above");
    Ok(f(&mut lock(&data.0)))
}

/// Binds every `rlhttp` import of the server.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    // serve(host, port) -> server, or -1 (serveError says why).
    linker.func_wrap(
        MODULE,
        "serve",
        |mut caller: Caller<'_, WasiP1Ctx>, p: i32, n: i32, port: i32| -> wasmtime::Result<i32> {
            let host = memory_string(&mut caller, p, n)?;
            Ok(serve(&host, port).unwrap_or_else(|e| {
                *lock(&ERROR) = e;
                -1
            }))
        },
    )?;
    linker.func_wrap(
        MODULE,
        "serveError",
        |mut caller: Caller<'_, WasiP1Ctx>| -> wasmtime::Result<(i32, i32)> {
            let text = lock(&ERROR).clone();
            return_string(&mut caller, &text)
        },
    )?;
    // nextRequest(server) -> exchange: blocks until a request has arrived whole.
    linker.func_wrap(
        MODULE,
        "nextRequest",
        |mut caller: Caller<'_, WasiP1Ctx>, server: i32| -> wasmtime::Result<Option<Rooted<ExternRef>>> {
            let Some(server) = usize::try_from(server)
                .ok()
                .and_then(|i| lock(&SERVERS).get(i).cloned())
            else {
                wasmtime::bail!("rlhttp: not a server");
            };
            let exchange = match lock(&server.requests).recv() {
                Ok(ex) => ex,
                Err(_) => wasmtime::bail!("rlhttp: the server stopped accepting"),
            };
            Ok(Some(ExternRef::new(&mut caller, Handle(Mutex::new(exchange)))?))
        },
    )?;
    linker.func_wrap(
        MODULE,
        "requestHead",
        |mut caller: Caller<'_, WasiP1Ctx>, handle: Option<Rooted<ExternRef>>| -> wasmtime::Result<(i32, i32)> {
            let head = with_exchange(&caller, handle, |ex| ex.head.clone())?;
            return_string(&mut caller, &head)
        },
    )?;
    // readRequestBody(exchange, ptr, cap) -> the octets written at ptr, 0 at the end.
    linker.func_wrap(
        MODULE,
        "readRequestBody",
        |mut caller: Caller<'_, WasiP1Ctx>,
         handle: Option<Rooted<ExternRef>>,
         ptr: i32,
         cap: i32|
         -> wasmtime::Result<i32> {
            let chunk = with_exchange(&caller, handle, |ex| {
                let n = (cap.max(0) as usize).min(ex.body.len() - ex.pos);
                let chunk = ex.body[ex.pos..ex.pos + n].to_vec();
                ex.pos += n;
                chunk
            })?;
            let Some(wasmtime::Extern::Memory(memory)) = caller.get_export("memory") else {
                wasmtime::bail!("the module exports no memory");
            };
            memory.write(&mut caller, ptr as u32 as usize, &chunk)?;
            Ok(chunk.len() as i32)
        },
    )?;
    // writeResponseBody(exchange, octets): the next part of the response body.
    linker.func_wrap(
        MODULE,
        "writeResponseBody",
        |mut caller: Caller<'_, WasiP1Ctx>,
         handle: Option<Rooted<ExternRef>>,
         ptr: i32,
         len: i32|
         -> wasmtime::Result<()> {
            let Some(wasmtime::Extern::Memory(memory)) = caller.get_export("memory") else {
                wasmtime::bail!("the module exports no memory");
            };
            let (start, end) = (ptr as u32 as usize, ptr as u32 as usize + len as u32 as usize);
            let Some(bytes) = memory.data(&caller).get(start..end).map(<[u8]>::to_vec) else {
                wasmtime::bail!("a response chunk lies outside linear memory");
            };
            with_exchange(&caller, handle, |ex| ex.out.extend_from_slice(&bytes))
        },
    )?;
    // respond(exchange, head): the response goes out.
    linker.func_wrap(
        MODULE,
        "respond",
        |mut caller: Caller<'_, WasiP1Ctx>,
         handle: Option<Rooted<ExternRef>>,
         p: i32,
         n: i32|
         -> wasmtime::Result<()> {
            let head = memory_string(&mut caller, p, n)?;
            match with_exchange(&caller, handle, |ex| respond(ex, &head))? {
                Ok(()) => Ok(()),
                Err(e) => wasmtime::bail!("rlhttp: {e}"),
            }
        },
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Sends `raw` on one connection to a fresh server and answers the request heads the
    /// module would read, the bodies, and what the client read back after each request is
    /// answered with `head` (a body chunk handed over first).
    fn run(raw: &'static [u8], head: &'static str) -> (Vec<String>, Vec<Vec<u8>>, String) {
        let index = serve("127.0.0.1", 0).unwrap();
        let server = lock(&SERVERS)[index as usize].clone();
        let mut client = TcpStream::connect(("127.0.0.1", server.port)).unwrap();
        let reader = {
            let mut c = client.try_clone().unwrap();
            std::thread::spawn(move || {
                let mut got = String::new();
                let _ = c.read_to_string(&mut got);
                got
            })
        };
        client.write_all(raw).unwrap();
        let (mut heads, mut bodies) = (Vec::new(), Vec::new());
        loop {
            let Ok(mut ex) = lock(&server.requests).recv_timeout(Duration::from_secs(2)) else {
                break;
            };
            heads.push(ex.head.clone());
            bodies.push(ex.body.clone());
            let keep_alive = ex.keep_alive;
            ex.out.extend_from_slice(b"crossed");
            respond(&mut ex, head).unwrap();
            if !keep_alive {
                break;
            }
        }
        client.shutdown(std::net::Shutdown::Write).unwrap();
        (heads, bodies, reader.join().unwrap())
    }

    #[test]
    fn a_kept_alive_connection_carries_requests_and_their_bodies_both_framings() {
        let (heads, bodies, wire) = run(
            b"GET /a?x=%20 HTTP/1.1\r\nHost: h:8\r\nX-Two: 1\r\nx-two: 2\r\n\r\n\
              POST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nab\xff\
              PUT /c HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n\
              2\r\nhe\r\n3;x=y\r\nllo\r\n0\r\nT: t\r\n\r\n",
            r#"{"status":201,"headers":[["content-type","text/plain"],["set-cookie","a"],["set-cookie","b"],["content-length","99"]]}"#,
        );
        assert_eq!(heads.len(), 3);
        assert!(heads[0].starts_with(r#"{"method":"GET","target":"/a?x=%20","protocol":"HTTP/1.1","scheme":"http","remote-addr":"127.0.0.1","remote-port":"#), "{}", heads[0]);
        assert!(
            heads[0].ends_with(r#""headers":{"host":"h:8","x-two":"1, 2"}}"#),
            "{}",
            heads[0]
        );
        assert!(
            heads[2].ends_with(r#""headers":{"host":"h","connection":"close","content-length":"5"}}"#),
            "{}",
            heads[2]
        );
        assert_eq!(bodies, vec![b"".to_vec(), b"ab\xff".to_vec(), b"hello".to_vec()]);
        let one = "HTTP/1.1 201 Created\r\ncontent-type: text/plain\r\nset-cookie: a\r\nset-cookie: b\r\ncontent-length: 7\r\n";
        assert_eq!(
            wire,
            format!("{one}\r\ncrossed{one}\r\ncrossed{one}connection: close\r\n\r\ncrossed")
        );
    }

    #[test]
    fn an_in_band_body_replaces_what_crossed_and_head_sends_none() {
        let (_, _, wire) = run(
            b"HEAD / HTTP/1.0\r\n\r\n",
            r#"{"status":500,"headers":[],"body":"oops\n"}"#,
        );
        assert_eq!(
            wire,
            "HTTP/1.1 500 Internal Server Error\r\ncontent-length: 5\r\nconnection: close\r\n\r\n"
        );
        let (_, _, wire) = run(b"GET / HTTP/1.0\r\n\r\n", r#"{"status":204,"headers":[]}"#);
        assert_eq!(wire, "HTTP/1.1 204 No Content\r\nconnection: close\r\n\r\n");
    }

    #[test]
    fn a_malformed_request_is_answered_400_and_an_occupied_port_is_refused() {
        let (heads, _, wire) = run(b"NOT A REQUEST\r\n\r\n", r#"{"status":200,"headers":[]}"#);
        assert!(heads.is_empty());
        assert!(wire.starts_with("HTTP/1.1 400 Bad Request\r\n"), "{wire}");
        let held = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = held.local_addr().unwrap().port();
        let why = serve("127.0.0.1", i32::from(port)).unwrap_err();
        assert!(
            why.starts_with(&format!("cannot listen on 127.0.0.1:{port}: ")),
            "{why}"
        );
    }
}
