# A by-value struct return is outside the native binary's shape grid, and the CFFI guide says otherwise

Difficulty: Medium

Reported 2026-09-02 from `doc/{en,ja}/guides/cffi.md`'s own "Structures,
including by value" example, run on the installed native binary:

```console
$ rontolisp
CL-USER> (ql:quickload :cffi)
CL-USER> (cffi:defcstruct div-t (quot :int) (rem :int))
CL-USER> (cffi:defcfun ("div" c-div) (:struct div-t) (numer :int) (denom :int))
CL-USER> (c-div 17 5)
Error: cffi: calling div: ffi:%apply-call: this binary has no foreign-call stub
for the shape struct(jint,jint)(jint,jint) -- ... add {"returnType":
"struct(jint,jint)", "parameterTypes": ["jint", "jint"], ...} in
foreign.downcalls of META-INF/native-image/am.ik.rontolisp/rontolisp/
reachability-metadata.json and rebuild the binary, or run the program on
java -jar, where any shape binds
```

The guide prints `(QUOT 3 REM 2)` for that call and says by value "works with
nothing extra installed". On `java -jar` it does. On the shipped binary --
which is what `rontolisp` is for most readers, and what the guide's `$ rontolisp`
prompt shows -- it does not. `eval/FfiTest` covers `div` on the JVM, so no test
sees this.

The diagnostic itself did its job: it named the exact metadata entry and the
`java -jar` escape. This item is about the two things it could not fix.

## What the grid actually holds

`.kb/ffi.md` ("The carriers are canonicalised") states the rule: a by-value
struct keeps its EXACT layout and is therefore outside the canonicalisation --
the member list is part of the shape. The shipped grid
(`src/test/java/am/ik/rontolisp/NativeImageDowncalls.java` generates it,
`FfiNativeImageForeignConfigTest` pins it) has 1,235 downcall entries whose
return carriers are:

| return carrier | entries |
| --- | --- |
| `void` / `void*` / `jlong` / `jdouble` / `jfloat` | 1,198 |
| `struct(jdouble,jdouble{,jdouble,jdouble})` | 6 |
| `struct(jlong,jlong)` | 4 |
| `struct(void*,void*)` | 1 |
| `jint`, `jboolean` | 26 |

Every struct entry there is a bundled consumer's own shape (`am.ik.gpu`,
`am.ik.objc`), and every one takes `void*` parameters. Not one has scalar
parameters, and `struct(jint,jint)` -- the shape of `div`, the most ordinary
by-value struct in C -- is absent. So the reachable claim is narrower than the
guide's: a `defcfun` returning a struct by value works in the binary only if
the shape happens to coincide with a shape rontolisp itself calls.

## The two halves, and the measurement each needs

**1. The documentation is wrong as written.** `doc/en/guides/cffi.md` and its
`doc/ja` twin promise the binary something only `java -jar` delivers. That half
is unconditional and must land whatever is decided about the grid: state which
by-value struct shapes the binary carries, and that the rest need `java -jar`
or a rebuild. Check the same guide's other `$ rontolisp` transcripts for the
same overclaim (`defcallback`, varargs, and the narrow-integer-past-the-sixth
case are all documented grid exclusions in `.kb/ffi.md` -- do they appear in the
guide as if they worked?).

**2. Whether a bounded struct family belongs in the grid.** The scalar grid is
finite because the carriers canonicalise. A struct's SysV/AAPCS64 class depends
on its members' types and offsets, so members cannot be widened the way
arguments are -- but the FAMILY can still be bounded, e.g. every member
sequence of length 1-2 (or 1-4) drawn from the four carriers, as a return type,
over the parameter shapes already registered. Before writing any of it,
MEASURE:

- the binary's size and build time now, and with the family added at each bound
  (1-2 members, 1-3, 1-4). Each entry is a compiled stub; the grid is already
  1,235 and the item that added it recorded what that cost.
- how many of those shapes are reachable in practice. `div`'s
  `struct(jint,jint)` is the canonical one; the C library surface a CFFI
  binding in Quicklisp actually returns by value is the evidence, not a guess
  -- grep the bundled/quicklisp `defcfun` forms for `(:struct` return types.

If the numbers say a bounded family is not worth its size, that is the result:
record the numbers in `.kb/ffi.md` beside the canonicalisation rule and let the
documentation half stand alone. Do not force the family through.

## Related

- `.todo/476` -- the other standing FFM item (non-constant `MethodHandle`);
  independent of this one.
