//! The one URL shape a fetch accepts: `http` or `https`, an authority, and the rest sent
//! as the request target verbatim. What the JDK's `URI.create` refuses is refused here
//! too (whitespace, control characters), and what `HttpClient` ignores is ignored (a
//! fragment, user information): the interpreter and the JVM fetch through them.

/// Where a request goes and what its request line and `Host` field say.
#[derive(Debug, PartialEq, Eq)]
pub struct Target {
    pub tls: bool,
    /// The name or address to connect to (an IPv6 literal without its brackets).
    pub host: String,
    pub port: u16,
    /// The `Host` field: the authority's host, and its port when not the scheme's default.
    pub authority: String,
    /// The origin-form request target: the path and the query, `/` when both are empty.
    pub path: String,
}

pub fn parse(url: &str) -> Result<Target, String> {
    let bad = |why: &str| format!("invalid URL {url:?}: {why}");
    if url.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err(bad("whitespace or a control character"));
    }
    let (scheme, rest) = url.split_once("://").ok_or_else(|| bad("no scheme"))?;
    let tls = if scheme.eq_ignore_ascii_case("https") {
        true
    } else if scheme.eq_ignore_ascii_case("http") {
        false
    } else {
        return Err(bad("the scheme is neither http nor https"));
    };
    let rest = rest.split('#').next().unwrap_or("");
    let end = rest.find(['/', '?']).unwrap_or(rest.len());
    let (authority, target) = rest.split_at(end);
    // User information is not sent (HttpClient does not either).
    let authority = authority.rsplit_once('@').map_or(authority, |(_, hostport)| hostport);
    let (host, port_text) = if let Some(v6) = authority.strip_prefix('[') {
        let (addr, after) = v6.split_once(']').ok_or_else(|| bad("unclosed IPv6 literal"))?;
        let port = match after {
            "" => None,
            p => Some(p.strip_prefix(':').ok_or_else(|| bad("junk after the IPv6 literal"))?),
        };
        (addr, port)
    } else {
        match authority.rsplit_once(':') {
            Some((h, p)) => (h, Some(p)),
            None => (authority, None),
        }
    };
    if host.is_empty() {
        return Err(bad("no host"));
    }
    let default_port = if tls { 443 } else { 80 };
    let port = match port_text {
        None | Some("") => default_port,
        Some(p) => p
            .parse::<u16>()
            .ok()
            .filter(|p| *p != 0)
            .ok_or_else(|| bad("the port is not a number from 1 to 65535"))?,
    };
    let bracketed = if host.contains(':') {
        format!("[{host}]")
    } else {
        host.to_owned()
    };
    let authority = if port == default_port {
        bracketed
    } else {
        format!("{bracketed}:{port}")
    };
    let path = if target.is_empty() {
        "/".to_owned()
    } else if target.starts_with('?') {
        format!("/{target}")
    } else {
        target.to_owned()
    };
    Ok(Target {
        tls,
        host: host.to_owned(),
        port,
        authority,
        path,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(tls: bool, host: &str, port: u16, authority: &str, path: &str) -> Target {
        Target {
            tls,
            host: host.into(),
            port,
            authority: authority.into(),
            path: path.into(),
        }
    }

    #[test]
    fn splits_the_shapes_a_fetch_is_given() {
        assert_eq!(
            parse("http://example.com").unwrap(),
            t(false, "example.com", 80, "example.com", "/")
        );
        assert_eq!(
            parse("HTTPS://example.com:8443/a/b?c=d#frag").unwrap(),
            t(true, "example.com", 8443, "example.com:8443", "/a/b?c=d")
        );
        assert_eq!(parse("https://h:443?q").unwrap(), t(true, "h", 443, "h", "/?q"));
        assert_eq!(
            parse("http://[::1]:8080/x").unwrap(),
            t(false, "::1", 8080, "[::1]:8080", "/x")
        );
        assert_eq!(parse("http://[::1]/").unwrap(), t(false, "::1", 80, "[::1]", "/"));
        assert_eq!(
            parse("http://u:p@127.0.0.1:9/%20").unwrap(),
            t(false, "127.0.0.1", 9, "127.0.0.1:9", "/%20")
        );
    }

    #[test]
    fn refuses_what_uri_create_refuses() {
        for bad in [
            "example.com",
            "ftp://example.com/",
            "http:///path",
            "http://h:x/",
            "http://h:0/",
            "http://h:70000/",
            "http://h/a b",
            "http://[::1/",
            "http://h/\n",
        ] {
            assert!(parse(bad).is_err(), "{bad}");
        }
    }
}
