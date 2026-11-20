# k51. Java front end: `java.time`, what the regions left

Difficulty: Medium

**Read `.kb/java-frontend.md`, "java.time: the regions", first**: k42 (2026-11-20) gave a
program the regions of the tz database it names, with the numbers below. The walk and the
sizes are in `.todo/artefacts/k42-java-time-the-rules-of-a-region/`.

- **A program that reads zoned texts and names no zone holds no region**: `ZonedDateTime.parse`
  brings none, since YAVI 0.18.0 reaches it in every schema and a table brought by it
  would cost `Booking` 47 KB for a date it never reads. The way to hold all of them is a
  call of `ZoneId.getAvailableZoneIds()` somewhere in the source. To decide: an option of
  the compile (`CompileFrontend`, the Maven plugin's `wasm` goal, the playground) that
  holds all, or the regions it lists; and whether a `parse` written in the program's own
  files -- not in a library of its source path -- should bring all once YAVI's split is
  released (`.todo/k50`).
- **A tighter table for a program holding all**: 46,661 B today, 40,825 of them the rules
  of 329 sets. gzip makes some 15 KB of the rules, so a copy of an earlier set's runs
  (the regions of one country share most of theirs) could save about 25 KB. It costs a
  reader that follows the copies and a table `JavaZones.table` can no longer cut by
  name without resolving them. Measure the reader's bytes against the saving.
- **The reader is 18 KB of the 50 KB the first region costs** (`ZoneChanges.region`,
  `rule`, `number`, `find`, `written`: every `int` and `long` operation is masked to its
  width). A region named by a constant could come as arrays the compiler wrote, with no
  reader in the program: a second reader in `JavaZones` to keep equal to the library's,
  and some 3 KB of literals for a region of 180 changes against 330 B of text. Measure.
- **Not there**: `ZoneRulesProvider` (`getAvailableZoneIds`, `getRules`, `getVersions`),
  `ZoneId.SHORT_IDS` and `ZoneId.of(String, Map)`.
- **`ZoneId.systemDefault()` is a region only where `TZ` names one the program holds.** A
  JDK reads the host's zone without `TZ` (the link `/etc/localtime` is); the interpreter
  and the JVM could answer the host's name, wasm cannot.
- **Speed**: an offset asked in a year of the yearly rules makes a `LocalDate` for each
  rule of three years (`ZoneChanges.ruled`, `Yearly.at`). The walk of every region takes
  29 s on wasm and 15 s on the JVM against a JDK's 3.2 s; most of it is the lines'
  strings. Measure a loop of `ZonedDateTime.now(zone)` before caching a year's changes.
