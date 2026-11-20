# k85. Java library: a `Formatter` of its own, and the conversions `String.format` lacks

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from `.todo/k72`.

`String.format`, `formatted` and `printf` are `%java-format` over the argument array
(`.kb/java-frontend.md`, "The library"); `java.util.Formatter` itself is refused by name.

- **`Formatter` as an object**: of nothing (a `StringBuilder`), an `Appendable`, a
  `PrintStream`, a `Locale`; `format` with or without a `Locale`, `out`, `locale`,
  `toString`, `flush`, `close`, `ioException`, the `FormatterClosedException`. What a
  `Formatter` over a `Writer` or a `PrintStream` writes and when is a JDK's answer to probe.
- **The conversions `String.format` lacks**: `%g`, `%G`, `%a`, `%A`, the `%t` / `%T` date and
  time conversions over `java.util.Date`, `Calendar` (refused) and the `java.time` classes,
  `Formattable`. Each refusal's words come from a JDK (`ClojureFormatLoweringTest` holds the
  order of a specifier's refusals, `.kb/java-frontend-provenance.md`, "Formatter's checks").
- Measure what each costs a program that formats only `%d` and `%s` (`sz.sh` of
  `.todo/artefacts/k71-java-front-end-what-k60-left/`): the `%h` arm cost 401 B in every
  program that formats.
