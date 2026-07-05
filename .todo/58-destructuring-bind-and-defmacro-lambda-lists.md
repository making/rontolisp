# 58: Phase 3 unit 4 -- destructuring-bind + full defmacro lambda lists

Part of the ASDF Phase 3 split (see `.todo/54-asdf-support.md`, "Phase 3"
section). Wishlist sources: `.todo/44-defmacro-followups.md` ("defmacro
lambda lists" section) and `.todo/31-lambda-list-extensions.md`. These two
belong in one session because they share the destructuring machinery.

## Scope

- `destructuring-bind`: `(destructuring-bind pattern form body...)` with
  nested patterns and `&optional`/`&rest`/`&body`/`&key` inside the pattern.
  Expansion-level: lower to a `let*` of car/cdr chains. Note
  `LoopExpander.destructure` already walks plain nested patterns -- lift it
  into a shared helper in `LispMacroExpander` and extend with the lambda-list
  keywords (loop's `for (a b) in ...` then reuses the shared walker, as
  anticipated in `.todo/29-loop-macro-followups.md`).
- `defmacro` lambda lists beyond required + one `&rest`/`&body`:
  destructuring parameter lists, `&optional` (with defaults), `&key`.
  `&whole` and `&environment` may remain errors (document). Both consumers
  must agree: the interpreter's `expandUserMacro` and the compile path's
  `UserMacroExpander` macro-time evaluator bind macro parameters the same way
  -- route both through the shared destructuring helper applied to the raw
  argument form list.
- Dotted patterns stay unsupported (reader has no dotted-pair syntax --
  documented limitation, same as loop's).

## Wiring checklist

`destructuring-bind` is a new macro: names/registry/expandBuiltinMacro,
evaluator + three compilers + FreeVarAnalyzer, list-macros/introspection
expectation updates, ci-spec case, docs (en+ja, macros/) + catalog, `.kb`
note (extend `.kb/defmacro-backquote.md`), native E2E. The defmacro side has
no new operator -- update the defmacro reference page's limitations section
and `.todo/44`.
