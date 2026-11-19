# k33: where the bytes of a YAVI program go, measured 2026-11-20

`am.ik.yavi:yavi:0.18.0` from Maven Central, compiled by the exec jar of `8cbfcca13`.
Nothing of YAVI is in this directory: `tag/Tag.java` is written here over its public API,
and `trim.py` holds regular expressions that cut a COPY of one of its files. The
conclusions are in `.kb/java-frontend.md`, "YAVI compiled to wasm".

## How to run it again

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
HERE=.todo/artefacts/k33-yavi-compiles-to-wasm-the-milestone-and-what-stands-before-it
DEP="--java-source-dep am.ik.yavi:yavi:0.18.0"
OUT=/tmp/k33
# the two examples and the smallest schema, each with every function's size
$HERE/measure.sh $JAR $OUT member examples/java yavi/Member.java $DEP
$HERE/measure.sh $JAR $OUT booking examples/java yavi/Booking.java $DEP
$HERE/measure.sh $JAR $OUT tag-full $HERE tag/Tag.java $DEP
python3 $HERE/by-owner.py $OUT/booking.sizes $OUT/member.sizes
# the smallest schema over a copy of YAVI whose reader of an untyped input is cut
unzip -q ~/.m2/repository/am/ik/yavi/yavi/0.18.0/yavi-0.18.0-sources.jar -d $OUT/yavi-a
python3 $HERE/trim.py $OUT/yavi-a/am/ik/yavi/core/UntypedInput.java a   # or b, c
$HERE/measure.sh $JAR $OUT tag-a $HERE tag/Tag.java --java-source-path $OUT/yavi-a
```

`measure.sh` needs `wasmtime` and `wasm-objdump` (wabt); two compiles of one program
differ by some tens of bytes (`.todo/k41`).

## Code bytes by owner (functions)

| | `Member` | `Booking` | `Tag` | `Tag`, cut a | `Tag`, cut b |
|---|---|---|---|---|---|
| the file | 662,980 | 1,280,340 | 1,216,741 | 951,244 | 889,037 |
| code section | 509,414 | 1,036,486 | 985,996 | 747,294 | 688,799 |
| data section | 146,506 | 230,571 | 218,225 | 193,581 | 190,272 |
| YAVI | 47,762 (208) | 186,124 (834) | 161,807 (733) | 156,436 (712) | 153,193 (711) |
| the regex engine | 130,631 (150) | 132,255 (152) | 132,252 (152) | 130,669 (149) | 130,487 (149) |
| `java.util` | 54,622 (224) | 92,155 (371) | 90,034 (365) | 81,906 (346) | 80,705 (346) |
| `java.time` | 0 | 97,088 (193) | 95,831 (189) | 0 | 0 |
| `java.math` | 0 | 30,171 (60) | 30,128 (59) | 29,655 (59) | 0 |
| `java.text` | 17,313 (15) | 18,620 (15) | 18,656 (15) | 18,361 (15) | 17,304 (14) |
| `java.net` and the URI runtime | 0 | 29,554 (31) | 29,553 (31) | 0 | 0 |
| `java.lang` and the library's rest | 11,973 (62) | 27,506 (150) | 26,863 (146) | 20,911 (121) | 20,051 (114) |
| lambdas | 46,861 (90) | 55,945 (217) | 53,944 (198) | 51,000 (161) | 50,880 (156) |
| dispatch (`_dispatch_*`) | 16,151 (3) | 92,578 (6) | 81,084 (5) | 29,774 (4) | 28,835 (4) |
| of it `_dispatch_spread` | 0 | 53,761 | 49,771 | 0 | 0 |
| the top level | 3,520 | 29,477 | 28,329 | 8,677 | 6,811 |
| the program | 3,512 (12) | 4,579 (15) | 2,270 (10) | 2,249 (10) | 2,230 (10) |
| the runtime's rest | 175,393 (345) | 236,870 (451) | 231,886 (439) | 214,792 (420) | 195,605 (388) |
| all functions | 507,738 (1,110) | 1,032,922 (2,496) | 982,637 (2,343) | 744,430 (1,998) | 686,101 (1,893) |

- Cut c (the `URI` entry alone): `Tag` 1,107,926 B, 108,815 less: `java.net` and the URI
  runtime 29,553 B of code, dispatch 81,084 -> 32,728, the top level 28,329 -> 22,951, the
  data section 218,225 -> 205,169.
- The k24 programs at this commit: the schema `Book.java` 1,216,108 B, the classic one
  655,716 B. The classic one over a copy of YAVI without `CharSequenceConstraint`'s three
  static patterns: 654,779 B, the regex engine still there (`VariantOptions`' `replaceAll`).
- `Booking`: the component 1,284,722 B, the jar 464,923 B; on the exec jar the interpreter
  7.5 s to its last line, `-o Booking.class` 13.3 s, `-o booking.wasm` 15.3 s,
  `--component` 15.6 s.
- One class of the library alone (a `main` printing the call's result):
  `LocalDate.parse` 154,335 B, `Instant.parse` 171,325, `Duration.parse` 162,358,
  `ZonedDateTime.parse` 191,845, `Year.parse` 132,933, `Period.parse` 153,701,
  `UUID.fromString` 110,951, `URI.create` 150,838 (the only one with `_dispatch_spread`,
  3,165 B), `new BigDecimal(a).intValueExact()` 189,388, `new Timestamp(0).toInstant()`
  212,676.
