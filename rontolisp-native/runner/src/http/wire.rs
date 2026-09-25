//! One HTTP/1.1 exchange over a fresh connection: the request a fetch describes goes out,
//! the reply head comes back parsed, and the body is framed for reading as the octets the
//! origin sent (de-chunked, never decoded).
//!
//! What the interpreter and the JVM do through the JDK's `HttpClient` is the reference:
//! the fields it refuses to let a caller set are refused, a reply's field names come back
//! lowercased and sorted with each value decoded as ISO-8859-1, redirects are not
//! followed, and nothing is decompressed. One connection per request, closed after the
//! reply (`Connection: close`): a runner makes a request at a time per thread and keeps
//! no pool.

use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::{Arc, OnceLock};

use super::url::Target;

/// A request as the module states it.
#[derive(Debug, Default)]
pub struct Request {
    pub method: String,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Option<Vec<u8>>,
}

/// A reply head: the status and the fields, names lowercased, in `HttpClient`'s order.
#[derive(Debug, PartialEq)]
pub struct Head {
    pub status: u16,
    pub headers: Vec<(String, String)>,
}

/// The connection a body is read from.
pub trait Conn: Read + Write + Send {}
impl<T: Read + Write + Send> Conn for T {}

/// The fields `HttpClient` reserves for itself (`jdk.internal.net.http.common.Utils`);
/// naming one fails the request, as it fails there.
const RESTRICTED: [&str; 5] = ["connection", "content-length", "expect", "host", "upgrade"];

/// A head larger than this is refused rather than buffered without end.
const MAX_HEAD: usize = 256 * 1024;

/// Checks what `HttpRequest.Builder` checks before anything is sent: the method, and
/// every field name a token and every value free of line breaks.
pub fn validate(req: &Request) -> Result<(), String> {
    if !req.method.bytes().all(is_tchar) || req.method.is_empty() {
        return Err(format!("invalid method {:?}", req.method));
    }
    for (name, value) in &req.headers {
        if name.is_empty() || !name.bytes().all(is_tchar) {
            return Err(format!("invalid header name {name:?}"));
        }
        if RESTRICTED.iter().any(|r| name.eq_ignore_ascii_case(r)) {
            return Err(format!("restricted header name: {name:?}"));
        }
        if value.chars().any(|c| c == '\r' || c == '\n' || c == '\0') {
            return Err(format!("invalid header value for {name:?}"));
        }
    }
    Ok(())
}

fn is_tchar(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b)
}

/// The request's bytes on the wire.
pub fn encode(req: &Request, target: &Target) -> Vec<u8> {
    let mut out = format!(
        "{} {} HTTP/1.1\r\nHost: {}\r\n",
        req.method, target.path, target.authority
    );
    for (name, value) in &req.headers {
        out.push_str(name);
        out.push_str(": ");
        out.push_str(value);
        out.push_str("\r\n");
    }
    match &req.body {
        Some(body) => out.push_str(&format!("Content-Length: {}\r\n", body.len())),
        // HttpClient states an empty body for the methods that carry one.
        None if matches!(req.method.as_str(), "POST" | "PUT" | "PATCH") => out.push_str("Content-Length: 0\r\n"),
        None => {}
    }
    out.push_str("Connection: close\r\n\r\n");
    let mut bytes = out.into_bytes();
    if let Some(body) = &req.body {
        bytes.extend_from_slice(body);
    }
    bytes
}

/// Opens the connection `target` names, TLS included.
/// Opens the connection `target` names, TLS included -- under the configuration `tls`
/// answers, asked for only when the target is `https`. Answers the connection and a
/// second handle on its socket: shutting that one down ends a read of the first that
/// another thread is blocked in.
pub fn connect(target: &Target, tls: &dyn Fn() -> TlsConfig) -> Result<(Box<dyn Conn>, TcpStream), String> {
    let tcp = TcpStream::connect((target.host.as_str(), target.port))
        .map_err(|e| format!("cannot connect to {}:{}: {e}", target.host, target.port))?;
    let _ = tcp.set_nodelay(true);
    let socket = tcp
        .try_clone()
        .map_err(|e| format!("cannot connect to {}:{}: {e}", target.host, target.port))?;
    if !target.tls {
        return Ok((Box::new(tcp), socket));
    }
    let name = rustls::pki_types::ServerName::try_from(target.host.clone())
        .map_err(|e| format!("invalid TLS server name {:?}: {e}", target.host))?;
    let mut conn = rustls::ClientConnection::new(tls()?, name).map_err(|e| format!("TLS: {e}"))?;
    let mut tcp = tcp;
    // The handshake now, not at the first write, so a certificate nobody vouches for
    // is reported as what it is.
    while conn.is_handshaking() {
        conn.complete_io(&mut tcp)
            .map_err(|e| format!("TLS handshake with {} failed: {e}", target.host))?;
    }
    Ok((Box::new(rustls::StreamOwned::new(conn, tcp)), socket))
}

