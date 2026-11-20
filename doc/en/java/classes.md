# The JDK classes

The [subset of the Java language](index.md) has a part of the JDK's classes, each with the
members the table below lists and no others; a missing class or member is refused by name.
`String`, `StringBuilder`, the boxes, `Math`, `System` and `IO` are part of the front end.
Every other class is written in the subset itself and compiled with the program as far as
the program reaches it, so a class of the program can extend or implement one
(`AbstractList`, `Iterable`, `Comparator`). A third-party library is none of these: it is
read as source from the program's source path ([Using a third-party
library](index.md#using-a-third-party-library)).

| Class | Members |
|---|---|
| `Object` | `new Object()`, `toString`, `equals`, `hashCode`, `getClass()`, `wait()`, `wait(long)`, `notify`, `notifyAll` |
| `Class` | a class literal (`Name.class`, `int.class`, `String[].class`, `void.class`) and `getClass()`; `getName`, `getSimpleName`, `getCanonicalName`, `getTypeName`, `getPackageName`, `getSuperclass`, `getComponentType`, `isInstance`, `cast`, `isAssignableFrom`, `isInterface`, `isArray`, `isPrimitive`, `isEnum`, `isRecord`, `getEnumConstants`, `toString`, `equals`, `hashCode`; `getResourceAsStream`, `getResource` and `getClassLoader` ([Resources](index.md#resources)) |
| `ClassLoader` | `getSystemClassLoader`, `getPlatformClassLoader`, `getParent`, `getResourceAsStream`, `getResource`, `getResources`, `getSystemResourceAsStream`, `getSystemResource`, `getSystemResources` ([Resources](index.md#resources)) |
| An enum | `values()`, `valueOf(String)`, `Enum.valueOf(Class, String)`, `name`, `ordinal`, `compareTo`, `getDeclaringClass`, `toString` |
| A record | the accessors, `equals`, `hashCode`, `toString` |
| An array | `length`, `clone()` |
| `String` | `length`, `charAt`, `isEmpty`, `isBlank`, `equals`, `equalsIgnoreCase`, `compareTo`, `compareToIgnoreCase`, `hashCode`, `substring`, `subSequence`, `indexOf` and `lastIndexOf` of a string or a character with or without a start, `indexOf` within a range, `codePointAt`, `codePointBefore`, `codePointCount`, `offsetByCodePoints`, `regionMatches`, `contentEquals`, `getChars`, `indent`, `contains`, `startsWith`, `endsWith`, `toUpperCase`, `toLowerCase`, `trim`, `strip`, `stripLeading`, `stripTrailing`, `repeat`, `replace`, `replaceAll`, `replaceFirst`, `matches`, `concat`, `toCharArray`, `split`, `intern`, `formatted`, `getBytes` of nothing, a `Charset` or a charset's name, `toUpperCase` and `toLowerCase` of a `Locale`; `String.valueOf`, `String.copyValueOf`, `String.join`, `String.format` with or without a `Locale`, `String.CASE_INSENSITIVE_ORDER`; `new String()`, `new String(String)`, `new String(char[])`, `new String(char[], int, int)`, `new String(byte[])` with or without a range and a `Charset` or a charset's name, `chars`, `codePoints`, `lines` |
| `StringBuilder`, `StringBuffer` | the constructors of nothing, a capacity or a text; `append` of a value or of a range of a text or of a `char[]`, `insert`, `reverse`, `setLength`, `setCharAt`, `deleteCharAt`, `delete`, `replace`, `indexOf` and `lastIndexOf` with or without a start, `appendCodePoint`, `repeat` of a text or a code point, `compareTo`, `toString`, `length`, `isEmpty`, `charAt`, `substring`, `subSequence`, `chars`, `codePoints` |
| `CharSequence` | `length`, `charAt`, `subSequence`, `isEmpty`, `chars`, `codePoints`; a `String` and a `StringBuilder` are one |
| `Appendable` | `append` of a `CharSequence`, of a range of one and of a `char`; a `StringBuilder`, a `Writer` and a `PrintStream` are one |
| `Integer`, `Long` | boxing and unboxing; `MAX_VALUE`, `MIN_VALUE`, `SIZE`, `BYTES`, `parseInt` / `parseLong` with or without a radix, `valueOf`, `decode`, `toString` with or without a radix, `toBinaryString`, `toHexString`, `toOctalString`, `Integer.parseUnsignedInt` and `Long.parseUnsignedLong` with or without a radix, `toUnsignedString` with or without a radix, `Integer.toUnsignedLong`, `divideUnsigned`, `remainderUnsigned`, `compareUnsigned`, `highestOneBit`, `lowestOneBit`, `numberOfLeadingZeros`, `numberOfTrailingZeros`, `reverse`, `reverseBytes`, `rotateLeft`, `rotateRight`, `compare`, `signum`, `sum`, `max`, `min`, `bitCount`, `hashCode`, `intValue`, `longValue`, `doubleValue`, `compareTo` |
| `Byte`, `Short` | boxing and unboxing; `MAX_VALUE`, `MIN_VALUE`, `SIZE`, `BYTES`, `parseByte` / `parseShort` with or without a radix, `valueOf`, `toString`, `compare`, `hashCode`, `toUnsignedInt`, `byteValue` / `shortValue`, `intValue`, `longValue`, `floatValue`, `doubleValue`, `compareTo` |
| `Float` | `MAX_VALUE`, `MIN_VALUE`, `MIN_NORMAL`, `MAX_EXPONENT`, `MIN_EXPONENT`, `SIZE`, `BYTES`, `POSITIVE_INFINITY`, `NEGATIVE_INFINITY`, `NaN`, `parseFloat`, `valueOf`, `toString`, `toHexString`, `compare`, `hashCode`, `floatToIntBits`, `floatToRawIntBits`, `intBitsToFloat`, `isNaN`, `isInfinite`, `isFinite`, `sum`, `max`, `min`, `floatValue`, `doubleValue`, `intValue`, `longValue`, `compareTo` |
| `Double` | `MAX_VALUE`, `MIN_VALUE`, `MIN_NORMAL`, `MAX_EXPONENT`, `MIN_EXPONENT`, `SIZE`, `BYTES`, `POSITIVE_INFINITY`, `NEGATIVE_INFINITY`, `NaN`, `parseDouble`, `valueOf`, `toString`, `toHexString`, `compare`, `hashCode`, `doubleToLongBits`, `doubleToRawLongBits`, `longBitsToDouble`, `isNaN`, `isInfinite`, `isFinite`, `sum`, `max`, `min`, `intValue`, `longValue`, `doubleValue`, `compareTo` |
| `Boolean`, `Character` | `Boolean.TRUE`, `FALSE`, `parseBoolean`, `valueOf`, `toString`, `hashCode`, `compare`, `logicalAnd`, `logicalOr`, `logicalXor`, `booleanValue`, `compareTo`; `Character.MAX_VALUE`, `MIN_VALUE`, `valueOf`, `toString`, `compare`, `hashCode`, `isDigit`, `isLetter`, `isLetterOrDigit`, `isAlphabetic`, `isWhitespace`, `isSpaceChar`, `isUpperCase`, `isLowerCase`, `toUpperCase`, `toLowerCase`, `getNumericValue`, `digit`, `forDigit`, `charValue`, `compareTo`; `getType` and the constants of the general categories, `isTitleCase`, `isDefined`, `isMirrored`, `isIdeographic`, `isISOControl`, `isJavaIdentifierStart`, `isJavaIdentifierPart`, `isUnicodeIdentifierStart`, `isUnicodeIdentifierPart`, `isIdentifierIgnorable`, `isEmoji`, `isEmojiPresentation`, `isEmojiModifier`, `isEmojiModifierBase`, `isEmojiComponent`, `isExtendedPictographic`, each test of a `char` or of a code point; `toChars` of a code point with or without an array to write into, `codePointAt` of an array, `reverseBytes`, `toString` of a code point, `charCount`, `isValidCodePoint`, `isBmpCodePoint`, `isSupplementaryCodePoint`, `isHighSurrogate`, `isLowSurrogate`, `isSurrogate`, `isSurrogatePair`, `toCodePoint`, `highSurrogate`, `lowSurrogate`; `MIN_RADIX`, `MAX_RADIX`, `SIZE`, `BYTES`, `MIN_CODE_POINT`, `MAX_CODE_POINT`, `MIN_SUPPLEMENTARY_CODE_POINT` and the bounds of the surrogates |
| `Number` | `intValue`, `longValue`, `floatValue`, `doubleValue`, `byteValue`, `shortValue`; a class extending it, which declares the first four |
| `Math`, `StrictMath` | `PI`, `E`, `TAU`, `abs`, `max`, `min`, `sqrt`, `cbrt`, `pow`, `exp`, `log`, `log10`, `floor`, `ceil`, `rint`, `round`, `signum`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `tanh`, `hypot`, `toRadians`, `toDegrees`, `floorDiv`, `floorMod`, `random`, `addExact`, `subtractExact`, `multiplyExact`, `negateExact`, `toIntExact`, `absExact`, `incrementExact`, `decrementExact`, `divideExact`, `floorDivExact`, `ceilDivExact`, `ceilDiv`, `ceilMod`, `clamp`, `multiplyFull`, `multiplyHigh`, `unsignedMultiplyHigh`, `log1p`, `expm1`, `IEEEremainder`, `copySign`, `getExponent`, `nextAfter`, `ulp`, `nextUp`, `nextDown`, `fma`, `scalb` |
| `System` | `out`, `err` (each a `PrintStream`), `in` (an `InputStream`), `currentTimeMillis`, `nanoTime`, `exit`, `arraycopy`, `identityHashCode`, `lineSeparator`, `getProperty` with or without a default, `getenv` of a name, `gc` |
| `Runtime` | `Runtime.getRuntime`, `availableProcessors`, `gc`, `exit`, `halt` |
| `IO` | `println`, `print`, `readln` |
| `InputStream`, `OutputStream` | `read` of a byte or into an array, `readAllBytes`, `readNBytes`, `skip`, `skipNBytes`, `available`, `mark`, `reset`, `markSupported`, `transferTo`, `close`, `InputStream.nullInputStream`; `write` of a byte or of an array, `flush`, `close`, `OutputStream.nullOutputStream`; a class of the program extends either |
| `ByteArrayInputStream`, `ByteArrayOutputStream` | over a `byte[]` or a range of one; `toByteArray`, `size`, `reset`, `writeTo`, `writeBytes`, `toString` with or without a charset |
| `FilterInputStream`, `FilterOutputStream`, `BufferedInputStream`, `BufferedOutputStream` | a stream over another, the buffered ones with or without a size |
| `PushbackInputStream`, `SequenceInputStream` | with or without a size, `unread` of a byte or of an array; over two streams or an `Enumeration` of them |
| `DataInput`, `DataOutput`, `DataInputStream`, `DataOutputStream` | `readBoolean`, `readByte`, `readUnsignedByte`, `readShort`, `readUnsignedShort`, `readChar`, `readInt`, `readLong`, `readFloat`, `readDouble`, `readFully`, `skipBytes`, `readLine`, `readUTF`; the `write` of each, `writeBytes`, `writeChars`, `writeUTF`, `size` |
| `PrintStream` | over an `OutputStream`, with or without automatic flushing and a charset; `print` and `println` of each type, `printf` and `format` with or without a `Locale`, `append`, `write`, `writeBytes`, `flush`, `close`, `checkError`, `charset` |
| `Reader`, `Writer` | `read` of a character or into an array, `skip`, `ready`, `mark`, `reset`, `markSupported`, `transferTo`, `close`; `write` of a character, an array or a string, `append`, `flush`, `close`; `Reader.nullReader`, `Writer.nullWriter`; a class of the program extends either |
| `FilterReader`, `FilterWriter`, `CharArrayReader`, `CharArrayWriter`, `PushbackReader`, `LineNumberReader` | a reader or a writer over another; over a `char[]` or a range of one, `toCharArray`, `size`, `reset`, `writeTo`; with or without a size, `unread` of a character or of an array; `getLineNumber`, `setLineNumber` |
| `StringReader`, `StringWriter`, `InputStreamReader`, `OutputStreamWriter` | over a string; `getBuffer`, `toString`; over a stream, with or without a `Charset` or a charset's name, `getEncoding` |
| `BufferedReader`, `BufferedWriter`, `PrintWriter` | with or without a buffer size; `readLine`, `lines`; `newLine`; a `PrintWriter` over a `Writer` or an `OutputStream`, with or without automatic flushing and a charset, with the members of `PrintStream` over characters |
| `Charset`, `StandardCharsets` | `UTF_8`, `ISO_8859_1`, `US_ASCII`, `UTF_16`, `UTF_16BE`, `UTF_16LE`; `Charset.forName`, `defaultCharset`, `isSupported`, `name`, `displayName`, `toString` |
| `Locale` | `ROOT`, `ENGLISH`, `US`, `UK`, `CANADA`, `Locale.getDefault` (which is `US`), `getLanguage`, `getCountry`, `toLanguageTag`, `toString` |
| `MessageFormat` | `new MessageFormat` of a pattern with or without a `Locale`, `MessageFormat.format`; `format` of an `Object[]`, `applyPattern`, `toPattern`, `getLocale`, `setLocale`. A pattern's arguments `{n}` with no format type: a number is written as a JDK writes one in English (`1,000`, `1,234.568`), a `BigInteger` and a `BigDecimal` from their own digits, any other value as its `toString` |
| `Normalizer`, `Normalizer.Form` | `Normalizer.normalize` and `isNormalized` of a `CharSequence` and a form; the forms `NFD`, `NFC`, `NFKD`, `NFKC` |
| `Iterable`, `Iterator` | `iterator`, `forEach`, `spliterator`; `hasNext`, `next`, `remove`, `forEachRemaining` |
| `Spliterator`, `Spliterators`, `RandomAccess` | `tryAdvance`, `forEachRemaining`, `trySplit`, `estimateSize`, `getExactSizeIfKnown`, `characteristics`, `hasCharacteristics`, `getComparator` and the eight characteristics; a collection's `spliterator` reports the characteristics and the estimate a JDK's reports; `Spliterators.spliterator` of a collection, an iterator and its size or an array, `spliteratorUnknownSize`, `emptySpliterator`, `iterator`, `AbstractSpliterator`; `Spliterator.OfInt`, `OfLong` and `OfDouble`, with the same of `Spliterators` over an `int[]`, a `long[]`, a `double[]` and a `PrimitiveIterator` (`emptyIntSpliterator`, `AbstractIntSpliterator` and those of the other two); `RandomAccess`, which `ArrayList` and the lists of `List.of` and `Arrays.asList` are |
| `Collection` | `size`, `isEmpty`, `contains`, `add`, `remove`, `containsAll`, `addAll`, `removeAll`, `retainAll`, `removeIf`, `clear`, `toArray` of nothing, an array or a function making one, `iterator`, `forEach`, `stream`, `parallelStream`, `equals`, `hashCode`, `toString` |
| `SequencedCollection`, `SequencedSet`, `SequencedMap` | a collection or a map with two ends: `reversed`, a view of it in the other order (a list's reads and changes the list by an index from the other end, a deque's calls the member of the other end); `getFirst`, `getLast`, `removeFirst`, `removeLast`, `addFirst`, `addLast`; `firstEntry`, `lastEntry`, `pollFirstEntry`, `pollLastEntry` (each a copy), `putFirst`, `putLast`, `sequencedKeySet`, `sequencedValues`, `sequencedEntrySet`. A list, a deque, a linked or sorted set and a linked or sorted map are one, and a class of the program implementing one has the defaults |
| `List`, `ArrayList` | the members of `Collection`; `get`, `set`, `add` and `addAll` at an index, `remove` at an index, `indexOf`, `lastIndexOf`, `subList`, `sort`, `replaceAll`, `getFirst`, `getLast`, `removeFirst`, `removeLast`, `addFirst`, `addLast`, `reversed`, `listIterator` with or without an index (a `ListIterator`: `hasNext`, `next`, `hasPrevious`, `previous`, `nextIndex`, `previousIndex`, `remove`, `set`, `add`); `List.of`, `List.copyOf`; `new ArrayList<>()` of nothing, a capacity or a collection, `ensureCapacity`, `trimToSize` |
| `Set`, `HashSet` | the members of `Collection`; `Set.of`, `Set.copyOf`; `new HashSet<>()` of nothing, a capacity or a collection, `HashSet.newHashSet` of a number of elements |
| `Map`, `HashMap`, `Map.Entry` | `size`, `isEmpty`, `containsKey`, `containsValue`, `get`, `getOrDefault`, `put`, `putIfAbsent`, `putAll`, `remove` of a key or of a key and its value, `replace` of a value or of an old value, `clear`, `keySet`, `values`, `entrySet`, `forEach`, `replaceAll`, `computeIfAbsent`, `computeIfPresent`, `compute`, `merge`, `equals`, `hashCode`, `toString`; `Map.of` up to ten pairs, `Map.entry`, `Map.ofEntries`, `Map.copyOf`; `new HashMap<>()` of nothing, a capacity or a map, `HashMap.newHashMap` of a number of mappings; `getKey`, `getValue`, `setValue`, `Map.Entry.comparingByKey` and `comparingByValue` with or without a comparator; `AbstractMap.SimpleEntry` and `SimpleImmutableEntry` of a key and a value or of an entry |
| `LinkedHashMap`, `LinkedHashSet` | the members of `HashMap` and `HashSet`, walked in the order the keys were added; `new LinkedHashMap<>(capacity, loadFactor, true)`, walked in the order they were last reached, and `removeEldestEntry`, asked after each key added; `new LinkedHashSet<>()` of nothing, a capacity or a collection; the members of `SequencedMap` and `SequencedSet`, where `putFirst`, `putLast`, `addFirst` and `addLast` move a key the map or set holds to that end; `LinkedHashMap.newLinkedHashMap`, `LinkedHashSet.newLinkedHashSet` |
| `SortedMap`, `NavigableMap`, `TreeMap` | the members of `Map`, the keys in the order of a comparator or of their `compareTo`; `comparator`, `firstKey`, `lastKey`, `firstEntry`, `lastEntry`, `pollFirstEntry`, `pollLastEntry`, `lowerEntry`, `lowerKey`, `floorEntry`, `floorKey`, `ceilingEntry`, `ceilingKey`, `higherEntry`, `higherKey`, `navigableKeySet`, `descendingKeySet`, `descendingMap`, `reversed`, `putFirst`, `putLast`; `subMap`, `headMap` and `tailMap` with or without the inclusive flags, each a view of the map; `new TreeMap<>()` of nothing, a comparator, a map or a sorted map |
| `SortedSet`, `NavigableSet`, `TreeSet` | the members of `Set` over the keys of a `TreeMap`; `comparator`, `first`, `last`, `lower`, `floor`, `ceiling`, `higher`, `pollFirst`, `pollLast`, `getFirst`, `getLast`, `removeFirst`, `removeLast`, `addFirst`, `addLast`, `descendingSet`, `descendingIterator`, `reversed`; `subSet`, `headSet` and `tailSet` with or without the inclusive flags; `new TreeSet<>()` of nothing, a comparator, a collection or a sorted set |
| `EnumSet`, `EnumMap` | the members of `Set` and of `Map` over the constants of one enum, in their order; `EnumSet.noneOf`, `allOf`, `of` one constant or more, `range`, `complementOf`, `copyOf` of a collection or of an enum set; `new EnumMap<>()` of the enum's class, an enum map or a map |
| `Queue`, `Deque`, `ArrayDeque` | the members of `Collection`; `offer`, `poll`, `peek`, `element`, `remove`; `addFirst`, `addLast`, `offerFirst`, `offerLast`, `removeFirst`, `removeLast`, `pollFirst`, `pollLast`, `getFirst`, `getLast`, `peekFirst`, `peekLast`, `push`, `pop`, `removeFirstOccurrence`, `removeLastOccurrence`, `descendingIterator`, `reversed` |
| `AbstractQueue`, `PriorityQueue` | the members of `Queue`; a priority queue of an initial capacity, a comparator or both, or of a collection, a sorted set or another priority queue, and `comparator` |
| `LinkedList` | the members of `List` and `Deque`, `reversed` a `LinkedList`; `null` elements; of nothing or a collection |
| `AbstractCollection`, `AbstractList`, `AbstractSequentialList`, `AbstractSet`, `AbstractMap` | what a class of the program extends to be a collection |
| `Vector`, `Stack`, `Enumeration` | the members of `ArrayList`; `addElement`, `elementAt`, `firstElement`, `lastElement`, `setElementAt`, `insertElementAt`, `removeElementAt`, `removeElement`, `removeAllElements`, `elements`; `push`, `pop`, `peek`, `empty`, `search`; `hasMoreElements`, `nextElement`, `asIterator` |
| `Hashtable` | the members of `Map`; `keys`, `elements`, `contains`; no `null` key or value |
| `Properties` | the members of `Hashtable`; of nothing, a capacity or defaults; `getProperty` with or without a default, `setProperty`, `load` of a `Reader` or of an `InputStream` in ISO 8859-1, `store` to a `Writer` or an `OutputStream` with comments, `list` to a `PrintStream` or a `PrintWriter`, `propertyNames`, `stringPropertyNames` |
| `ResourceBundle`, `PropertyResourceBundle`, `ListResourceBundle` | `ResourceBundle.getBundle` of a base name, with a `Locale` and with a `ClassLoader` ([Resources](index.md#resources)), `clearCache` with or without a loader; `getString`, `getStringArray`, `getObject`, `getKeys`, `keySet`, `containsKey`, `getLocale`, `getBaseBundleName`, `handleGetObject`, `handleKeySet`, `setParent`; `new PropertyResourceBundle` of an `InputStream` or a `Reader`; a class of the program extending `ListResourceBundle` with its `getContents` |
| `ServiceLoader`, `ServiceLoader.Provider` | `load` of a `Class` with or without a `ClassLoader`, `loadInstalled` ([Resources](index.md#resources)); `iterator`, `stream`, `findFirst`, `reload`, `toString`; `type`, `get` |
| `Arrays` | `asList`, `toString`, `fill`, `equals`, `copyOf`, `copyOfRange`, `sort` of a whole array or a range, with or without a comparator, `binarySearch`; over arrays of `int`, `long`, `double`, `char`, `boolean` and of objects; `toString`, `fill`, `equals`, `copyOf` and `copyOfRange` of a `byte[]`; `toString` and `equals` of a `short[]` and a `float[]`; `hashCode` of every array; `setAll` of an `int[]`, a `long[]`, a `double[]` and an array of objects; `deepToString`, `deepEquals`, `deepHashCode`; `stream` of an array of objects, an `int[]`, a `long[]` or a `double[]`, or of a range of one; `spliterator` of an array of objects, an `int[]`, a `long[]` or a `double[]`, or of a range of one |
| `Collections` | `sort`, `binarySearch` with or without a comparator, `reverse`, `shuffle`, `swap`, `max`, `min`, `frequency`, `addAll`, `fill`, `nCopies`, `emptyList`, `emptySet`, `emptyMap`, `emptyIterator`, `emptyListIterator`, `emptyEnumeration`, `singletonList`, `singleton`, `singletonMap`, `unmodifiableList`, `unmodifiableSet`, `unmodifiableCollection`, `unmodifiableMap`, `synchronizedList`, `synchronizedSet`, `synchronizedCollection`, `synchronizedMap`; `unmodifiable`, `synchronized` and `empty` of `SortedSet`, `NavigableSet`, `SortedMap` and `NavigableMap`; `unmodifiableSequencedCollection`, `unmodifiableSequencedSet`, `unmodifiableSequencedMap`, `newSetFromMap`, `newSequencedSetFromMap`; `reverseOrder`, `rotate`, `replaceAll`, `indexOfSubList`, `lastIndexOfSubList`, `list` of an `Enumeration`, `enumeration` of a collection |
| `Objects` | `equals`, `hashCode`, `hash`, `toString`, `isNull`, `nonNull`, `requireNonNull` with or without a message or a supplier of one, `requireNonNullElse`, `requireNonNullElseGet`, `compare`, `checkIndex`, `checkFromIndexSize`, `checkFromToIndex` |
| `Optional` | `empty`, `of`, `ofNullable`, `isPresent`, `isEmpty`, `get`, `orElse`, `orElseGet`, `orElseThrow`, `ifPresent`, `ifPresentOrElse`, `filter`, `map`, `flatMap`, `or`, `stream`, `equals`, `hashCode`, `toString` |
| `OptionalInt`, `OptionalLong`, `OptionalDouble` | `empty`, `of`, `isPresent`, `isEmpty`, `getAsInt` / `getAsLong` / `getAsDouble`, `orElse`, `orElseGet`, `orElseThrow`, `ifPresent`, `ifPresentOrElse`, `stream`, `equals`, `hashCode`, `toString` |
| `Stream` | `Stream.of`, `ofNullable`, `empty`, `iterate` with or without a test, `generate`, `concat`, `builder`; `filter`, `map`, `mapToInt`, `mapToLong`, `mapToDouble`, `flatMap`, `flatMapToInt`, `flatMapToLong`, `flatMapToDouble`, `mapMulti`, `mapMultiToInt`, `mapMultiToLong`, `mapMultiToDouble`, `gather`, `distinct`, `sorted` with or without a comparator, `peek`, `limit`, `skip`, `takeWhile`, `dropWhile`; `forEach`, `forEachOrdered`, `toArray` of nothing or a function making the array, `reduce` in its three forms, `collect` of a `Collector` or of three functions, `toList`, `min`, `max`, `count`, `anyMatch`, `allMatch`, `noneMatch`, `findFirst`, `findAny`, `iterator`, `spliterator`; `parallel`, `sequential`, `isParallel`, `unordered`, `onClose`, `close` |
| `IntStream`, `LongStream`, `DoubleStream` | `of`, `empty`, `iterate`, `generate`, `concat`, `builder`, and of the first two `range` and `rangeClosed`; the members of `Stream` over numbers, with `mapToObj`, `boxed`, `asLongStream`, `asDoubleStream`, `sum`, `average`, `min`, `max`, `summaryStatistics`, `toArray`, `mapMulti`, an `iterator` that is a `PrimitiveIterator.OfInt`, `OfLong` or `OfDouble` and a `spliterator` that is a `Spliterator.OfInt`, `OfLong` or `OfDouble` |
| `Collectors` | `toList`, `toSet`, `toCollection`, `toUnmodifiableList`, `toUnmodifiableSet`, `toMap` and `toUnmodifiableMap` of two functions, with a merge function, and `toMap` with a map's supplier too; `joining` of nothing, a delimiter, or one with a prefix and a suffix; `groupingBy` of a classifier, with a collector, and with a map's supplier too; `groupingByConcurrent` in the same three forms and `toConcurrentMap` in the three of `toMap`, over a `ConcurrentHashMap`; `partitioningBy` with or without a collector; `counting`, `summingInt` / `Long` / `Double`, `averagingInt` / `Long` / `Double`, `summarizingInt` / `Long` / `Double`, `minBy`, `maxBy`, `reducing` in its three forms, `mapping`, `filtering`, `flatMapping`, `collectingAndThen`, `teeing` |
| `Collector` | `supplier`, `accumulator`, `combiner`, `finisher`, `characteristics`; `Collector.of` with or without a finisher; `Collector.Characteristics` |
| `Gatherer`, `Gatherers` | `initializer`, `integrator`, `combiner`, `finisher`, `andThen`; `Gatherer.of` and `ofSequential` in their forms, `defaultInitializer`, `defaultCombiner`, `defaultFinisher`; `Gatherer.Integrator` with `of` and `ofGreedy`, `Gatherer.Downstream` with `push` and `isRejecting`; a gatherer of the program's own class; `Gatherers.fold`, `scan`, `windowFixed`, `windowSliding`, `mapConcurrent` |
| `StreamSupport` | `stream`, `intStream`, `longStream` and `doubleStream` of a spliterator, or of a supplier of one and its characteristics; the spliterator is asked for its elements when the stream is, and `count()` of one that says `SIZED` runs no stage |
| `IntSummaryStatistics`, `LongSummaryStatistics`, `DoubleSummaryStatistics` | of nothing, or of a count, a minimum, a maximum and a sum; `accept`, `combine`, `getCount`, `getSum`, `getMin`, `getMax`, `getAverage`, `toString` |
| `Random` | `new Random()`, `new Random(long)`, `setSeed`, `nextInt()`, `nextInt(int)`, `nextLong`, `nextDouble`, `nextFloat`, `nextBoolean`, `nextGaussian`, `nextBytes`; `nextInt`, `nextLong`, `nextDouble` and `nextFloat` of a bound or of two; `ints`, `longs` and `doubles` of nothing, a size, two bounds or a size and two bounds; a seed gives the specified sequence, and a number or a stream between two bounds the numbers a JDK draws |
| `UUID` | `new UUID(long, long)`, `fromString`, `randomUUID`; `getMostSignificantBits`, `getLeastSignificantBits`, `version`, `variant`, `compareTo`, `equals`, `hashCode`, `toString`. `fromString` takes the forms a JDK takes, such as `1-1-1-1-1`; `randomUUID` is version 4 |
| `BigInteger` | `ZERO`, `ONE`, `TWO`, `TEN`, `valueOf`; `new BigInteger` of a text with or without a radix, of the bytes of a two's complement or a range of them, of a sign and a magnitude; `add`, `subtract`, `multiply`, `divide`, `remainder`, `mod`, `divideAndRemainder`, `pow`, `gcd`, `abs`, `negate`, `signum`, `sqrt`, `sqrtAndRemainder`, `modPow`, `modInverse`; `shiftLeft`, `shiftRight`, `and`, `or`, `xor`, `not`, `andNot`, `testBit`, `setBit`, `clearBit`, `flipBit`, `getLowestSetBit`, `bitLength`, `bitCount`; `compareTo`, `equals`, `hashCode`, `min`, `max`; `toString` with or without a radix, `toByteArray`; `intValue`, `longValue`, `floatValue`, `doubleValue`, `intValueExact`, `longValueExact`, `shortValueExact`, `byteValueExact` |
| `BigDecimal` | `ZERO`, `ONE`, `TWO`, `TEN`; `valueOf` of a `long`, of an unscaled `long` and a scale, of a `double`; `new BigDecimal` of a text, a `char[]` or a range of one, an `int`, a `long`, a `double`, a `BigInteger` with or without a scale, each with or without a `MathContext`; `add`, `subtract`, `multiply`, `divide`, `divideToIntegralValue`, `remainder`, `divideAndRemainder` and `pow`, each with or without a `MathContext`; `divide` at a scale or with a `RoundingMode`; `sqrt`, `abs`, `negate`, `plus`, `round`; `signum`, `scale`, `precision`, `unscaledValue`, `setScale`, `movePointLeft`, `movePointRight`, `scaleByPowerOfTen`, `stripTrailingZeros`, `ulp`; `compareTo`, `equals`, `hashCode`, `min`, `max`; `toString`, `toEngineeringString`, `toPlainString`; `toBigInteger`, `toBigIntegerExact`, `intValue`, `longValue`, `floatValue`, `doubleValue`, `intValueExact`, `longValueExact`, `shortValueExact`, `byteValueExact`; the older `ROUND_UP` .. `ROUND_UNNECESSARY` and the members taking one |
| `MathContext`, `RoundingMode` | `new MathContext` of a precision with or without a `RoundingMode`, or of the text `toString` writes; `UNLIMITED`, `DECIMAL32`, `DECIMAL64`, `DECIMAL128`; `getPrecision`, `getRoundingMode`, `equals`, `hashCode`, `toString`; the eight modes and `RoundingMode.valueOf(int)` |
| `Comparator` | `compare`, `reversed`, `thenComparing` of a comparator, of a key or of a key and its comparator, `thenComparingInt`, `thenComparingLong`, `thenComparingDouble`, `Comparator.naturalOrder`, `reverseOrder`, `comparing` with or without a comparator of the key, `comparingInt`, `comparingLong`, `comparingDouble`, `nullsFirst`, `nullsLast` |
| Exceptions | `Throwable`, `Exception`, `Error`, `RuntimeException`, `IllegalArgumentException`, `IllegalStateException`, `ArithmeticException`, `NullPointerException`, `ClassCastException`, `IndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException`, `StringIndexOutOfBoundsException`, `NegativeArraySizeException`, `UnsupportedOperationException`, `NumberFormatException`, `CloneNotSupportedException`, `InterruptedException`, `IllegalMonitorStateException`, `IllegalThreadStateException`, `NoSuchElementException`, `InputMismatchException`, `ConcurrentModificationException`, `EmptyStackException`, `MatchException`, `ArrayStoreException`, `SecurityException`, `AssertionError`, `VirtualMachineError`, `StackOverflowError`, `OutOfMemoryError`, `LinkageError`, `NoClassDefFoundError`, `ExceptionInInitializerError` with `getException`, `IOException`, `EOFException`, `FileNotFoundException`, `UTFDataFormatException`, `UnsupportedEncodingException`, `UncheckedIOException`, `UnsupportedCharsetException`, `IllegalCharsetNameException`, `InstantiationException`, `NoSuchMethodException`, `IllegalAccessException`, `ServiceConfigurationError`, `MalformedURLException`, `MissingResourceException` with `getClassName` and `getKey`: the constructors of the API -- of no argument, of a message, of a message and a cause, of a cause, of an index --, `getMessage`, `getLocalizedMessage`, `getCause`, `initCause`, `addSuppressed`, `getSuppressed`, `printStackTrace()`, `getStackTrace`, `setStackTrace`, `fillInStackTrace`, `toString`; `StackTraceElement` with its constructor of four arguments, `getClassName`, `getMethodName`, `getFileName`, `getLineNumber`, `isNativeMethod`. `IllegalFormatException` and three subclasses the run time raises, with no constructor: `IllegalFormatConversionException`, `UnknownFormatConversionException`, `MissingFormatArgumentException`. Of `java.util.concurrent`: `ExecutionException`, `TimeoutException`, `CancellationException`, `RejectedExecutionException`, `CompletionException`, `BrokenBarrierException` |
| `Runnable`, `Comparable`, `AutoCloseable`, `Closeable`, `Flushable` | `run`; `compareTo`, which a `String`, a box and an enum constant have too; `close`; `flush` |
| `Thread` | the constructors of nothing, a `Runnable`, a name or both; a class extending it; `start`, `run`, `join()`, `join(long)`, `join(Duration)`, `interrupt`, `isInterrupted`, `isAlive`, `getName`, `setName`, `isDaemon`, `setDaemon`, `getPriority`, `setPriority`, `isVirtual`, `threadId`, `getId`, `getContextClassLoader`, `toString`; `Thread.currentThread`, `sleep(long)`, `sleep(long, int)`, `sleep(Duration)`, `yield`, `onSpinWait`, `interrupted`, `holdsLock`, `startVirtualThread`; `Thread.ofPlatform()` and `ofVirtual()` with `name`, `daemon`, `priority`, `start`, `unstarted`, `factory`; `getState` and `Thread.State`; `setUncaughtExceptionHandler`, `getUncaughtExceptionHandler`, `Thread.setDefaultUncaughtExceptionHandler`, `getDefaultUncaughtExceptionHandler` |
| `ThreadLocal`, `InheritableThreadLocal` | `new ThreadLocal<>()`, `withInitial`, `get`, `set`, `remove`, `initialValue`; `childValue` |
| `AtomicInteger`, `AtomicLong` | `get`, `set`, `lazySet`, `getAndSet`, `compareAndSet`, `getAndAdd`, `addAndGet`, `getAndIncrement`, `getAndDecrement`, `incrementAndGet`, `decrementAndGet`, `getAndUpdate`, `updateAndGet`, `getAndAccumulate`, `accumulateAndGet`, `toString`; each is a `Number` |
| `AtomicBoolean`, `AtomicReference` | `get`, `set`, `lazySet`, `getAndSet`, `compareAndSet`, `toString`; of a reference also `getAndUpdate`, `updateAndGet`, `getAndAccumulate`, `accumulateAndGet` |
| `AtomicIntegerArray`, `AtomicLongArray`, `AtomicReferenceArray`, `LongAdder` | of a length or an array; the members of the atomic of one value, each with an index, `length`, `toString`; `add`, `increment`, `decrement`, `sum`, `reset`, `sumThenReset`, `toString`; a `LongAdder` is a `Number` |
| `LongAccumulator`, `DoubleAdder`, `DoubleAccumulator` | of a function and its identity; `accumulate`, `get`, `reset`, `getThenReset`; `add`, `sum`, `reset`, `sumThenReset`; `toString`; each is a `Number` |
| `AtomicIntegerFieldUpdater`, `AtomicLongFieldUpdater`, `AtomicReferenceFieldUpdater` | `newUpdater` of class literals and a constant field name; `get`, `set`, `lazySet`, `getAndSet`, `compareAndSet`, `weakCompareAndSet`, `getAndUpdate`, `updateAndGet`, `getAndAccumulate`, `accumulateAndGet`; of an `int` and a `long` also `getAndAdd`, `addAndGet`, `getAndIncrement`, `getAndDecrement`, `incrementAndGet`, `decrementAndGet` |
| `TimeUnit` | the seven units; `convert`, `toNanos`, `toMicros`, `toMillis`, `toSeconds`, `toMinutes`, `toHours`, `toDays`, `sleep`, `timedJoin`, `timedWait` |
| `Lock`, `ReentrantLock`, `Condition` | `lock`, `lockInterruptibly`, `tryLock` with or without a limit, `unlock`, `newCondition`, `isLocked`, `isHeldByCurrentThread`, `getHoldCount`, `isFair`, `hasQueuedThreads`, `getQueueLength`, `toString`; `await` with or without a limit, `awaitNanos`, `awaitUninterruptibly`, `signal`, `signalAll` |
| `ReadWriteLock`, `ReentrantReadWriteLock` | `readLock` and `writeLock`, each a `Lock`, the write lock with conditions; `getReadLockCount`, `getReadHoldCount`, `getWriteHoldCount`, `isWriteLocked`, `isWriteLockedByCurrentThread`, `isFair`, `hasQueuedThreads`, `getQueueLength`, `toString` |
| `StampedLock` | `writeLock`, `readLock`, each also `Interruptibly` and as `try..` with or without a limit; `tryOptimisticRead`, `validate`; `unlockWrite`, `unlockRead`, `unlock`, `tryUnlockWrite`, `tryUnlockRead`; `tryConvertToWriteLock`, `tryConvertToReadLock`, `tryConvertToOptimisticRead`; `isWriteLocked`, `isReadLocked`, `getReadLockCount`, `isLockStamp`, `isWriteLockStamp`, `isReadLockStamp`, `isOptimisticReadStamp`; `asReadLock`, `asWriteLock`, `asReadWriteLock`; `toString`. A stamp is the number a JDK 25 gives |
| `CountDownLatch`, `Semaphore` | `await` with or without a limit, `countDown`, `getCount`; `acquire`, `acquireUninterruptibly`, `tryAcquire` with or without a limit, `release`, each of one permit or several, `availablePermits`, `drainPermits`, `isFair`; `toString` of both |
| `CyclicBarrier`, `Exchanger` | of a number of threads, with or without an action; `await` with or without a limit, `reset`, `isBroken`, `getParties`, `getNumberWaiting`; `exchange` with or without a limit |
| `Phaser` | of nothing, a number of parties, a parent or both; `register`, `bulkRegister`, `arrive`, `arriveAndDeregister`, `arriveAndAwaitAdvance`, `awaitAdvance`, `awaitAdvanceInterruptibly` with or without a limit, `forceTermination`, `getPhase`, `getRegisteredParties`, `getArrivedParties`, `getUnarrivedParties`, `getParent`, `getRoot`, `isTerminated`, `toString`; `onAdvance` to override |
| `BlockingQueue`, `ArrayBlockingQueue`, `LinkedBlockingQueue`, `SynchronousQueue`, `ConcurrentLinkedQueue` | the members of `Queue`; of a blocking queue also `put`, `take`, `offer` and `poll` with a limit, `remainingCapacity`, `drainTo`; a `SynchronousQueue` holds nothing and hands an element from the thread putting it to the one taking it |
| `TransferQueue`, `LinkedTransferQueue` | the members of a blocking queue; `transfer`, `tryTransfer` with or without a limit, `hasWaitingConsumer`, `getWaitingConsumerCount` |
| `BlockingDeque`, `LinkedBlockingDeque`, `ConcurrentLinkedDeque` | the members of `Deque`; of a blocking deque also those of a blocking queue, and at either end `putFirst`, `putLast`, `takeFirst`, `takeLast`, `offerFirst`, `offerLast`, `pollFirst` and `pollLast` with a limit |
| `ConcurrentMap`, `ConcurrentHashMap` | the members of `Map`; `remove` of a key and its value, `replace` of an old value, `mappingCount`, `contains`, `keys`, `elements`, `keySet` of a value, `ConcurrentHashMap.newKeySet`; of nothing, a capacity or a map; no `null` key or value |
| `ConcurrentNavigableMap`, `ConcurrentSkipListMap`, `ConcurrentSkipListSet` | the members of `NavigableMap` and `ConcurrentMap`, or of `NavigableSet`; of nothing, a comparator, a map, a sorted map, a collection or a sorted set; no `null` key or value; an iterator gives the keys from where it stands and never refuses a change |
| `CopyOnWriteArrayList`, `CopyOnWriteArraySet` | the members of `List`, or of `Set`; `addIfAbsent`, `addAllAbsent`; an iterator walks the list there was when it was made and changes nothing |
| `Executor`, `ExecutorService`, `Executors` | `execute`, `submit` of a `Callable` or a `Runnable`, `invokeAll` and `invokeAny` with or without a limit, `shutdown`, `shutdownNow`, `isShutdown`, `isTerminated`, `awaitTermination`, `close`; `newFixedThreadPool`, `newSingleThreadExecutor`, `newCachedThreadPool`, `newScheduledThreadPool`, `newSingleThreadScheduledExecutor`, each with or without a `ThreadFactory`, `newVirtualThreadPerTaskExecutor`, `newThreadPerTaskExecutor`, `unconfigurableExecutorService`, `unconfigurableScheduledExecutorService`, `defaultThreadFactory`, `callable` |
| `ThreadPoolExecutor`, `RejectedExecutionHandler` | of a core size, a most size, a time and a queue, with or without a `ThreadFactory`, a handler or both; `AbortPolicy`, `CallerRunsPolicy`, `DiscardPolicy`, `DiscardOldestPolicy`; `getPoolSize`, `getCorePoolSize`, `getMaximumPoolSize`, `getLargestPoolSize`, `getActiveCount`, `getTaskCount`, `getCompletedTaskCount`, `getKeepAliveTime`, `getQueue`, `getThreadFactory`, `getRejectedExecutionHandler`, `setRejectedExecutionHandler`, `setCorePoolSize`, `setMaximumPoolSize`, `setKeepAliveTime`, `setThreadFactory`, `allowCoreThreadTimeOut`, `allowsCoreThreadTimeOut`, `prestartCoreThread`, `prestartAllCoreThreads`, `remove`, `purge`, `isTerminating`, `toString`; `beforeExecute`, `afterExecute` and `terminated` to override |
| `ScheduledExecutorService`, `ScheduledThreadPoolExecutor`, `ScheduledFuture`, `Delayed` | `schedule` of a `Runnable` or a `Callable`, `scheduleAtFixedRate`, `scheduleWithFixedDelay`; of a core size, with or without a `ThreadFactory`; `setRemoveOnCancelPolicy`, `setExecuteExistingDelayedTasksAfterShutdownPolicy`, `setContinueExistingPeriodicTasksAfterShutdownPolicy` and what reads them; `getDelay`, `compareTo` |
| `ForkJoinPool`, `ForkJoinTask`, `RecursiveTask`, `RecursiveAction`, `CountedCompleter` | `new ForkJoinPool()`, of a parallelism, `ForkJoinPool.commonPool`; `invoke`, `execute`, `submit`, `getParallelism`, `getCommonPoolParallelism`, `getPoolSize` and the members of `ExecutorService`; `fork`, `join`, `invoke`, `invokeAll`, `get` with or without a limit, `cancel`, `isDone`, `isCancelled`, `isCompletedNormally`, `isCompletedAbnormally`, `getException`, `complete`, `completeExceptionally`, `quietlyComplete`, `quietlyJoin`, `quietlyInvoke`, `getRawResult`, `ForkJoinTask.adapt`, `inForkJoinPool`, `getPool`; `compute`; of a `CountedCompleter` also `tryComplete`, `propagateCompletion`, `onCompletion`, `onExceptionalCompletion`, `getCompleter`, `getRoot`, `getPendingCount`, `setPendingCount`, `addToPendingCount`, `compareAndSetPendingCount`, `decrementPendingCountUnlessZero`, `firstComplete`, `nextComplete`, `quietlyCompleteRoot`, `helpComplete` |
| `Timer`, `TimerTask` | of nothing, a name, a daemon flag or both; `schedule` of a delay with or without a period, `scheduleAtFixedRate`, `cancel`, `purge`; `run`, `cancel`, `scheduledExecutionTime` |
| `Callable`, `Future`, `FutureTask`, `ThreadFactory` | `call`; `get` with or without a limit, `isDone`, `cancel`, `isCancelled`, `state`, `resultNow`, `exceptionNow`; `run`, `toString`; `newThread` |
| `CompletableFuture`, `CompletionStage` | `supplyAsync` and `runAsync` with or without an executor, `completedFuture`, `failedFuture`, `completedStage`, `failedStage`, `allOf`, `anyOf`; `thenApply`, `thenAccept`, `thenRun`, `thenCompose`, `handle`, `whenComplete`, `thenCombine`, `thenAcceptBoth`, `runAfterBoth`, `applyToEither`, `acceptEither`, `runAfterEither`, `exceptionally`, `exceptionallyCompose`, each also `Async` with or without an executor; `complete`, `completeExceptionally`, `completeAsync`, `obtrudeValue`, `obtrudeException`, `cancel`, `orTimeout`, `completeOnTimeout`, `delayedExecutor`, `copy`, `minimalCompletionStage`, `newIncompleteFuture`, `defaultExecutor`, `join`, `get` with or without a limit, `getNow`, `isDone`, `isCancelled`, `isCompletedExceptionally`, `getNumberOfDependents`, `state`, `resultNow`, `exceptionNow`, `toString` |
| `java.util.function` | `Function`, `BiFunction`, `UnaryOperator`, `BinaryOperator`, `Supplier`, `Consumer`, `BiConsumer`, `Predicate`, `BiPredicate`, `BooleanSupplier`; for `Int`, `Long` and `Double`: `IntFunction`, `IntPredicate`, `IntUnaryOperator`, `IntBinaryOperator`, `IntSupplier`, `IntConsumer`, `ToIntFunction`, `ToIntBiFunction`, `ObjIntConsumer`; from one of the three to another, `IntToLongFunction`, `IntToDoubleFunction`, `LongToIntFunction`, `LongToDoubleFunction`, `DoubleToIntFunction`, `DoubleToLongFunction`; each with its abstract method and its `andThen`, `compose`, `and`, `or`, `negate`, `identity`, `not`, `isEqual` |
| `Duration` | `ZERO`; `ofDays`, `ofHours`, `ofMinutes`, `ofSeconds` with or without nanoseconds, `ofMillis`, `ofNanos`, `parse`; `getSeconds`, `getNano`, `isZero`, `isNegative`, `isPositive`; `of` a count of a unit, `between`, `from`; `plus` and `minus` of a `Duration` or a count of a unit, `plusDays` .. `plusNanos`, `minusDays` .. `minusNanos`, `multipliedBy`, `negated`, `abs`, `withSeconds`, `withNanos`, `truncatedTo`; `get`, `getUnits`, `addTo`, `subtractFrom`; `toDays`, `toHours`, `toMinutes`, `toSeconds`, `toMillis`, `toNanos`, `toDaysPart` .. `toNanosPart`; `compareTo`, `equals`, `hashCode`, `toString`; `DateTimeException`, `DateTimeParseException` |
| `LocalDate` | `MIN`, `MAX`, `EPOCH`; `now` with or without a zone or a `Clock`, `of`, `ofYearDay`, `ofEpochDay`, `ofInstant`, `parse` of a text alone or with a formatter, `from`; `getYear`, `getMonth`, `getMonthValue`, `getDayOfMonth`, `getDayOfYear`, `getDayOfWeek`, `isLeapYear`, `lengthOfMonth`, `lengthOfYear`; `withYear` .. `withDayOfYear`, `plusYears` .. `plusDays`, `minusYears` .. `minusDays`; `until` of a date as a `Period`; `datesUntil` with or without a `Period` step; `atTime` of a time or an `OffsetTime`, `atStartOfDay` with or without a zone, `toEpochDay`, `toEpochSecond`; `getChronology`, `getEra`; `isAfter`, `isBefore`, `isEqual`, `compareTo`, `equals`, `hashCode`, `format`, `query`, `toString` |
| `LocalTime` | `MIN`, `MAX`, `MIDNIGHT`, `NOON`; `now`, `of`, `ofSecondOfDay`, `ofNanoOfDay`, `ofInstant`, `parse` of a text alone or with a formatter, `from`; `getHour` .. `getNano`; `withHour` .. `withNano`, `plusHours` .. `plusNanos`, `minusHours` .. `minusNanos`, `truncatedTo`; `atDate`, `atOffset`, `toSecondOfDay`, `toNanoOfDay`, `toEpochSecond`; `isAfter`, `isBefore`, `compareTo`, `equals`, `hashCode`, `format`, `query`, `toString` |
| `LocalDateTime` | `MIN`, `MAX`; `now`, `of` a date and a time or their numbers, `ofInstant`, `ofEpochSecond`, `parse` of a text alone or with a formatter, `from`; `toLocalDate`, `toLocalTime` and the getters of both; `withYear` .. `withNano`, `plusYears` .. `plusNanos`, `minusYears` .. `minusNanos`, `truncatedTo`; `atOffset`, `atZone`, `toEpochSecond`, `toInstant`; `isAfter`, `isBefore`, `isEqual`, `compareTo`, `equals`, `hashCode`, `format`, `query`, `toString` |
| `OffsetDateTime` | `MIN`, `MAX`; `now`, `of`, `ofInstant`, `parse` of a text alone or with a formatter, `from`; `getOffset`, `withOffsetSameLocal`, `withOffsetSameInstant`, `toLocalDateTime`, `toLocalDate`, `toLocalTime` and the getters, `with..`, `plus..`, `minus..` and `truncatedTo` of a `LocalDateTime`; `toInstant`, `toEpochSecond`, `toZonedDateTime`, `toOffsetTime`, `atZoneSameInstant`, `atZoneSimilarLocal`; `isAfter`, `isBefore`, `isEqual`, `compareTo`, `timeLineOrder`, `equals`, `hashCode`, `format`, `query`, `toString` |
| `OffsetTime` | `MIN`, `MAX`; `now`, `of` a time and an offset or their numbers, `ofInstant`, `parse` of a text alone or with a formatter, `from`; `getOffset`, `withOffsetSameLocal`, `withOffsetSameInstant`, `toLocalTime` and the getters, `with..`, `plus..`, `minus..` and `truncatedTo` of a `LocalTime`; `atDate`, `toEpochSecond`; `isAfter`, `isBefore`, `isEqual`, `compareTo`, `equals`, `hashCode`, `format`, `query`, `toString` |
| `MonthDay` | `now`, `of`, `parse` of a text alone or with a formatter, `from`; `getMonth`, `getMonthValue`, `getDayOfMonth`, `isValidYear`, `withMonth`, `with` a `Month`, `withDayOfMonth`, `atYear`; `isAfter`, `isBefore`, `compareTo`, `equals`, `hashCode`, `format`, `query`, `adjustInto`, `toString` |
| `ZonedDateTime` | `now`, `of`, `ofLocal`, `ofInstant`, `ofStrict`, `parse` of a text alone or with a formatter, `from`; `getOffset`, `getZone`, `withZoneSameLocal`, `withZoneSameInstant`, `withFixedOffsetZone`, `withEarlierOffsetAtOverlap`, `withLaterOffsetAtOverlap`, `toLocalDateTime`, `toLocalDate`, `toLocalTime` and the getters, `with..`, `plus..`, `minus..` and `truncatedTo` of a `LocalDateTime`; `toOffsetDateTime`, `toInstant`, `toEpochSecond`; `isAfter`, `isBefore`, `isEqual`, `compareTo`, `equals`, `hashCode`, `format`, `query`, `toString` |
| `Instant` | `EPOCH`, `MIN`, `MAX`; `now` with or without a `Clock`, `ofEpochSecond` with or without nanoseconds, `ofEpochMilli`, `parse`, `from`; `getEpochSecond`, `getNano`, `toEpochMilli`; `plusSeconds`, `plusMillis`, `plusNanos`, `minusSeconds`, `minusMillis`, `minusNanos`, `truncatedTo`; `atOffset`, `atZone`; `isAfter`, `isBefore`, `compareTo`, `equals`, `hashCode`, `query`, `toString` |
| `Year`, `YearMonth` | `now`, `of`, `parse` of a text alone or with a formatter, `from`; `Year.isLeap`, `getValue`, `isLeap`, `length`, `plusYears`, `minusYears`, `atDay`, `atMonth`, `isValidMonthDay`, `atMonthDay`; `getYear`, `getMonth`, `getMonthValue`, `isLeapYear`, `isValidDay`, `lengthOfMonth`, `lengthOfYear`, `withYear`, `withMonth`, `plusYears`, `plusMonths`, `minusYears`, `minusMonths`, `atDay`, `atEndOfMonth`; `isAfter`, `isBefore`, `compareTo`, `equals`, `hashCode`, `format`, `query`, `toString` |
| `Period` | the `ChronoPeriod` of the ISO calendar. `ZERO`; `of`, `ofYears`, `ofMonths`, `ofWeeks`, `ofDays`, `between`, `parse`, `from`; `getYears`, `getMonths`, `getDays`, `isZero`, `isNegative`, `toTotalMonths`; `withYears` .. `withDays`, `plus` and `minus` of an amount, `plusYears` .. `plusDays`, `minusYears` .. `minusDays`, `multipliedBy`, `negated`, `normalized`; `get`, `getUnits`, `addTo`, `subtractFrom`; `equals`, `hashCode`, `toString` |
| `java.time.temporal` | `Temporal`, `TemporalAccessor`, `TemporalAdjuster`, `TemporalAmount`, `TemporalField`, `TemporalUnit`, `TemporalQuery`, `TemporalQueries` (`zoneId`, `zone`, `offset`, `localDate`, `localTime`, `precision`, `chronology`), `ChronoField`, `ChronoUnit`, `ValueRange`, `UnsupportedTemporalTypeException`; `TemporalAdjusters` (every adjuster); `IsoFields` (the quarters and the week-based years, their fields and units), `JulianFields`, `WeekFields` (`of` a `Locale` or a first day and a count, `ISO`, `SUNDAY_START` and their five fields); a field's `getDisplayName` and `resolve`. Each date and time class above: `isSupported` of a field or a unit, `get`, `getLong`, `range`, `with` of a field or an adjuster, `plus` and `minus` of an amount or a count of a unit, `until` in a unit, `adjustInto`, `query`. A program may write its own field, unit, adjuster, amount, query and date, which `from` reads through its fields |
| `java.time.chrono` | `ChronoLocalDate`, `ChronoLocalDateTime`, `ChronoZonedDateTime`: what `LocalDate`, `LocalDateTime` and `ZonedDateTime` are, with their conversions, their order and `getChronology`; `ChronoPeriod`; `Chronology` (`of`, `from`, `ofLocale`, `getAvailableChronologies`) and the [ISO calendar](deviations.md) `IsoChronology` (`INSTANCE`; its dates by year, era, day of the year and epoch day, `dateNow`, `localDateTime`, `zonedDateTime`, `isLeapYear`, `prolepticYear`, `eraOf`, `eras`, `range`, `period`, `epochSecond`, `getId`, `getCalendarType`, `getDisplayName`); `Era`, `IsoEra` (`BCE`, `CE`, `of`, `getValue`, `getDisplayName`) |
| `DateTimeFormatter` | `ofPattern` with or without a `Locale`, every letter of a pattern (a zone's name in words and a day's period in the [words a JDK writes](deviations.md)); `ofLocalizedDate`, `ofLocalizedTime`, `ofLocalizedDateTime` of one `FormatStyle` or two; `ISO_LOCAL_DATE`, `ISO_OFFSET_DATE`, `ISO_DATE`, `ISO_LOCAL_TIME`, `ISO_OFFSET_TIME`, `ISO_TIME`, `ISO_LOCAL_DATE_TIME`, `ISO_OFFSET_DATE_TIME`, `ISO_ZONED_DATE_TIME`, `ISO_DATE_TIME`, `ISO_ORDINAL_DATE`, `ISO_WEEK_DATE`, `ISO_INSTANT`, `BASIC_ISO_DATE`, `RFC_1123_DATE_TIME`; `format`, `formatTo`, `parse` of a text alone or with a query, `parseBest`; `withLocale`, `localizedBy`, `withResolverStyle`, `withZone`, `withChronology` and their getters; `parsedExcessDays`, `parsedLeapSecond`, `toString`; `DateTimeParseException`, `FormatStyle` |
| `DateTimeFormatterBuilder` | `appendPattern`, `appendValue` of a width or of two and a `SignStyle`, `appendValueReduced` of a base or a base date, `appendFraction`, `appendText` of a style or a map of words, `appendLiteral`, `appendOffset`, `appendOffsetId`, `appendLocalizedOffset`, `appendZoneId`, `appendZoneRegionId`, `appendZoneOrOffsetId`, `appendZoneText` and `appendGenericZoneText` with or without preferred zones, `appendDayPeriodText`, `appendLocalized` and `getLocalizedDateTimePattern`, `appendInstant` with or without a count of digits, `append`, `appendOptional`, `optionalStart`, `optionalEnd`, `padNext`, `parseCaseSensitive`, `parseCaseInsensitive`, `parseStrict`, `parseLenient`, `parseDefaulting`, `toFormatter` with or without a `Locale`; `ResolverStyle`, `SignStyle`, `TextStyle` |
| `ZoneOffset`, `ZoneId`, `ZoneRules` | `UTC`, `MIN`, `MAX`; `ZoneOffset.of`, `ofHours`, `ofHoursMinutes`, `ofHoursMinutesSeconds`, `ofTotalSeconds`, `from`, `getTotalSeconds`, `compareTo`; `ZoneId.of` of a name or of a name and a map of aliases, `SHORT_IDS`, `ofOffset`, `systemDefault`, `getAvailableZoneIds`, `from`, `getId`, `getRules`, `getDisplayName`, `normalized`, `equals`, `hashCode`, `toString`; `ZoneRules.of` an offset or the changes of a zone, `isFixedOffset`, `getOffset` and `getValidOffsets` of an instant or a local date-time, `isValidOffset`, `getStandardOffset`, `getDaylightSavings`, `isDaylightSavings`, `getTransition`, `nextTransition`, `previousTransition`, `getTransitions`, `getTransitionRules`; `ZoneRulesProvider.getAvailableZoneIds`, `getRules`, `getVersions`, `refresh`; `ZoneRulesException`. The [zones](deviations.md) a program has are the ones whose offset never changes and the regions it names |
| `ZoneOffsetTransition`, `ZoneOffsetTransitionRule` | `ZoneOffsetTransition.of`, `getInstant`, `toEpochSecond`, `getDateTimeBefore`, `getDateTimeAfter`, `getOffsetBefore`, `getOffsetAfter`, `getDuration`, `isGap`, `isOverlap`, `isValidOffset`, `compareTo`, `equals`, `hashCode`, `toString`; `ZoneOffsetTransitionRule.of`, `getMonth`, `getDayOfMonthIndicator`, `getDayOfWeek`, `getLocalTime`, `isMidnightEndOfDay`, `getTimeDefinition`, `getStandardOffset`, `getOffsetBefore`, `getOffsetAfter`, `createTransition`, `equals`, `hashCode`, `toString`; `TimeDefinition` with `createDateTime` |
| `Clock`, `InstantSource` | `systemUTC`, `systemDefaultZone`, `system`, `fixed`, `offset`, `tick`, `tickSeconds`, `tickMinutes`, `tickMillis`; `instant`, `millis`, `getZone`, `withZone`; a class extending it. `InstantSource.system`, `fixed`, `offset`, `tick`, `instant`, `millis`, `withZone`; a source of the program's own, a lambda too |
| `DayOfWeek`, `Month` | the constants; `of`, `from`, `getValue`, `getDisplayName`, `plus`, `minus`; `Month.length`, `minLength`, `maxLength`, `firstDayOfYear`, `firstMonthOfQuarter` |
| `java.util.Date` | `new Date()`, `new Date(long)`, `Date.from`; `getTime`, `setTime`, `before`, `after`, `compareTo`, `equals`, `hashCode`, `toInstant`, `toString` |
| `java.sql.Date`, `Time`, `Timestamp` | the package of the module `java.sql`. `new` of the milliseconds; `valueOf` of a text, of a `LocalDate`, a `LocalTime` or a `LocalDateTime`; `toLocalDate`, `toLocalTime`, `toLocalDateTime`, `toString`; `Timestamp.from`, `toInstant`, `getNanos`, `setNanos`, `equals`, `compareTo`, `before` and `after` of a `Timestamp`. A wall time is read and written in the host's zone, in the Julian calendar before 1582-10-15 |
| `URI`, `URISyntaxException` | `new URI(String)`, `URI.create`; `getScheme`, `getSchemeSpecificPart`, `getAuthority`, `getUserInfo`, `getHost`, `getPort`, `getPath`, `getQuery`, `getFragment` and the `getRaw..` of each; `isAbsolute`, `isOpaque`, `resolve` of a `URI` or a string, `toURL`, `equals`, `hashCode`, `compareTo`, `toString`; `getInput`, `getReason`, `getIndex` |
| `URL` | `new URL(String)`, of a protocol, a host, a port and a file, and of a protocol, a host and a file; `getProtocol`, `getAuthority`, `getUserInfo`, `getHost`, `getPort`, `getDefaultPort`, `getPath`, `getQuery`, `getFile`, `getRef`, `openStream` ([Resources](index.md#resources)), `toURI`, `sameFile`, `equals`, `hashCode`, `toExternalForm`, `toString` |
| `HttpClient` | `newHttpClient`; `newBuilder` with `version`, `followRedirects`, `executor`, `connectTimeout`, `build`; `send`, `sendAsync`, `followRedirects`, `version`, `executor`, `connectTimeout`, `close`, `shutdown`, `shutdownNow`, `isTerminated`; `HttpClient.Version`, `HttpClient.Redirect` |
| `HttpRequest` | `newBuilder` with or without a `URI`, with `uri`, `header`, `headers`, `setHeader`, `GET`, `POST`, `PUT`, `DELETE`, `HEAD`, `method`, `version`, `expectContinue`, `timeout`, `copy`, `build`; `method`, `uri`, `headers`, `bodyPublisher`, `version`, `expectContinue`, `timeout`, `equals`, `hashCode`, `toString`; `BodyPublishers.ofString` with or without a `Charset`, `ofByteArray` of an array or a range, `ofInputStream`, `noBody`; `BodyPublisher.contentLength` |
| `HttpResponse` | `statusCode`, `headers`, `body`, `uri`, `request`, `previousResponse`, `version`, `toString`; `BodyHandlers.ofString` with or without a `Charset`, `ofByteArray`, `ofInputStream`, `discarding`, `replacing`; `BodySubscribers` of the same and `mapping`; a `BodyHandler` of the program's own choosing one by the `ResponseInfo` (`statusCode`, `headers`, `version`) |
| `HttpHeaders` | `firstValue`, `firstValueAsLong`, `allValues`, `map`, `HttpHeaders.of`, `equals`, `hashCode`, `toString` |
| `HttpTimeoutException`, `HttpConnectTimeoutException` | of a message; what `send` throws, and what the future of `sendAsync` completes with, when a time limit passes |
| `HttpServer`, `HttpContext`, `HttpHandler` | `HttpServer.create` of nothing, of an address and a backlog, or of those with a path, a handler and filters, `bind`, `createContext` with or without a handler, `removeContext` of a path or a context, `setExecutor`, `getExecutor`, `start`, `stop`, `getAddress`; `getPath`, `getHandler`, `setHandler`, `getServer`, `getAttributes`, `getFilters`, `setAuthenticator`, `getAuthenticator`; `handle` |
| `HttpExchange`, `Request` | `getRequestMethod`, `getRequestURI`, `getProtocol`, `getRequestHeaders`, `getRequestBody`, `getResponseHeaders`, `sendResponseHeaders`, `getResponseBody`, `getResponseCode`, `close`, `getHttpContext`, `getRemoteAddress`, `getLocalAddress`, `getAttribute`, `setAttribute`, `setStreams`, `getPrincipal`; `with` |
| `Filter`, `HttpHandlers` | `doFilter`, `description`, `Filter.beforeHandler`, `afterHandler`, `adaptRequest`; `new Filter.Chain` of filters and a handler, its `doFilter`; `HttpHandlers.of` a status, fields and a body, `handleOrElse` |
| `Authenticator`, `BasicAuthenticator`, `HttpPrincipal` | `authenticate`; `Failure`, `Retry` and `Success` with `getResponseCode` or `getPrincipal`; a `BasicAuthenticator` of a realm, with or without a `Charset`, `getRealm`, `checkCredentials`; `new HttpPrincipal` of a user and a realm, `getUsername`, `getRealm`, `getName`, `equals`, `hashCode`, `toString` |
| `Headers` | the members of `Map`; `getFirst`, `add`, `set`; `new Headers()` of nothing or a map, `Headers.of` of names and values in turn or of a map |
| `InetSocketAddress`, `InetAddress`, `BindException`, `UnknownHostException` | of a port, of a host and a port, of an `InetAddress` and a port, `createUnresolved`; `getPort`, `getHostString`, `getHostName`, `getAddress`, `isUnresolved`, `equals`, `hashCode`, `toString`; `getHostAddress`, `getHostName`, `getAddress`, `isLoopbackAddress`, `isAnyLocalAddress`, `InetAddress.getLoopbackAddress`, `getByName` and `getAllByName` of an address in numbers or `localhost`, `getByAddress` |

`String.format`, `formatted` and `printf` take `%d`, `%s`, `%S`, `%f`, `%e`, `%E`, `%x`,
`%X`, `%o`, `%c`, `%b`, `%h`, `%H`, `%n` and `%%`, with the flags `-`, `0`, `+`, a space, `,`, `(` and
`#`, a width, a precision and an argument index (`%2$s`). A `BigInteger` is written by
`%d`, `%x`, `%X` and `%o`, its sign ahead of its digits, and a `BigDecimal` by `%f`, `%e`
and `%E`, its own digits rounded half up.

`java.util.regex.Pattern` and `Matcher` take the syntax the `Pattern` documentation
describes -- classes with unions and intersections, groups (capturing, named, atomic),
lookahead and lookbehind, back references, greedy, reluctant and possessive quantifiers,
the inline flags, `\\p{..}` properties, `\\X` and `\\b{g}` -- and so do `String.matches`,
`replaceAll`, `replaceFirst` and `split`. A malformed pattern is a
`PatternSyntaxException` with a JDK's message. A `Matcher` searches a region
(`region`, `useAnchoringBounds`, `useTransparentBounds`), tells whether a search read the
end of the input (`hitEnd`, `requireEnd`), builds a replacement match by match
(`appendReplacement`, `appendTail`, `replaceAll` of a function) and keeps a match
(`toMatchResult`). `Pattern.splitAsStream` and `Matcher.results` answer a stream. See
[deviations](deviations.md).

A stream takes its elements from its source one at a time when its last operation asks for
them, so a stage sees an element only once every stage before it let that element through,
and a stream with no end can be cut by `limit`, `takeWhile` or `findFirst`. `count()` runs
no stage where the source and the stages tell how many elements there are, as a JDK 25
does: a collection, an array or a range through `map`, `peek`, `sorted`, `skip`, `limit`
or `boxed`. A stream is read once; a second operation on it is an `IllegalStateException`.

```java
record City(String name, String country, int people) {}

void main() {
    List<City> cities = List.of(new City("Oslo", "NO", 709), new City("Bergen", "NO", 289),
            new City("Lyon", "FR", 522), new City("Paris", "FR", 2103), new City("Turku", "FI", 201));
    IO.println(cities.stream().filter(c -> c.people() > 250).map(City::name).sorted().toList());
    IO.println(cities.stream().collect(Collectors.groupingBy(City::country, Collectors.counting())));
    IO.println(cities.stream().collect(
            Collectors.groupingBy(City::country, Collectors.mapping(City::name, Collectors.joining("+")))));
    IO.println(cities.stream().mapToInt(City::people).summaryStatistics());
    IO.println(cities.stream().max(Comparator.comparingInt(City::people)).map(City::name).orElse("none"));
    IO.println(IntStream.rangeClosed(1, 5).map(n -> n * n).sum() + " "
            + Stream.iterate(1, n -> n * 2).limit(8).toList());
    IO.println("to be or not to be".chars().filter(c -> c == 'o').count() + " "
            + Arrays.stream("to be or not to be".split(" ")).distinct().collect(Collectors.joining(",")));
}
```

```
[Bergen, Lyon, Oslo, Paris]
{NO=2, FI=1, FR=2}
{NO=Oslo+Bergen, FI=Turku, FR=Lyon+Paris}
IntSummaryStatistics{count=5, sum=3824, min=201, average=764.800000, max=2103}
Paris
55 [1, 2, 4, 8, 16, 32, 64, 128]
4 to,be,or,not
```

The streams of `java.io` are over arrays, strings, the standard streams and each other;
there are no files. A `PrintStream`, a `PrintWriter` and the two bridges between bytes and
characters take `UTF-8` (the default), `ISO-8859-1`, `US-ASCII`, `UTF-16`, `UTF-16BE` or
`UTF-16LE`.

```java
void main() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeInt(2026);
        out.writeUTF("naïve");
    }
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    IO.println(bytes.size() + " bytes: " + in.readInt() + " " + in.readUTF());
    BufferedReader lines = new BufferedReader(new StringReader("first\nsecond"));
    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
        IO.println(line.toUpperCase());
    }
}
```

```
12 bytes: 2026 naïve
FIRST
SECOND
```

A `HashMap`, a `HashSet` and a `Hashtable` find a key by its `equals` and `hashCode`, and
walk their keys in the order a JDK's do; a `LinkedHashMap` and a `LinkedHashSet` in the
order the keys came in; a `TreeMap` and a `TreeSet` in the order of their comparator.

```java
record Point(int x, int y) {
}

class Grid implements Iterable<Point> {
    private final List<Point> points = new ArrayList<>();

    Grid add(int x, int y) {
        points.add(new Point(x, y));
        return this;
    }

    public Iterator<Point> iterator() {
        return points.iterator();
    }
}

void main() {
    Map<Point, String> names = new HashMap<>();
    names.put(new Point(0, 0), "origin");
    Map<Integer, List<Point>> byRow = new HashMap<>();
    for (Point p : new Grid().add(0, 0).add(3, 1).add(1, 1)) {
        byRow.computeIfAbsent(p.y(), row -> new ArrayList<>()).add(p);
    }
    List<Point> row = byRow.get(1);
    row.sort(Comparator.comparingInt(Point::x));
    Set<String> words = new HashSet<>(Arrays.asList("to be or not to be".split(" ")));
    IO.println(names.get(new Point(0, 0)) + " " + byRow.keySet() + " " + row + " " + words);
    IO.println(String.format("%-6s|%5.2f|%04d|%x", "pi", Math.PI, 42, 255));
}
```

```
origin [0, 1] [Point[x=1, y=1], Point[x=3, y=1]] [not, be, or, to]
pi    | 3.14|0042|ff
```

```java
class Account {
    private int balance;

    void withdraw(int amount) {
        if (amount > balance) {
            throw new IllegalStateException("insufficient funds: " + (amount - balance));
        }
        balance -= amount;
    }

    void deposit(int amount) {
        balance += amount;
    }
}

void main() {
    Account account = new Account();
    account.deposit(100);
    Map<String, Integer> attempts = new HashMap<>();
    for (int amount : new int[] {30, 100, 50}) {
        try {
            account.withdraw(amount);
            attempts.put("ok", attempts.getOrDefault("ok", 0) + 1);
        } catch (IllegalStateException e) {
            IO.println(e.getMessage());
            attempts.put("refused", attempts.getOrDefault("refused", 0) + 1);
        } finally {
            IO.println("tried " + amount);
        }
    }
    IO.println(attempts.get("ok") + " ok, " + attempts.get("refused") + " refused, " + attempts.get("other"));
}
```

```
tried 30
insufficient funds: 30
tried 100
tried 50
2 ok, 1 refused, null
```
