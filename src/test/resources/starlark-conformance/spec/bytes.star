# bytes: the examples of spec.md ("Bytes literals", "Bytes", "bytes", "bytes·elems",
# "hash", "str", "Membership tests"), and the cases around them

assert_eq(type(b"abc"), "bytes")
assert_eq(b"abc", b'abc')
assert_eq(b"""abc""", b'''abc''')
assert_eq(br"a\nb", rb"a\nb")
assert_eq(len(br"a\nb"), 4)
assert_eq(len(b"abc"), 3)
assert_eq(b"😃", b"\xf0\x9f\x98\x83")
assert_eq(b"é\U0001F603", bytes("é😃"))
assert_eq(b"\377\000", b"\xff\x00")
assert_eq(bytes("hello 😃"), b"hello 😃")
assert_eq(bytes(b"hello 😃"), b"hello 😃")
assert_eq(bytes([65, 66, 67]), b"ABC")
assert_eq(bytes(range(3)), b"\x00\x01\x02")
assert_eq(len(bytes("😃")), 4)
assert_eq(b"ab" + b"cd", b"abcd")
assert_eq(b"ab" * 2, b"abab")
assert_eq(2 * b"ab", b"abab")
assert_eq(b"ab" * -1, b"")
assert_eq(b"abc"[0], 97)
assert_eq(b"abc"[-1], 99)
assert_eq(b"\xff"[0], 255)
assert_eq(b"abcd"[1:3], b"bc")
assert_eq(b"abcd"[::-1], b"dcba")
assert_eq(b"abcd"[::2], b"ac")
assert_(b"nasty" in b"dynasty")
assert_(97 in b"abc")
assert_(98 not in b"ac")
assert_(b"" in b"abc")
assert_(b"x" not in b"abc")
assert_(b"abc")
assert_(not b"")
assert_(b"a" < b"b")
assert_(b"ab" < b"abc")
assert_(b"\x7f" < b"\x80")
assert_(b"\xff" > b"\x00\xff")
assert_eq(sorted([b"b", b"a", b""]), [b"", b"a", b"b"])
assert_eq({b"k": 1}[b"k"], 1)
assert_eq(len({b"k": 1, "k": 2}), 2)
assert_ne(b"abc", "abc")
assert_eq(str(b"abc"), "abc")
assert_eq(str(b"hello 😃"), "hello 😃")
assert_eq(str(b"a\xffb\xed\xa0\x80"), "a�b���")
assert_eq(repr(b"abc"), 'b"abc"')
assert_eq(repr(b"hello 😃"), 'b"hello 😃"')
assert_eq(repr(b"a\xffb\x00\"\\\n"), 'b"a\\xffb\\x00\\"\\\\\\n"')
assert_eq(str([b"a"]), '[b"a"]')
assert_eq(hash(b""), 2166136261)
assert_eq(hash(b"a"), 3826002220)
assert_eq(type(b"ABC".elems()), "bytes.elems")
assert_eq(repr(b"ABC".elems()), 'b"ABC".elems()')
assert_eq(list(b"ABC".elems()), [65, 66, 67])
assert_eq([x for x in b"\xff".elems()], [255])
assert_eq(dir(b""), ["elems"])
---
bytes(65) ### got int, want string, bytes, or iterable of int
---
bytes([256]) ### out of range
---
bytes(["a"]) ### got string, want int
---
list(b"abc") ### got bytes, want iterable
---
def f():
  for x in b"abc":
    pass
f() ### not iterable
---
256 in b"abc" ### out of range
---
"a" in b"abc" ### requires bytes or int as left operand
---
b"abc"[3] ### out of range
---
b"a" + "a" ### unknown binary op
---
b"a" < "a" ### unsupported comparison
---
b"abc".decode() ### has no .decode field
---
hash(1) ### want string or bytes