/// A TLS client configuration, or why there is none.
pub type TlsConfig = Result<Arc<rustls::ClientConfig>, String>;

/// The environment variable naming a PEM bundle of the roots to trust INSTEAD of the
/// compiled-in set -- the convention OpenSSL, curl and Go follow, and what a private or
/// TLS-inspecting CA needs.
pub const CERT_FILE_VAR: &str = "SSL_CERT_FILE";

/// The one TLS client configuration of the process, built on the first HTTPS request
/// (a program that never makes one never reads a root): the roots of
/// [`CERT_FILE_VAR`] when it names a bundle, else Mozilla's (`webpki-roots`, the set
/// wasmtime's own `wasi:http` trusts, compiled in so that an output needs no bundle on
/// the machine it runs on).
pub fn tls_config() -> TlsConfig {
    static CONFIG: OnceLock<TlsConfig> = OnceLock::new();
    CONFIG
        .get_or_init(|| client_config(roots(std::env::var_os(CERT_FILE_VAR).as_deref())?))
        .clone()
}

/// The trust anchors: every certificate in the PEM file `bundle` names (an empty value
/// is no value), else the compiled-in set. A bundle that cannot be read or holds no
/// certificate fails every HTTPS request, naming the variable, rather than falling back.
pub fn roots(bundle: Option<&std::ffi::OsStr>) -> Result<rustls::RootCertStore, String> {
    use rustls::pki_types::CertificateDer;
    use rustls::pki_types::pem::PemObject;

    let Some(path) = bundle.filter(|p| !p.is_empty()) else {
        return Ok(rustls::RootCertStore {
            roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
        });
    };
    let named =
        |why: &dyn std::fmt::Display| format!("{CERT_FILE_VAR}={}: {why}", std::path::Path::new(path).display());
    let certs = CertificateDer::pem_file_iter(path)
        .and_then(Iterator::collect::<Result<Vec<_>, _>>)
        .map_err(|e| named(&e))?;
    let mut store = rustls::RootCertStore::empty();
    let (added, _) = store.add_parsable_certificates(certs);
    if added == 0 {
        return Err(named(&"no certificate to trust in it"));
    }
    Ok(store)
}

/// A client configuration trusting `roots`: ring's provider, TLS 1.2 and 1.3, ALPN
/// `http/1.1` (the only protocol this client speaks).
pub fn client_config(roots: rustls::RootCertStore) -> TlsConfig {
    let mut config = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .map_err(|e| format!("TLS: {e}"))?
        .with_root_certificates(roots)
        .with_no_client_auth();
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(config))
}

/// Sends `req` over `conn` and reads the reply head, skipping interim (1xx) replies.
/// Answers the head and the body reader positioned after it.
pub fn exchange(mut conn: Box<dyn Conn>, req: &Request, target: &Target) -> Result<(Head, Body), String> {
    conn.write_all(&encode(req, target))
        .and_then(|()| conn.flush())
        .map_err(|e| format!("cannot send the request: {e}"))?;
    let mut buf: Vec<u8> = Vec::new();
    loop {
        let (head, consumed) = read_head(&mut conn, &mut buf)?;
        buf.drain(..consumed);
        // 101 is not interim: nothing here switches protocols, so it is the answer.
        if (100..200).contains(&head.status) && head.status != 101 {
            continue;
        }
        let framing = framing(&req.method, &head)?;
        let body = Body {
            conn,
            buf,
            pos: 0,
            framing,
        };
        return Ok((head, body));
    }
}

/// Reads until `buf` holds a whole head; answers it and how many bytes of `buf` it took.
fn read_head(conn: &mut Box<dyn Conn>, buf: &mut Vec<u8>) -> Result<(Head, usize), String> {
    let mut chunk = [0u8; 8192];
    loop {
        if let Some(parsed) = parse_head(buf)? {
            return Ok(parsed);
        }
        if buf.len() > MAX_HEAD {
            return Err("the reply head is larger than 256 KiB".into());
        }
        let n = conn
            .read(&mut chunk)
            .map_err(|e| format!("cannot read the reply: {e}"))?;
        if n == 0 {
            return Err("the connection closed before a reply head arrived".into());
        }
        buf.extend_from_slice(&chunk[..n]);
    }
}

