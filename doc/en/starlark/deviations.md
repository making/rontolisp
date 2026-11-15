# Deviations

Conformance is partial by design. Where behavior departs from the
[specification](https://github.com/bazelbuild/starlark/blob/master/spec.md), it does so
here, on every backend alike.

## Where behavior departs

**A string is a sequence of code points.** `len`, indexing and slicing count Unicode code
points; the specification's two string models count UTF-8 bytes or UTF-16 units. `hash` is
the specification's number all the same.

```starlark
print(len("𝄞"), "naïve"[2], "日本語"[::-1], hash("𝄞"))
```

```
1 ï 語本日 1772394
```

**`print` writes to standard output.** The specification leaves the destination to the
host, and Bazel's is standard error.

**The dialect is the permissive one.** `if` and `for` may stand at the top level, a global
may be assigned more than once, and a function may call itself. Bazel refuses all three;
Go's implementation has each as an option.

**A top-level function can be called above its `def`.** The specification makes that an
error until the `def` statement has run.

```starlark
print(double(21))

def double(x):
    return x * 2
```

```
42
```

**A variable read before it is assigned is not always an error.** A local variable reads
`None`. A global is an error, with Common Lisp's text (`The variable y is unbound`).

The rest, in short:

- A slice of a range answers a list, and a range prints with its start: `range(0, 5)`.
- `isalpha`, `isdigit`, `isalnum` and `isspace` test ASCII characters only.
- An error ends the program: `Unhandled condition: ` and the message on standard error,
  and a non-zero exit status. The message names no file or line. The texts follow the Go
  implementation's.
- A list, tuple or dict expression of several thousand elements does not compile to a JVM
  class: it passes the size a JVM method may have. The interpreter and WebAssembly take
  it.

## Not there yet

- `load`, and a `b"..."` literal: each is refused at its position before the program
  starts.
- The `bytes` and `set` types and the built-ins that make them.
- Freezing: no value becomes immutable when a file has run.
- The REPL, the playground's language pick and Run cells on these pages.
- Calls between a Starlark file and a Common Lisp file of one program: what each side
  sees of the other is not settled.
