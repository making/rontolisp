# e52. Clojure examples for the deps.edn, library and boundary work

Difficulty: Medium

`examples/clojure/` shows the core language, Ring and the host boundary, but none of the
recent Clojure work: a deps.edn project, `.cljc`, the bundled namespaces, the HTTP client,
macros calling program functions, or WIT rich types. Each needs a runnable example checked in
`examples/examples.yaml`. Start only after the todos it covers have landed (check
`.todo/history/`); a theme whose todo is still open is left out and noted for a follow-up.

## Themes

- deps.edn project (e37, e38): a `:local/root` dependency, an alias, `-M -m` an entry
  namespace, `-X` a function, `rontolisp test`. Maven and git coordinates (e34, e36, e39,
  e47, e48) need the network: a harness leg only if the suite already has an offline
  fixture for them (a `file://` repository), otherwise documented commands only.
- Java libraries on the run-time class path (e35).
- `.cljc` and reader conditionals, including `{:read-cond :preserve}` (e40, e45).
- `ns` clauses and `require` options (e41).
- Bundled namespaces: `clojure.walk`, `clojure.edn`, `clojure.template` and the rest e42
  adds.
- HTTP client over `rontolisp:fetch` (e44): a leg that needs no external host, or a
  compile-only leg as `ring-hello.clj` does.
- Macro bodies calling the program's functions (e46).
- WIT rich types (e50): extend `greeter/` or add a world beside it.

## Plan

1. Per theme, one program (or one project directory) that runs on every backend the feature
   supports, with an `.expected` file; legs follow the existing `clojure/*` entries.
2. Update `examples/clojure/README.md` (table and commands) and the Clojure doc pages that
   list examples, en and ja together.
3. Run the examples suite for the new entries only (`-Drontolisp.examples.only=`,
   `.kb/running-backends.md`).
