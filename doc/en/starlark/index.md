# Starlark (experimental)

**Experimental.** rontolisp reads
[Starlark](https://github.com/bazelbuild/starlark/blob/master/spec.md), the Python dialect
Bazel's build files are written in, and lowers it to the same core forms as Common Lisp, so
a `.star` program runs on every backend. Conformance is partial by design and nothing here
is a compatibility promise. Use it to try a Starlark program on the JVM or WebAssembly;
write Common Lisp for anything you need to keep working.

A `.star` file is read as Starlark; `--source-language starlark` says so for any other
file and for `-e`. The language is picked per file. `--starlark-standard spec` applies the
specification's rules on the top level and recursion, which the default relaxes
([Deviations](deviations.md)).

```bash
rontolisp hello.star                                # interpreter
rontolisp hello.star -o Hello.class && java Hello   # JVM
rontolisp hello.star -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.star -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp -e 'print(1 + 2)' --source-language starlark
```

`--no-gc` is refused: that backend has no structures, hash tables or closures. There is no
REPL yet, so `--source-language starlark` without a program is refused, and the examples
on these pages do not run in the browser.

[`examples/starlark/`](https://github.com/making/rontolisp/tree/develop/examples/starlark)
holds a runnable program with its expected output.

```starlark
def fib(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a

def describe(name, *args, sep = ", ", **kwargs):
    parts = [str(a) for a in args] + ["%s=%r" % (k, v) for k, v in kwargs.items()]
    return "{}({})".format(name, sep.join(parts))

print([fib(i) for i in range(10)], fib(100))
print(describe("f", 1, 2.5, None, x = "a", y = [1, (2,)]))
words = "the quick brown fox jumps over the lazy dog".split()
print(sorted(words, key = len, reverse = True)[:3], {w: len(w) for w in words if len(w) > 4})
```

```
[0, 1, 1, 2, 3, 5, 8, 13, 21, 34] 354224848179261915075
f(1, 2.5, None, x="a", y=[1, (2,)])
["quick", "brown", "jumps"] {"quick": 5, "brown": 5, "jumps": 5}
```

## What is read

The whole grammar of the specification:

- `def` with default values, `*args`, keyword-only parameters and `**kwargs`; `lambda`;
  nested functions that read the variables around them.
- `if` / `elif` / `else`, `for` with `break` and `continue`, `return`, `pass`.
- Assignment to a name, to an element (`a[i] = v`) and to a tuple or list of targets, and
  the augmented forms (`+=`, `//=`, `<<=`, ...).
- List and dict comprehensions, conditional expressions, indexing and slicing, `and` /
  `or` / `not`, the comparisons, `in`, the arithmetic and bitwise operators, and `%` on a
  string.
- `load`, for another Starlark file ([Modules](#modules)) and for the functions of a Common
  Lisp package ([Mixing with Common Lisp](common-lisp.md)).

The values are `None`, `True` and `False`, integers of any size, floats, strings, lists,
tuples, dicts, ranges and functions.

## Built-in functions

| Function | Answers |
|---|---|
| `abs(x)` | the absolute value of an int or a float |
| `all(x)`, `any(x)` | whether every element, or any, is true |
| `bool(x=False)` | the truth of `x` |
| `dict(pairs, **kwargs)` | a new dict: the entries of a dict or of an iterable of pairs, then the named arguments |
| `dir(x)` | a new sorted list of the names of the methods of `x` |
| `enumerate(x, start=0)` | a list of `(index, element)` tuples |
| `fail(*args, sep=" ")` | nothing: it ends the program with the arguments as the error |
| `float(x=0)` | a float from an int, a float, a bool or a string (`"1e3"`, `"inf"`, `"nan"`) |
| `getattr(x, name, default)` | the method `name` of `x` bound to it, else `default`, else an error |
| `hasattr(x, name)` | whether `x` has the method `name` |
| `hash(x)` | the hash of a string |
| `int(x, base)` | an int from an int, a float, a bool, or a string in base 2 to 36 (`base=0` reads a `0x`, `0o` or `0b` prefix) |
| `len(x)` | the number of elements of a string, list, tuple, dict or range |
| `list(x)`, `tuple(x)` | a new list, or a tuple, of the elements of an iterable |
| `max(*args, key=None)`, `min(*args, key=None)` | the greatest, or least, of the arguments or of the one iterable |
| `print(*args, sep=" ")` | `None`, after writing the `str` of the arguments and a newline to standard output |
| `range(stop)`, `range(start, stop, step=1)` | a range of integers |
| `repr(x)`, `str(x)` | `x` as a string; `repr` writes a string between double quotes |
| `reversed(x)` | a new list in reverse order |
| `sorted(x, key=None, reverse=False)` | a new sorted list; equal elements keep their order |
| `type(x)` | the name of the type of `x` |
| `zip(*iterables)` | a list of tuples, as long as the shortest iterable |

## Methods

| Type | Methods |
|---|---|
| list | `append`, `clear`, `extend`, `index`, `insert`, `pop`, `remove` |
| dict | `clear`, `get`, `items`, `keys`, `pop`, `popitem`, `setdefault`, `update`, `values` |
| string | `capitalize`, `codepoint_ords`, `codepoints`, `count`, `elem_ords`, `elems`, `endswith`, `find`, `format`, `index`, `isalnum`, `isalpha`, `isdigit`, `islower`, `isspace`, `istitle`, `isupper`, `join`, `lower`, `lstrip`, `partition`, `removeprefix`, `removesuffix`, `replace`, `rfind`, `rindex`, `rpartition`, `rsplit`, `rstrip`, `split`, `splitlines`, `startswith`, `strip`, `title`, `upper` |

A method is called on its receiver, or taken as a value bound to it:

```starlark
log = []
push = log.append
push("a")
print(push, log, "banana".count("an"), {"k": 1}.get("missing", 0))
```

```
<built-in method append of list value> ["a"] 2 0
```

A list or a dict is not changed while a loop runs over it. Like every error, the attempt
ends the program:

```console
$ cat loop.star
xs = [1, 2]
for x in xs:
    xs.append(x)
$ rontolisp loop.star
Unhandled condition: cannot append to list during iteration
  at loop.star:3
```

A variable read before it is assigned is an error the same way, a local or a global, and
so is a call of a `def` written above it.

## Modules

`load("words.star", "shout", count = "COUNT")` runs another file and binds names of the
loading file to its globals: `shout` to the module's `shout`, `count` to its `COUNT`. The
path is relative to the file holding the statement.

- A module runs once per program, where the first `load` of it stands, however many files
  load it.
- Its globals are its own: two files may both define `helper`.
- A name starting with an underscore cannot be loaded, and neither can a name the module
  loaded itself.
- A file that is not there, a name the module does not define and a file loading the one
  that loads it are errors before the program starts.
- A module is read when the program is compiled, so a compiled program carries it.

**When a file has run, the lists and dicts made while it ran are frozen**: changing one is
an error from then on. A list or dict that a function of the file makes when it is called
later belongs to the caller. A frozen list is a dict key.

```console
$ cat words.star
print("words runs")
seen = ["start"]
def shout(s):
    return s.upper() + "!"
$ cat main.star
load("words.star", "shout", "seen")
print(shout("hello"), seen, {seen: 1})
seen.append("more")
$ rontolisp main.star
words runs
HELLO! ["start"] {["start"]: 1}
Unhandled condition: cannot append to frozen list
  at main.star:3
```

## This section

- [Mixing with Common Lisp](common-lisp.md) -- what a Common Lisp file and a Starlark file
  of one program see of each other.
- [Deviations](deviations.md) -- where this front end departs from the specification,
  then what is not there yet.
