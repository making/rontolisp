# nREPL

`rontolisp --nrepl` serves the Clojure [REPL](repl.md) over [nREPL](https://nrepl.org),
the protocol editors (CIDER, Calva, Conjure) and `clj -M -m nrepl.cmdline --connect`
speak. It listens on localhost, on a free port unless `--nrepl=PORT` names one, prints the
line nREPL's own server prints (editors read the port from it), and writes the port to
`.nrepl-port` in the working directory, deleted when the server ends. `deps.edn` aliases
apply as in the REPL (`rontolisp -A:dev --nrepl`).

```console
$ rontolisp --nrepl
nREPL server started on port 41831 on host localhost - nrepl://localhost:41831
$ clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' -M -m nrepl.cmdline --connect --port 41831
user=> (defn g [] 1)
#'user/g
user=> (defn f [] (g))
#'user/f
user=> (defn g [] 2)
#'user/g
user=> (f)
2
```

The server answers the operations of nREPL 1.3's default handler an editor needs, in the
shapes nREPL answers them:

| Operation | Answer |
|---|---|
| `clone`, `close`, `ls-sessions` | the sessions |
| `eval` | a value per form with the namespace then current, `*out*` and `*err*` as `out` and `err` replies; a form that fails reports on `err` and the forms after it run |
| `load-file` | the file's contents loaded: its namespace is the load's own, its last value answers |
| `interrupt` | the running evaluation stops at its next function call or loop step; the session goes on |
| `completions` | the vars, namespaces and special forms a prefix completes, one part per `-` or `.` segment |
| `describe` | the operations and the versions |

The sessions share one runtime, as nREPL's do: what one session defines, another calls.
Each keeps its own `*ns*`, `*1`, `*2`, `*3`, `*e` and the flags `set!` changes
(`*print-length*`, `*warn-on-reflection*`).

Not answered: `stdin` (an evaluation reading `*in*` reads its end), `lookup`, the
middleware operations and cider-nrepl's, so an editor works through its fallbacks. A
report's place names no `eval` class (`at (REPL:1)` where nREPL writes
`at user/eval1234 (REPL:1)`).
