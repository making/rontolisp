//! The JSON the `rlhttp` boundary speaks, both directions: the request record the module
//! writes (`rontolisp:json-stringify`, `FetchResponseShape`'s `request`) and the reply
//! head the module reads back (`rontolisp:json-parse`). Just enough JSON for that: a
//! complete reader (any valid document, so a key order or an escape the writer on the
//! other side chooses cannot break it) and a string writer.

/// A parsed JSON value. Numbers are kept as `f64`: nothing here reads one.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Number(f64),
    String(String),
    Array(Vec<Value>),
    Object(Vec<(String, Value)>),
}

impl Value {
    /// The member `key` of an object, `None` for anything else or a missing key.
    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Object(members) => members.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::String(s) => Some(s),
            _ => None,
        }
    }
}

/// Parses one JSON document (surrounding whitespace allowed, nothing after it).
pub fn parse(text: &str) -> Result<Value, String> {
    let mut p = Parser {
        s: text.as_bytes(),
        i: 0,
    };
    p.ws();
    let v = p.value(0)?;
    p.ws();
    if p.i != p.s.len() {
        return Err(p.error("trailing characters"));
    }
    Ok(v)
}

/// Appends `s` to `out` as a JSON string literal.
pub fn write_string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 || c == '\u{7f}' => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Nesting deeper than this is refused rather than recursed into.
const MAX_DEPTH: usize = 64;

struct Parser<'a> {
    s: &'a [u8],
    i: usize,
}

