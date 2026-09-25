//! `method_getTypeEncoding`'s strings, parsed into the shapes a send is laid out by: the
//! twin of `am.ik.objc.TypeEncoding` (same kinds, same flattening of a struct into its
//! scalar leaves, same refusals), so a selector the JVM binding refuses is refused here in
//! the same words.

/// The kind of a value in an encoding.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Object,
    Class,
    Selector,
    CString,
    Pointer,
    Bool,
    Int8,
    Int16,
    Int32,
    Int64,
    Float,
    Double,
    Void,
    Struct,
}

impl Kind {
    /// Whether the value travels as an address (an integer register).
    pub fn is_address(self) -> bool {
        matches!(
            self,
            Kind::Object | Kind::Class | Kind::Selector | Kind::CString | Kind::Pointer
        )
    }

    /// The scalar's size in bytes (its natural alignment too).
    pub fn size(self) -> usize {
        match self {
            Kind::Bool | Kind::Int8 => 1,
            Kind::Int16 => 2,
            Kind::Int32 | Kind::Float => 4,
            Kind::Void | Kind::Struct => 0,
            _ => 8,
        }
    }

    pub fn is_float(self) -> bool {
        matches!(self, Kind::Float | Kind::Double)
    }
}

/// One type: a scalar, or a struct flattened to its scalar leaves in memory order.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Type {
    pub kind: Kind,
    pub leaves: Vec<Kind>,
    pub unsigned: bool,
}

impl Type {
    fn of(kind: Kind) -> Type {
        Type {
            kind,
            leaves: Vec::new(),
            unsigned: false,
        }
    }

    fn unsigned(kind: Kind) -> Type {
        Type {
            kind,
            leaves: Vec::new(),
            unsigned: true,
        }
    }

    fn structure(leaves: Vec<Kind>) -> Type {
        Type {
            kind: Kind::Struct,
            leaves,
            unsigned: false,
        }
    }

    /// A struct's C layout: each leaf's byte offset, the size and the alignment.
    pub fn layout(&self) -> (Vec<usize>, usize, usize) {
        let mut offsets = Vec::with_capacity(self.leaves.len());
        let mut offset = 0;
        let mut align = 1;
        for leaf in &self.leaves {
            let a = leaf.size();
            offset += (a - offset % a) % a;
            offsets.push(offset);
            offset += a;
            align = align.max(a);
        }
        offset += (align - offset % align) % align;
        (offsets, offset, align)
    }

    /// A homogeneous floating-point aggregate: 1-4 leaves, all `float` or all `double`,
    /// which AAPCS64 passes and returns in consecutive SIMD registers.
    pub fn hfa(&self) -> Option<Kind> {
        let first = *self.leaves.first()?;
        (first.is_float() && self.leaves.len() <= 4 && self.leaves.iter().all(|k| *k == first)).then_some(first)
    }
}

/// A method's encoding: the return type, then `self`, `_cmd` and the declared arguments.
#[derive(Clone, Debug)]
pub struct Encoding {
    pub ret: Type,
    pub args: Vec<Type>,
}

impl Encoding {
    /// The shape as the JVM binding names it in a message (`TypeEncoding.spelling`):
    /// `void*(void*,void*)`.
    pub fn spelling(&self) -> String {
        fn spell(ty: &Type) -> String {
            match ty.kind {
                Kind::Struct => {
                    let leaves: Vec<String> = ty.leaves.iter().map(|k| spell(&Type::of(*k))).collect();
                    format!("struct({})", leaves.join(","))
                }
                Kind::Void => "void".into(),
                Kind::Bool => "jboolean".into(),
                Kind::Int8 => "jbyte".into(),
                Kind::Int16 => "jshort".into(),
                Kind::Int32 => "jint".into(),
                Kind::Int64 => "jlong".into(),
                Kind::Float => "jfloat".into(),
                Kind::Double => "jdouble".into(),
                _ => "void*".into(),
            }
        }
        let args: Vec<String> = self.args.iter().map(spell).collect();
        format!("{}({})", spell(&self.ret), args.join(","))
    }
}

pub fn parse(encoding: &str) -> Result<Encoding, String> {
    let mut parser = Parser {
        source: encoding.as_bytes(),
        pos: 0,
        whole: encoding,
    };
    let mut types = Vec::new();
    while !parser.at_end() {
        types.push(parser.ty()?);
        parser.skip_digits();
    }
    if types.is_empty() {
        return Err("empty type encoding".into());
    }
    let ret = types.remove(0);
    if types.iter().any(|t| t.kind == Kind::Void) {
        return Err(format!("void argument in type encoding '{encoding}'"));
    }
    Ok(Encoding { ret, args: types })
}

struct Parser<'a> {
    source: &'a [u8],
    pos: usize,
    whole: &'a str,
}