/// A complete head at the front of `buf`, `None` while it is still arriving.
pub fn parse_head(buf: &[u8]) -> Result<Option<(Head, usize)>, String> {
    let mut slots = vec![httparse::EMPTY_HEADER; 64];
    loop {
        let mut response = httparse::Response::new(&mut slots);
        match response.parse(buf) {
            Ok(httparse::Status::Partial) => return Ok(None),
            Ok(httparse::Status::Complete(consumed)) => {
                let status = response.code.ok_or("the reply has no status")?;
                let mut headers: Vec<(String, String)> = response
                    .headers
                    .iter()
                    .map(|h| (h.name.to_ascii_lowercase(), latin1(h.value)))
                    .collect();
                // HttpClient's order: by name (a stable sort keeps a repeated field's
                // values as they came).
                headers.sort_by(|a, b| a.0.cmp(&b.0));
                return Ok(Some((Head { status, headers }, consumed)));
            }
            Err(httparse::Error::TooManyHeaders) if slots.len() < 4096 => {
                let n = slots.len() * 4;
                slots = vec![httparse::EMPTY_HEADER; n];
            }
            Err(e) => return Err(format!("malformed reply head: {e}")),
        }
    }
}

fn latin1(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| b as char).collect()
}

/// How the body of a reply is delimited (RFC 9112, section 6.3).
#[derive(Debug, PartialEq)]
pub enum Framing {
    /// No body at all: a reply to HEAD, a 1xx, 204 or 304.
    Empty,
    /// `Content-Length` octets.
    Length(u64),
    /// `Transfer-Encoding: chunked`: the octets left in the current chunk, or `None`
    /// before a chunk-size line.
    Chunked(Option<u64>),
    /// Everything until the origin closes the connection.
    Close,
}

fn framing(method: &str, head: &Head) -> Result<Framing, String> {
    if method == "HEAD" || (100..200).contains(&head.status) || head.status == 204 || head.status == 304 {
        return Ok(Framing::Empty);
    }
    fn field<'a>(head: &'a Head, name: &'a str) -> impl Iterator<Item = &'a str> {
        head.headers
            .iter()
            .filter(move |(n, _)| n == name)
            .map(|(_, v)| v.as_str())
    }
    if let Some(te) = field(head, "transfer-encoding").last() {
        let last = te.rsplit(',').next().unwrap_or("").trim();
        return Ok(if last.eq_ignore_ascii_case("chunked") {
            Framing::Chunked(None)
        } else {
            Framing::Close
        });
    }
    let mut length: Option<u64> = None;
    for value in field(head, "content-length") {
        for part in value.split(',') {
            let n = part
                .trim()
                .parse::<u64>()
                .map_err(|_| format!("invalid content-length {value:?}"))?;
            if length.is_some_and(|l| l != n) {
                return Err("conflicting content-length fields".into());
            }
            length = Some(n);
        }
    }
    Ok(match length {
        Some(0) => Framing::Empty,
        Some(n) => Framing::Length(n),
        None => Framing::Close,
    })
}

/// The reply body, read as the octets the origin sent.
pub struct Body {
    conn: Box<dyn Conn>,
    /// Bytes read past the head (or past the last chunk line), not yet answered.
    buf: Vec<u8>,
    pos: usize,
    framing: Framing,
}

