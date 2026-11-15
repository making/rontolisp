# Deviations

Conformance is partial by design. Where behavior departs from the
[specification](https://github.com/bazelbuild/starlark/blob/master/spec.md), it does so
here, on every backend alike.

## Where behavior departs

**A string is a sequence of code points.** `len`, indexing and slicing count Unicode code
points; the specification's two string models count UTF-8 bytes or UTF-16 units. `hash` is
the specification's number all the same, and `bytes` of a string is the UTF-8 encoding of
its code points: no string holds part of a character.

```starlark
print(len("𝄞"), "naïve"[2], "日本語"[::-1], hash("𝄞"))
```

```
1 ï 語本日 1772394
```

**`print` writes to standard output.** The specification leaves the destination to the
host, and Bazel's is standard error.

**The dialect is permissive by default.** `if` and `for` may stand at the top level, a
global may be assigned more than once, and a function may call itself; Go's implementation
has each as an option. `--starlark-standard spec` makes all three errors, as the
specification does: the first two before the program starts, a call while a call of the
same `def` or `lambda` runs when it happens (`function f called recursively`).

```console
$ cat count.star
for x in range(2):
    print(x)
$ rontolisp count.star
0
1
$ rontolisp --starlark-standard spec count.star
error: count.star:1:1: for loop not within a function
```

**Freezing follows when a value was made, not what reaches it.** The specification freezes
what a file's globals reach when the file has run; here every list, dict and set made while
the file ran is frozen, also one no global holds. A frozen list is a dict key, as the
specification says; a frozen dict or set is not. (The Go and Java implementations refuse
all three.)

The rest, in short:

- A slice of a range answers a list, and a range prints with its start: `range(0, 5)`.
- `isalpha`, `isdigit`, `isalnum` and `isspace` test ASCII characters only.
- An error ends the program: `Unhandled condition: ` and the message on standard error,
  then the file and line it happened on and the `def` holding it (on WebAssembly with
  [`--report-locations`](../compiling/wasm.md#uncaught-condition-locations---report-locations)),
  and a non-zero exit status. Only an error found before the program starts names the
  column. The texts follow the Go implementation's.
- A list, tuple or dict expression of several thousand elements does not compile to a JVM
  class unless every element is a constant (a number, a string, `None`, `True`, `False`,
  or such an expression of constants): it passes the size a JVM method may have. The
  interpreter and WebAssembly take it.
- An error report names a module's function behind the module's path:
  `in sl%(words.star)shout`.
- A module that stopped at an error is not run again by a later `load` of it.
- At the REPL, a name an input uses is defined by that input or an earlier one, inside a
  `def` too. A function keeps the built-in a later input's global of the same name hides,
  and calls the new value of a global defined again. Nothing made at the REPL is frozen.