impl Parser<'_> {
    fn at_end(&self) -> bool {
        self.pos >= self.source.len()
    }

    fn peek(&self) -> u8 {
        self.source[self.pos]
    }

    fn skip_digits(&mut self) {
        while !self.at_end() && self.peek().is_ascii_digit() {
            self.pos += 1;
        }
    }

    fn fail(&self, why: &str) -> String {
        format!("cannot parse type encoding '{}' at {}: {why}", self.whole, self.pos)
    }

    fn ty(&mut self) -> Result<Type, String> {
        // Method qualifiers (const, in, out, inout, bycopy, byref, oneway) say nothing
        // about the shape.
        while !self.at_end() && b"rnNoORV".contains(&self.peek()) {
            self.pos += 1;
        }
        if self.at_end() {
            return Err(self.fail("truncated"));
        }
        let c = self.peek();
        self.pos += 1;
        Ok(match c {
            b'@' => {
                if !self.at_end() && self.peek() == b'?' {
                    return Err(self.fail("a block argument is not supported"));
                }
                self.skip_quoted_name();
                Type::of(Kind::Object)
            }
            b'#' => Type::of(Kind::Class),
            b':' => Type::of(Kind::Selector),
            b'*' => Type::of(Kind::CString),
            b'^' => {
                if !self.at_end() && self.peek() == b'v' {
                    self.pos += 1;
                } else {
                    self.ty()?;
                }
                Type::of(Kind::Pointer)
            }
            b'B' => Type::of(Kind::Bool),
            b'c' => Type::of(Kind::Int8),
            b'C' => Type::unsigned(Kind::Int8),
            b's' => Type::of(Kind::Int16),
            b'S' => Type::unsigned(Kind::Int16),
            b'i' => Type::of(Kind::Int32),
            b'I' => Type::unsigned(Kind::Int32),
            b'l' | b'q' => Type::of(Kind::Int64),
            b'L' | b'Q' => Type::unsigned(Kind::Int64),
            b'f' => Type::of(Kind::Float),
            b'd' => Type::of(Kind::Double),
            b'v' => Type::of(Kind::Void),
            b'{' => Type::structure(self.aggregate(b'}')?),
            b'[' => {
                let mut count = 0usize;
                while !self.at_end() && self.peek().is_ascii_digit() {
                    count = count * 10 + (self.peek() - b'0') as usize;
                    self.pos += 1;
                }
                let element = self.ty()?;
                self.expect(b']')?;
                let mut leaves = Vec::new();
                for _ in 0..count {
                    if element.kind == Kind::Struct {
                        leaves.extend_from_slice(&element.leaves);
                    } else {
                        leaves.push(element.kind);
                    }
                }
                Type::structure(leaves)
            }
            b'(' => return Err(self.fail("a union is not supported")),
            b'b' => return Err(self.fail("a bitfield is not supported")),
            b'?' => return Err(self.fail("a function pointer is not supported")),
            other => return Err(self.fail(&format!("unsupported type '{}'", other as char))),
        })
    }

    fn aggregate(&mut self, close: u8) -> Result<Vec<Kind>, String> {
        // {CGRect=...}: the name (possibly "?") runs to '='; a struct in a POINTEE
        // position may have no member list at all ({CGRect}).
        while !self.at_end() && self.peek() != b'=' && self.peek() != close {
            self.pos += 1;
        }
        let mut leaves = Vec::new();
        if !self.at_end() && self.peek() == b'=' {
            self.pos += 1;
            while !self.at_end() && self.peek() != close {
                self.skip_quoted_name();
                let member = self.ty()?;
                match member.kind {
                    Kind::Void => return Err(self.fail("void struct member")),
                    Kind::Struct => leaves.extend(member.leaves),
                    k => leaves.push(k),
                }
            }
        }
        self.expect(close)?;
        Ok(leaves)
    }

    fn skip_quoted_name(&mut self) {
        if !self.at_end() && self.peek() == b'"' {
            let rest = &self.source[self.pos + 1..];
            self.pos = match rest.iter().position(|b| *b == b'"') {
                Some(i) => self.pos + 1 + i + 1,
                None => self.source.len(),
            };
        }
    }

    fn expect(&mut self, c: u8) -> Result<(), String> {
        if self.at_end() || self.peek() != c {
            return Err(self.fail(&format!("expected '{}'", c as char)));
        }
        self.pos += 1;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_rect_flattens_to_four_doubles_and_is_an_hfa() {
        let e = parse("@48@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16").unwrap();
        assert_eq!(e.ret.kind, Kind::Object);
        assert_eq!(e.args.len(), 3);
        assert_eq!(e.args[2].leaves, vec![Kind::Double; 4]);
        assert_eq!(e.args[2].hfa(), Some(Kind::Double));
        assert_eq!(e.args[2].layout().1, 32);
    }

    #[test]
    fn qualifiers_pointers_and_unsigned_kinds() {
        let e = parse("Vv24@0:8^@16").unwrap();
        assert_eq!(e.ret.kind, Kind::Void);
        assert_eq!(e.args[2].kind, Kind::Pointer);
        assert!(parse("Q16@0:8").unwrap().ret.unsigned);
    }

    #[test]
    fn a_block_and_a_union_are_refused_by_name() {
        assert!(
            parse("v24@0:8@?16")
                .unwrap_err()
                .contains("a block argument is not supported")
        );
        assert!(
            parse("v24@0:8(u=iq)16")
                .unwrap_err()
                .contains("a union is not supported")
        );
    }

    #[test]
    fn a_mixed_struct_is_laid_out_with_c_padding() {
        let e = parse("v@:{S=cqi}").unwrap();
        let (offsets, size, align) = e.args[2].layout();
        assert_eq!(offsets, vec![0, 8, 16]);
        assert_eq!((size, align), (24, 8));
        assert_eq!(e.args[2].hfa(), None);
    }
}