impl Body {
    /// Up to `out.len()` octets of the body; `Ok(0)` at its end.
    pub fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() {
            return Ok(0);
        }
        loop {
            match self.framing {
                Framing::Empty => return Ok(0),
                Framing::Close => return self.raw(out, usize::MAX, true),
                Framing::Length(left) => {
                    let n = self.raw(out, left.min(usize::MAX as u64) as usize, false)?;
                    let left = left - n as u64;
                    self.framing = if left == 0 {
                        Framing::Empty
                    } else {
                        Framing::Length(left)
                    };
                    return Ok(n);
                }
                Framing::Chunked(Some(0)) => {
                    // The CRLF that ends a chunk's data.
                    self.expect_crlf()?;
                    self.framing = Framing::Chunked(None);
                }
                Framing::Chunked(Some(left)) => {
                    let n = self.raw(out, left.min(usize::MAX as u64) as usize, false)?;
                    self.framing = Framing::Chunked(Some(left - n as u64));
                    return Ok(n);
                }
                Framing::Chunked(None) => {
                    let line = self.line()?;
                    let size = line.split(';').next().unwrap_or("").trim();
                    let size = u64::from_str_radix(size, 16)
                        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, format!("bad chunk size {line:?}")))?;
                    if size == 0 {
                        // The trailer section, up to its empty line.
                        while !self.line()?.is_empty() {}
                        self.framing = Framing::Empty;
                        return Ok(0);
                    }
                    self.framing = Framing::Chunked(Some(size));
                }
            }
        }
    }

    /// Up to `limit` octets: from the look-ahead buffer first, else one read of the
    /// connection. At the connection's end, `Ok(0)` when `eof_ok`, else an error: the
    /// framing promised more.
    fn raw(&mut self, out: &mut [u8], limit: usize, eof_ok: bool) -> io::Result<usize> {
        let want = out.len().min(limit);
        if self.pos < self.buf.len() {
            let n = want.min(self.buf.len() - self.pos);
            out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
            self.pos += n;
            if self.pos == self.buf.len() {
                self.buf.clear();
                self.pos = 0;
            }
            return Ok(n);
        }
        let n = match self.conn.read(&mut out[..want]) {
            // A TLS peer that closes without close_notify: the framing decides whether
            // anything was cut short.
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => 0,
            r => r?,
        };
        if n == 0 && !eof_ok {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "the connection closed before the body ended",
            ));
        }
        if n == 0 {
            self.framing = Framing::Empty;
        }
        Ok(n)
    }

    /// One CRLF-terminated line of the chunked framing, without the CRLF.
    fn line(&mut self) -> io::Result<String> {
        let mut line = Vec::new();
        let mut byte = [0u8; 1];
        loop {
            if self.raw(&mut byte, 1, false)? == 0 {
                unreachable!("raw answers an error at the end");
            }
            if byte[0] == b'\n' {
                if line.last() == Some(&b'\r') {
                    line.pop();
                }
                return Ok(latin1(&line));
            }
            if line.len() > 8192 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "chunk line too long"));
            }
            line.push(byte[0]);
        }
    }

    fn expect_crlf(&mut self) -> io::Result<()> {
        if self.line()?.is_empty() {
            Ok(())
        } else {
            Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "chunk data longer than its size",
            ))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A connection that answers `input` and records what was written.
    struct Scripted {
        input: io::Cursor<Vec<u8>>,
        step: usize,
    }

    impl Read for Scripted {
        fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
            // A few bytes at a time, so every framing state is crossed by a read.
            let n = out.len().min(self.step);
            self.input.read(&mut out[..n])
        }
    }

    impl Write for Scripted {
        fn write(&mut self, b: &[u8]) -> io::Result<usize> {
            Ok(b.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    fn reply(method: &str, wire: &str, step: usize) -> Result<(Head, Vec<u8>), String> {
        let conn = Box::new(Scripted {
            input: io::Cursor::new(wire.as_bytes().to_vec()),
            step,
        });
        let target = super::super::url::parse("http://h/").unwrap();
        let req = Request {
            method: method.into(),
            ..Default::default()
        };
        let (head, mut body) = exchange(conn, &req, &target)?;
        let mut out = Vec::new();
        let mut chunk = [0u8; 7];
        loop {
            let n = body.read(&mut chunk).map_err(|e| e.to_string())?;
            if n == 0 {
                return Ok((head, out));
            }
            out.extend_from_slice(&chunk[..n]);
        }
    }

    #[test]
    fn reads_every_framing_at_every_step() {
        for step in [1, 2, 3, 5, 64, 4096] {
            let (head, body) = reply(
                "GET",
                "HTTP/1.1 200 OK\r\nZeta: 1\r\nX-Test: ok\r\nset-cookie: a\r\nSet-Cookie: b\r\nContent-Length: 5\r\n\r\nhelloEXTRA",
                step,
            )
            .unwrap();
            assert_eq!(head.status, 200);
            assert_eq!(
                head.headers,
                vec![
                    ("content-length".into(), "5".into()),
                    ("set-cookie".into(), "a".into()),
                    ("set-cookie".into(), "b".into()),
                    ("x-test".into(), "ok".into()),
                    ("zeta".into(), "1".into()),
                ]
            );
            assert_eq!(body, b"hello");
            let chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;x=y\r\nWiki\r\n6\r\npedia \r\nE\r\nin \r\n\r\nchunks.\r\n0\r\nT: 1\r\n\r\n";
            assert_eq!(reply("GET", chunked, step).unwrap().1, b"Wikipedia in \r\n\r\nchunks.");
            let close = "HTTP/1.1 200 OK\r\n\r\nuntil the end";
            assert_eq!(reply("GET", close, step).unwrap().1, b"until the end");
            let interim = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\nok";
            let (head, body) = reply("POST", interim, step).unwrap();
            assert_eq!((head.status, body.as_slice()), (201, &b"ok"[..]));
        }
    }

    #[test]
    fn a_bodiless_reply_is_empty_whatever_its_fields_say() {
        let wire = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n";
        assert_eq!(reply("HEAD", wire, 64).unwrap().1, b"");
        let wire = "HTTP/1.1 204 No Content\r\nContent-Length: 10\r\n\r\n";
        assert_eq!(reply("GET", wire, 64).unwrap().1, b"");
        let wire = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        assert_eq!(reply("GET", wire, 64).unwrap().1, b"");
    }

    #[test]
    fn a_body_cut_short_is_an_error_not_a_short_body() {
        assert!(reply("GET", "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort", 64).is_err());
        assert!(
            reply(
                "GET",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nab",
                64
            )
            .is_err()
        );
        assert!(reply("GET", "HTTP/1.1 200 OK\r\nContent-Length: 1, 2\r\n\r\nab", 64).is_err());
        assert!(reply("GET", "HTTP/1.1 200 OK\r\n", 64).is_err());
        assert!(reply("GET", "", 64).is_err());
        assert!(reply("GET", "garbage\r\n\r\n", 64).is_err());
    }

    #[test]
    fn a_field_value_is_iso_8859_1_as_httpclient_decodes_it() {
        let (head, _) = reply(
            "GET",
            "HTTP/1.1 200 OK\r\nAlpha: \u{e9}x\r\nContent-Length: 0\r\n\r\n",
            64,
        )
        .unwrap();
        // The wire carries U+00E9 as its two UTF-8 octets; each octet is one character.
        assert_eq!(head.headers[0], ("alpha".into(), "\u{c3}\u{a9}x".into()));
    }

    #[test]
    fn the_request_is_what_httpclient_would_send() {
        let target = super::super::url::parse("http://h:8080/p?q").unwrap();
        let req = Request {
            method: "POST".into(),
            url: String::new(),
            headers: vec![("User-Agent".into(), "ua/1".into())],
            body: Some(b"hi".to_vec()),
        };
        assert_eq!(
            String::from_utf8(encode(&req, &target)).unwrap(),
            "POST /p?q HTTP/1.1\r\nHost: h:8080\r\nUser-Agent: ua/1\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi"
        );
        let get = Request {
            method: "GET".into(),
            ..Default::default()
        };
        assert!(
            !String::from_utf8(encode(&get, &target))
                .unwrap()
                .contains("Content-Length")
        );
        let put = Request {
            method: "PUT".into(),
            ..Default::default()
        };
        assert!(
            String::from_utf8(encode(&put, &target))
                .unwrap()
                .contains("Content-Length: 0\r\n")
        );
    }

    #[test]
    fn the_fields_httpclient_refuses_are_refused() {
        let with = |name: &str, value: &str| Request {
            method: "GET".into(),
            headers: vec![(name.into(), value.into())],
            ..Default::default()
        };
        assert!(validate(&with("Accept", "text/plain")).is_ok());
        for (name, value) in [
            ("Host", "x"),
            ("connection", "close"),
            ("Content-Length", "1"),
            ("a b", "x"),
            ("", "x"),
            ("X", "a\r\nb"),
        ] {
            assert!(validate(&with(name, value)).is_err(), "{name:?}");
        }
    }
    /// The test material in `testdata` (never a runner's).
    fn testdata(name: &str) -> std::path::PathBuf {
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("src/http/testdata")
            .join(name)
    }

    /// What an origin saw of each connection: the request and the protocol ALPN settled
    /// on, or why the handshake failed.
    type Seen = Vec<Result<(String, Option<Vec<u8>>), String>>;

    /// An HTTPS origin for `localhost` / `127.0.0.1` under the test root, answering each of
    /// `connections` with `secure`.
    fn tls_origin(connections: usize) -> (u16, std::thread::JoinHandle<Seen>) {
        use rustls::pki_types::pem::PemObject;
        use rustls::pki_types::{CertificateDer, PrivateKeyDer};

        let certs = CertificateDer::pem_file_iter(testdata("localhost.pem"))
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        let key = PrivateKeyDer::from_pem_file(testdata("localhost-key.pem")).unwrap();
        let mut config =
            rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(certs, key)
                .unwrap();
        config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
        let config = Arc::new(config);
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let origin = std::thread::spawn(move || {
            (0..connections)
                .map(|_| {
                    let (tcp, _) = listener.accept().map_err(|e| e.to_string())?;
                    let conn = rustls::ServerConnection::new(config.clone()).map_err(|e| e.to_string())?;
                    let mut tls = rustls::StreamOwned::new(conn, tcp);
                    let mut seen = Vec::new();
                    let mut b = [0u8; 1024];
                    while !seen.ends_with(b"\r\n\r\n") {
                        match tls.read(&mut b) {
                            Ok(0) => return Err("closed before the request ended".to_owned()),
                            Ok(n) => seen.extend_from_slice(&b[..n]),
                            Err(e) => return Err(e.to_string()),
                        }
                    }
                    let alpn = tls.conn.alpn_protocol().map(<[u8]>::to_vec);
                    tls.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecure")
                        .map_err(|e| e.to_string())?;
                    tls.conn.send_close_notify();
                    tls.flush().map_err(|e| e.to_string())?;
                    Ok((String::from_utf8_lossy(&seen).into_owned(), alpn))
                })
                .collect()
        });
        (port, origin)
    }

    fn fetch_over(target: &Target, tls: &dyn Fn() -> TlsConfig) -> Result<(Head, Vec<u8>), String> {
        let (conn, _socket) = connect(target, tls)?;
        let req = Request {
            method: "GET".into(),
            ..Default::default()
        };
        let (head, mut body) = exchange(conn, &req, target)?;
        let mut out = vec![0u8; 64];
        let n = body.read(&mut out).map_err(|e| e.to_string())?;
        out.truncate(n);
        assert_eq!(body.read(&mut [0u8; 8]).map_err(|e| e.to_string())?, 0);
        Ok((head, out))
    }

    #[test]
    fn an_https_exchange_verifies_the_origin_by_name_and_by_address() {
        let (port, origin) = tls_origin(2);
        let trusted = || client_config(roots(Some(testdata("ca.pem").as_os_str()))?);
        for host in ["localhost", "127.0.0.1"] {
            let target = super::super::url::parse(&format!("https://{host}:{port}/p?q")).unwrap();
            let (head, body) = fetch_over(&target, &trusted).unwrap();
            assert_eq!((head.status, body.as_slice()), (200, &b"secure"[..]));
        }
        for (seen, alpn) in origin.join().unwrap().into_iter().map(Result::unwrap) {
            assert!(seen.starts_with("GET /p?q HTTP/1.1\r\nHost: "), "{seen}");
            // The client offers http/1.1 alone, so an origin that prefers h2 still speaks 1.1.
            assert_eq!(alpn.as_deref(), Some(&b"http/1.1"[..]));
        }
    }

    #[test]
    fn a_certificate_no_trusted_root_vouches_for_fails_the_handshake() {
        let (port, origin) = tls_origin(1);
        let target = super::super::url::parse(&format!("https://localhost:{port}/")).unwrap();
        let mozilla = || client_config(roots(None)?);
        let e = fetch_over(&target, &mozilla).unwrap_err();
        assert!(e.starts_with("TLS handshake with localhost failed: "), "{e}");
        assert!(e.contains("UnknownIssuer"), "{e}");
        assert!(origin.join().unwrap()[0].is_err());
    }

    #[test]
    fn ssl_cert_file_replaces_the_compiled_in_roots() {
        assert_eq!(roots(None).unwrap().len(), webpki_roots::TLS_SERVER_ROOTS.len());
        assert_eq!(
            roots(Some("".as_ref())).unwrap().len(),
            webpki_roots::TLS_SERVER_ROOTS.len()
        );
        assert_eq!(roots(Some(testdata("ca.pem").as_os_str())).unwrap().len(), 1);
        let missing = roots(Some("/nonexistent/bundle.pem".as_ref())).unwrap_err();
        assert!(
            missing.starts_with("SSL_CERT_FILE=/nonexistent/bundle.pem: "),
            "{missing}"
        );
        let keys_only = roots(Some(testdata("localhost-key.pem").as_os_str())).unwrap_err();
        assert!(keys_only.ends_with("no certificate to trust in it"), "{keys_only}");
    }
}
