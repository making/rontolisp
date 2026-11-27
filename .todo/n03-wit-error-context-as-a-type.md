# n03. WIT: `error-context` as a type

Difficulty: Medium

`error-context` is a WIT built-in type (the component model's async error value), and the
WIT reader treats it as an undefined name. Measured 2026-11-27: an interface naming it
anywhere is refused whole, even when no bound member takes it (`@WitImport: q.wit:10:
'ctx': the WIT type of parameter 'e' is 'error-context', which the file does not define
(nor import with a use clause)`), and a world's export at its line (`holds
'error-context', a type the file does not define`). So a WASI 0.3 WIT that mentions it
cannot be imported unedited, by any front end.

- `am.ik.wit`: parse it as a primitive-like type (a handle in the canonical ABI, like a
  stream's or a future's end), printed back as written (`WitRoundTripTest`).
- `WitTypeMapper`: decide its Lisp value first (an opaque handle; `error-context.debug-message`
  and `.drop` as built-ins of the binding, as `-read` / `-drop-readable` are for a stream).
- At least: a member that does not take it binds, the WIT left as published.
- Java (`JavaWitTypes`): a type of its own in `rontolisp-java-api`, or refused at the member
  with the reason; `doc/{en,ja}/java/wit.md` ("The types", "Where each type crosses") in the
  same commit.