impl Parser<'_> {
    fn error(&self, what: &str) -> String {
        format!("malformed JSON at offset {}: {what}", self.i)
    }

    fn ws(&mut self) {
        while let Some(b' ' | b'\t' | b'\n' | b'\r') = self.s.get(self.i) {
            self.i += 1;
        }
    }

    fn eat(&mut self, literal: &str) -> bool {
        if self.s[self.i..].starts_with(literal.as_bytes()) {
            self.i += literal.len();
            true
        } else {
            false
        }
    }

    fn value(&mut self, depth: usize) -> Result<Value, String> {
        if depth > MAX_DEPTH {
            return Err(self.error("nested too deeply"));
        }
        match self.s.get(self.i) {
            Some(b'{') => {
                self.i += 1;
                let mut members = Vec::new();
                self.ws();
                if self.eat("}") {
                    return Ok(Value::Object(members));
                }
                loop {
                    self.ws();
                    if self.s.get(self.i) != Some(&b'"') {
                        return Err(self.error("expected a member name"));
                    }
                    let key = self.string()?;
                    self.ws();
                    if !self.eat(":") {
                        return Err(self.error("expected ':'"));
                    }
                    self.ws();
                    let v = self.value(depth + 1)?;
                    members.push((key, v));
                    self.ws();
                    if self.eat(",") {
                        continue;
                    }
                    if self.eat("}") {
                        return Ok(Value::Object(members));
                    }
                    return Err(self.error("expected ',' or '}'"));
                }
            }
            Some(b'[') => {
                self.i += 1;
                let mut items = Vec::new();
                self.ws();
                if self.eat("]") {
                    return Ok(Value::Array(items));
                }
                loop {
                    self.ws();
                    items.push(self.value(depth + 1)?);
                    self.ws();
                    if self.eat(",") {
                        continue;
                    }
                    if self.eat("]") {
                        return Ok(Value::Array(items));
                    }
                    return Err(self.error("expected ',' or ']'"));
                }
            }
            Some(b'"') => self.string().map(Value::String),
            Some(b't') if self.eat("true") => Ok(Value::Bool(true)),
            Some(b'f') if self.eat("false") => Ok(Value::Bool(false)),
            Some(b'n') if self.eat("null") => Ok(Value::Null),
            Some(b'-' | b'0'..=b'9') => self.number(),
            _ => Err(self.error("expected a value")),
        }
    }

    fn number(&mut self) -> Result<Value, String> {
        let start = self.i;
        while let Some(b'-' | b'+' | b'.' | b'e' | b'E' | b'0'..=b'9') = self.s.get(self.i) {
            self.i += 1;
        }
        std::str::from_utf8(&self.s[start..self.i])
            .ok()
            .and_then(|t| t.parse::<f64>().ok())
            .map(Value::Number)
            .ok_or_else(|| self.error("malformed number"))
    }

    fn hex4(&mut self) -> Result<u32, String> {
        let digits = self
            .s
            .get(self.i..self.i + 4)
            .ok_or_else(|| self.error("short \\u escape"))?;
        let text = std::str::from_utf8(digits).map_err(|_| self.error("bad \\u escape"))?;
        let v = u32::from_str_radix(text, 16).map_err(|_| self.error("bad \\u escape"))?;
        self.i += 4;
        Ok(v)
    }

    fn string(&mut self) -> Result<String, String> {
        self.i += 1; // the opening quote
        let mut out: Vec<u8> = Vec::new();
        loop {
            match self.s.get(self.i) {
                None => return Err(self.error("unterminated string")),
                Some(b'"') => {
                    self.i += 1;
                    return String::from_utf8(out).map_err(|_| self.error("invalid UTF-8"));
                }
                Some(b'\\') => {
                    self.i += 1;
                    let c = match self.s.get(self.i) {
                        Some(b'"') => '"',
                        Some(b'\\') => '\\',
                        Some(b'/') => '/',
                        Some(b'b') => '\u{8}',
                        Some(b'f') => '\u{c}',
                        Some(b'n') => '\n',
                        Some(b'r') => '\r',
                        Some(b't') => '\t',
                        Some(b'u') => {
                            self.i += 1;
                            let hi = self.hex4()?;
                            let code = if (0xD800..0xDC00).contains(&hi) && self.eat("\\u") {
                                let lo = self.hex4()?;
                                if !(0xDC00..0xE000).contains(&lo) {
                                    return Err(self.error("unpaired surrogate"));
                                }
                                0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)
                            } else {
                                hi
                            };
                            let c = char::from_u32(code).unwrap_or('\u{FFFD}');
                            let mut buf = [0u8; 4];
                            out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
                            continue;
                        }
                        _ => return Err(self.error("bad escape")),
                    };
                    self.i += 1;
                    let mut buf = [0u8; 4];
                    out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
                }
                Some(&b) => {
                    out.push(b);
                    self.i += 1;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_request_record_in_any_member_order() {
        let v = parse(r#" {"headers":[["Accept","text/plain"],["X","a\"b"]],"method":"POST","url":"http://h/","body":"\u00e9\ud83d\ude00"} "#)
            .unwrap();
        assert_eq!(v.get("method").and_then(Value::as_str), Some("POST"));
        assert_eq!(v.get("body").and_then(Value::as_str), Some("\u{e9}\u{1F600}"));
        let Some(Value::Array(headers)) = v.get("headers") else {
            panic!()
        };
        assert_eq!(
            headers[1],
            Value::Array(vec![Value::String("X".into()), Value::String("a\"b".into())])
        );
        assert_eq!(parse("[1, -2.5e3, true, false, null, {}]").unwrap().get("x"), None);
    }

    #[test]
    fn refuses_what_is_not_json() {
        for bad in ["", "{", "{\"a\" 1}", "[1,]", "\"open", "{} x", "tru", "\"\\q\""] {
            assert!(parse(bad).is_err(), "{bad:?}");
        }
        let deep = "[".repeat(100) + &"]".repeat(100);
        assert!(parse(&deep).is_err());
    }

    #[test]
    fn writes_what_it_reads_back() {
        let mut out = String::new();
        write_string(&mut out, "a\"b\\c\nd\u{1}\u{e9}");
        assert_eq!(out, r#""a\"b\\c\nd\u0001é""#);
        assert_eq!(parse(&out).unwrap(), Value::String("a\"b\\c\nd\u{1}\u{e9}".into()));
    }
}
