# k67. Java: a static field is read through an unbound test

Difficulty: Low

Found by k56 (2026-11-20). A Java static field is `(defvar |C.f|)` with no value, set by the
class's initialization; every read of it on wasm tests the module global against the
UNBOUND marker and calls the `unbound-variable` report (`.kb/dynamic-special-variables.md`),
some 15 B a read. A static has its default value before its initializer runs (JLS 12.4),
so no read of one can find it unbound.

- **Measured** (k56's build): 60 such tests in a program holding `Europe/Paris`
  (`ZonedDateTime.of(.., ZoneId.of("Europe/Paris")).plusHours(1)`, 173,608 B of wasm).
- **To decide**: the default value given where the variable is defined, or a declaration
  the backends read that the global is always bound; what a program reading a static of a
  class not yet initialized sees on each backend (`.kb/java-frontend.md`, "Static
  initialization").
