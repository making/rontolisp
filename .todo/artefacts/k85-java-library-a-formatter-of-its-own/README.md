# k85: what was measured (2026-11-21)

Results are in `.kb/java-frontend.md`, "java.util.Formatter and the conversions a format
lacked". Every program here was written here; each expectation is a JDK 25's output.

- `probes/Grid.java` (9,885 lines): `%t` and `%T` of every letter over a long, a `Date` and
  each `java.time` class (and over the wrong classes), the calendar's change and the days of
  1582 under the host's zone, `%g` and `%a` of 75 values under 35 specifiers, every set of
  flags of `g G a A tH TH tq Tc h H s` with and without a width, a precision and an index,
  the nulls and infinities of each conversion. Under `TZ=UTC` the four backends print the
  JDK's lines but the 512 refusals of `%s` (`.todo/k95`); under `America/New_York` the
  long's and the `Date`'s zone is named as `Date.toString()` names it.
- `probes/Appended.java`, `probes/Pieces.java`: a `Formatter` over an `Appendable` that logs
  each append and over one whose every append fails, closed, of a `Locale`, of an
  `OutputStream`; a `Formattable`'s flags, width, precision and formatter. The four backends
  differ from the JDK in the padding (one append) and in a refusal made before anything is
  appended (`deviations.md`).
- `probes/ZoneNames.java`: `%tZ` against a formatter's `z` in `Locale.US` over every zone
  at three instants: 1,812 equal. `probes/HexPadding.java`: the zeros of a width and of a
  precision of `%a`. `probes/DecimalGeneral.java`: `%g` of a `BigDecimal` zero by its scale.
- `sizes/` -- the wasm bytes, by `sz.sh` of `.todo/artefacts/k71-java-front-end-what-k60-left/`.
