# 使える JDK のクラス

[Java 言語のサブセット](index.md)は JDK のクラスの一部を持ちます。各クラスにあるメンバーは次の表のものだけです。
表にないクラスとメンバーは名前を示して拒否します。
`String`、`StringBuilder`、ボックス、`Math`、`System`、`IO` はフロントエンドの一部です。
それ以外のクラスはこのサブセット自身で書かれていて、プログラムが使う部分だけがプログラムと
一緒にコンパイルされます。そのため、プログラムのクラスはこれらのクラスを継承または実装
できます（`AbstractList`、`Iterable`、`Comparator`）。サードパーティのライブラリはこの表に
含まれません。プログラムのソースパスからソースとして読みます
（[サードパーティのライブラリを使う](index.md#using-a-third-party-library)）。

| クラス | メンバー |
|---|---|
| `Object` | `new Object()`、`toString`、`equals`、`hashCode`、`getClass()`、`wait()`、`wait(long)`、`notify`、`notifyAll` |
| `Class` | クラスリテラル（`Name.class`、`int.class`、`String[].class`、`void.class`）と `getClass()`。`getName`、`getSimpleName`、`getCanonicalName`、`getTypeName`、`getPackageName`、`getSuperclass`、`getComponentType`、`isInstance`、`cast`、`isAssignableFrom`、`isInterface`、`isArray`、`isPrimitive`、`isEnum`、`isRecord`、`getEnumConstants`、`toString`、`equals`、`hashCode` |
| 列挙型 | `values()`、`valueOf(String)`、`Enum.valueOf(Class, String)`、`name`、`ordinal`、`compareTo`、`getDeclaringClass`、`toString` |
| レコード | アクセサ、`equals`、`hashCode`、`toString` |
| 配列 | `length`、`clone()` |
| `String` | `length`、`charAt`、`isEmpty`、`isBlank`、`equals`、`equalsIgnoreCase`、`compareTo`、`compareToIgnoreCase`、`hashCode`、`substring`、`subSequence`、開始位置の指定がある、またはない、文字列または文字の `indexOf` と `lastIndexOf`、範囲を取る `indexOf`、`codePointAt`、`codePointBefore`、`codePointCount`、`offsetByCodePoints`、`regionMatches`、`contentEquals`、`getChars`、`indent`、`contains`、`startsWith`、`endsWith`、`toUpperCase`、`toLowerCase`、`trim`、`strip`、`stripLeading`、`stripTrailing`、`repeat`、`replace`、`replaceAll`、`replaceFirst`、`matches`、`concat`、`toCharArray`、`split`、`intern`、`formatted`、引数なし、`Charset`、文字セットの名前を取る `getBytes`、`Locale` を取る `toUpperCase` と `toLowerCase`。`String.valueOf`、`String.copyValueOf`、`String.join`、`Locale` の指定がある、またはない `String.format`、`String.CASE_INSENSITIVE_ORDER`。`new String()`、`new String(String)`、`new String(char[])`、`new String(char[], int, int)`、範囲と、`Charset` または文字セットの名前を指定できる `new String(byte[])`、`chars`、`codePoints`、`lines` |
| `StringBuilder`、`StringBuffer` | 引数なし、容量、文字列を取るコンストラクタ。値、または文字列か `char[]` の範囲を取る `append`、`insert`、`reverse`、`setLength`、`setCharAt`、`deleteCharAt`、`delete`、`replace`、開始位置の指定がある、またはない `indexOf` と `lastIndexOf`、`appendCodePoint`、文字列またはコードポイントを取る `repeat`、`compareTo`、`toString`、`length`、`isEmpty`、`charAt`、`substring`、`subSequence`、`chars`、`codePoints` |
| `CharSequence` | `length`、`charAt`、`subSequence`、`isEmpty`、`chars`、`codePoints`。`String` と `StringBuilder` は `CharSequence` です |
| `Appendable` | `CharSequence`、その範囲、`char` を取る `append`。`StringBuilder`、`Writer`、`PrintStream` は `Appendable` です |
| `Integer`、`Long` | ボクシングとアンボクシング。`MAX_VALUE`、`MIN_VALUE`、`SIZE`、`BYTES`、基数の指定がある、またはない `parseInt` / `parseLong`、`valueOf`、`decode`、基数の指定がある、またはない `toString`、`toBinaryString`、`toHexString`、`toOctalString`、基数の指定がある、またはない `Integer.parseUnsignedInt` と `Long.parseUnsignedLong`、基数の指定がある、またはない `toUnsignedString`、`Integer.toUnsignedLong`、`divideUnsigned`、`remainderUnsigned`、`compareUnsigned`、`highestOneBit`、`lowestOneBit`、`numberOfLeadingZeros`、`numberOfTrailingZeros`、`reverse`、`reverseBytes`、`rotateLeft`、`rotateRight`、`compare`、`signum`、`sum`、`max`、`min`、`bitCount`、`hashCode`、`intValue`、`longValue`、`doubleValue`、`compareTo` |
| `Byte`、`Short` | ボクシングとアンボクシング。`MAX_VALUE`、`MIN_VALUE`、`SIZE`、`BYTES`、基数の指定がある、またはない `parseByte` / `parseShort`、`valueOf`、`toString`、`compare`、`hashCode`、`toUnsignedInt`、`byteValue` / `shortValue`、`intValue`、`longValue`、`floatValue`、`doubleValue`、`compareTo` |
| `Float` | `MAX_VALUE`、`MIN_VALUE`、`MIN_NORMAL`、`MAX_EXPONENT`、`MIN_EXPONENT`、`SIZE`、`BYTES`、`POSITIVE_INFINITY`、`NEGATIVE_INFINITY`、`NaN`、`parseFloat`、`valueOf`、`toString`、`toHexString`、`compare`、`hashCode`、`floatToIntBits`、`floatToRawIntBits`、`intBitsToFloat`、`isNaN`、`isInfinite`、`isFinite`、`sum`、`max`、`min`、`floatValue`、`doubleValue`、`intValue`、`longValue`、`compareTo` |
| `Double` | `MAX_VALUE`、`MIN_VALUE`、`MIN_NORMAL`、`MAX_EXPONENT`、`MIN_EXPONENT`、`SIZE`、`BYTES`、`POSITIVE_INFINITY`、`NEGATIVE_INFINITY`、`NaN`、`parseDouble`、`valueOf`、`toString`、`toHexString`、`compare`、`hashCode`、`doubleToLongBits`、`doubleToRawLongBits`、`longBitsToDouble`、`isNaN`、`isInfinite`、`isFinite`、`sum`、`max`、`min`、`intValue`、`longValue`、`doubleValue`、`compareTo` |
| `Boolean`、`Character` | `Boolean.TRUE`、`FALSE`、`parseBoolean`、`valueOf`、`toString`、`hashCode`、`compare`、`logicalAnd`、`logicalOr`、`logicalXor`、`booleanValue`、`compareTo`。`Character.MAX_VALUE`、`MIN_VALUE`、`valueOf`、`toString`、`compare`、`hashCode`、`isDigit`、`isLetter`、`isLetterOrDigit`、`isAlphabetic`、`isWhitespace`、`isSpaceChar`、`isUpperCase`、`isLowerCase`、`toUpperCase`、`toLowerCase`、`getNumericValue`、`digit`、`forDigit`、`charValue`、`compareTo`。`getType` と一般カテゴリの定数、`isTitleCase`、`isDefined`、`isMirrored`、`isIdeographic`、`isISOControl`、`isJavaIdentifierStart`、`isJavaIdentifierPart`、`isUnicodeIdentifierStart`、`isUnicodeIdentifierPart`、`isIdentifierIgnorable`、`isEmoji`、`isEmojiPresentation`、`isEmojiModifier`、`isEmojiModifierBase`、`isEmojiComponent`、`isExtendedPictographic`。判定はどれも `char` とコードポイントのどちらも取ります。書き込み先の配列の指定がある、またはない、コードポイントを取る `toChars`、配列を取る `codePointAt`、`reverseBytes`、コードポイントを取る `toString`、`charCount`、`isValidCodePoint`、`isBmpCodePoint`、`isSupplementaryCodePoint`、`isHighSurrogate`、`isLowSurrogate`、`isSurrogate`、`isSurrogatePair`、`toCodePoint`、`highSurrogate`、`lowSurrogate`。`MIN_RADIX`、`MAX_RADIX`、`SIZE`、`BYTES`、`MIN_CODE_POINT`、`MAX_CODE_POINT`、`MIN_SUPPLEMENTARY_CODE_POINT` とサロゲートの範囲の定数 |
| `Number` | `intValue`、`longValue`、`floatValue`、`doubleValue`、`byteValue`、`shortValue`。`Number` を継承するクラス（先頭の 4 つを宣言します） |
| `Math`、`StrictMath` | `PI`、`E`、`TAU`、`abs`、`max`、`min`、`sqrt`、`cbrt`、`pow`、`exp`、`log`、`log10`、`floor`、`ceil`、`rint`、`round`、`signum`、`sin`、`cos`、`tan`、`asin`、`acos`、`atan`、`atan2`、`sinh`、`cosh`、`tanh`、`hypot`、`toRadians`、`toDegrees`、`floorDiv`、`floorMod`、`random`、`addExact`、`subtractExact`、`multiplyExact`、`negateExact`、`toIntExact`、`absExact`、`incrementExact`、`decrementExact`、`divideExact`、`floorDivExact`、`ceilDivExact`、`ceilDiv`、`ceilMod`、`clamp`、`multiplyFull`、`multiplyHigh`、`unsignedMultiplyHigh`、`log1p`、`expm1`、`IEEEremainder`、`copySign`、`getExponent`、`nextAfter`、`ulp`、`nextUp`、`nextDown`、`fma`、`scalb` |
| `System` | `out`、`err`（どちらも `PrintStream`）、`in`（`InputStream`）、`currentTimeMillis`、`nanoTime`、`exit`、`arraycopy`、`identityHashCode`、`lineSeparator`、既定値の指定がある、またはない `getProperty`、名前を取る `getenv`、`gc` |
| `Runtime` | `Runtime.getRuntime`、`availableProcessors`、`gc`、`exit`、`halt` |
| `IO` | `println`、`print`、`readln` |
| `InputStream`、`OutputStream` | 1 バイトまたは配列への `read`、`readAllBytes`、`readNBytes`、`skip`、`skipNBytes`、`available`、`mark`、`reset`、`markSupported`、`transferTo`、`close`、`InputStream.nullInputStream`。1 バイトまたは配列の `write`、`flush`、`close`、`OutputStream.nullOutputStream`。プログラムのクラスはどちらも継承できます |
| `ByteArrayInputStream`、`ByteArrayOutputStream` | `byte[]` またはその範囲を対象にします。`toByteArray`、`size`、`reset`、`writeTo`、`writeBytes`、文字セットの指定がある、またはない `toString` |
| `FilterInputStream`、`FilterOutputStream`、`BufferedInputStream`、`BufferedOutputStream` | ほかのストリームの上に作るストリーム。バッファ付きのものはサイズを指定できます |
| `PushbackInputStream`、`SequenceInputStream` | サイズを指定できます。1 バイトまたは配列の `unread`。2 つのストリーム、またはストリームの `Enumeration` の上に作ります |
| `DataInput`、`DataOutput`、`DataInputStream`、`DataOutputStream` | `readBoolean`、`readByte`、`readUnsignedByte`、`readShort`、`readUnsignedShort`、`readChar`、`readInt`、`readLong`、`readFloat`、`readDouble`、`readFully`、`skipBytes`、`readLine`、`readUTF`。それぞれに対応する `write`、`writeBytes`、`writeChars`、`writeUTF`、`size` |
| `PrintStream` | `OutputStream` の上に作ります。自動フラッシュと文字セットを指定できます。各型の `print` と `println`、`Locale` の指定がある、またはない `printf` と `format`、`append`、`write`、`writeBytes`、`flush`、`close`、`checkError`、`charset` |
| `Reader`、`Writer` | 1 文字または配列への `read`、`skip`、`ready`、`mark`、`reset`、`markSupported`、`transferTo`、`close`。1 文字、配列、文字列の `write`、`append`、`flush`、`close`。`Reader.nullReader`、`Writer.nullWriter`。プログラムのクラスはどちらも継承できます |
| `FilterReader`、`FilterWriter`、`CharArrayReader`、`CharArrayWriter`、`PushbackReader`、`LineNumberReader` | ほかのリーダーやライターの上に作るもの。`char[]` またはその範囲を対象にするもの（`toCharArray`、`size`、`reset`、`writeTo`）。サイズを指定でき、1 文字または配列を `unread` できるもの。`getLineNumber`、`setLineNumber` |
| `StringReader`、`StringWriter`、`InputStreamReader`、`OutputStreamWriter` | 文字列を対象にします。`getBuffer`、`toString`。ストリームを対象にするものは `Charset` または文字セットの名前を指定できます。`getEncoding` |
| `BufferedReader`、`BufferedWriter`、`PrintWriter` | バッファのサイズを指定できます。`readLine`、`lines`。`newLine`。`PrintWriter` は `Writer` または `OutputStream` の上に作り、自動フラッシュと文字セットを指定できます。メンバーは `PrintStream` の文字版です |
| `Charset`、`StandardCharsets` | `UTF_8`、`ISO_8859_1`、`US_ASCII`、`UTF_16`、`UTF_16BE`、`UTF_16LE`。`Charset.forName`、`defaultCharset`、`isSupported`、`name`、`displayName`、`toString` |
| `Locale` | `ROOT`、`ENGLISH`、`US`、`UK`、`CANADA`、`Locale.getDefault`（`US` を返します）、`getLanguage`、`getCountry`、`toLanguageTag`、`toString` |
| `MessageFormat` | パターンを取る、`Locale` の指定がある、またはない `new MessageFormat`、`MessageFormat.format`。`Object[]` を取る `format`、`applyPattern`、`toPattern`、`getLocale`、`setLocale`。パターンの引数は書式型のない `{n}` です。数値は JDK が英語で書く形（`1,000`、`1,234.568`）になり、`BigInteger` と `BigDecimal` はその値の桁から書きます。それ以外の値は `toString` の結果になります |
| `Normalizer`、`Normalizer.Form` | `CharSequence` と正規化形式を取る `Normalizer.normalize` と `isNormalized`。正規化形式は `NFD`、`NFC`、`NFKD`、`NFKC` です |
| `Iterable`、`Iterator` | `iterator`、`forEach`、`spliterator`。`hasNext`、`next`、`remove`、`forEachRemaining` |
| `Spliterator`、`Spliterators`、`RandomAccess` | `tryAdvance`、`forEachRemaining`、`trySplit`、`estimateSize`、`getExactSizeIfKnown`、`characteristics`、`hasCharacteristics`、`getComparator` と 8 つの特性。コレクションの `spliterator` は JDK と同じ特性と見積もりを返す。コレクション、イテレーターとその大きさ、または配列を取る `Spliterators.spliterator`、`spliteratorUnknownSize`、`emptySpliterator`、`iterator`、`AbstractSpliterator`。`Spliterator.OfInt`、`OfLong`、`OfDouble` と、`int[]`、`long[]`、`double[]`、`PrimitiveIterator` を取る `Spliterators` の同じメンバー（`emptyIntSpliterator`、`AbstractIntSpliterator` と、ほかの 2 つの型の同じもの）。`ArrayList`、`List.of` と `Arrays.asList` のリストが実装する `RandomAccess` |
| `Collection` | `size`、`isEmpty`、`contains`、`add`、`remove`、`containsAll`、`addAll`、`removeAll`、`retainAll`、`removeIf`、`clear`、引数なし、配列、または配列を作る関数を取る `toArray`、`iterator`、`forEach`、`stream`、`parallelStream`、`equals`、`hashCode`、`toString` |
| `List`、`ArrayList` | `Collection` のメンバー。`get`、`set`、添字を指定する `add` と `addAll`、添字を指定する `remove`、`indexOf`、`lastIndexOf`、`subList`、`sort`、`replaceAll`、`getFirst`、`getLast`、`removeFirst`、`removeLast`、`addFirst`、`addLast`、`reversed`、添字の指定がある、またはない `listIterator`（`ListIterator` のメンバーは `hasNext`、`next`、`hasPrevious`、`previous`、`nextIndex`、`previousIndex`、`remove`、`set`、`add`）。`List.of`、`List.copyOf`。引数なし、容量、コレクションを取る `new ArrayList<>()`、`ensureCapacity`、`trimToSize` |
| `Set`、`HashSet` | `Collection` のメンバー。`Set.of`、`Set.copyOf`。引数なし、容量、コレクションを取る `new HashSet<>()` |
| `Map`、`HashMap`、`Map.Entry` | `size`、`isEmpty`、`containsKey`、`containsValue`、`get`、`getOrDefault`、`put`、`putIfAbsent`、`putAll`、キー、またはキーと値を指定する `remove`、値、または古い値を指定する `replace`、`clear`、`keySet`、`values`、`entrySet`、`forEach`、`replaceAll`、`computeIfAbsent`、`computeIfPresent`、`compute`、`merge`、`equals`、`hashCode`、`toString`。10 組までの `Map.of`、`Map.entry`、`Map.ofEntries`、`Map.copyOf`。引数なし、容量、マップを取る `new HashMap<>()`。`getKey`、`getValue`、`setValue`、コンパレータの指定がある、またはない `Map.Entry.comparingByKey` と `comparingByValue`。キーと値、またはエントリを取る `AbstractMap.SimpleEntry` と `SimpleImmutableEntry` |
| `LinkedHashMap`、`LinkedHashSet` | `HashMap` と `HashSet` のメンバー。キーを加えた順にたどります。`new LinkedHashMap<>(capacity, loadFactor, true)` は最後に触れた順にたどり、キーを加えるたびに `removeEldestEntry` を呼びます。引数なし、容量、コレクションを取る `new LinkedHashSet<>()` |
| `SortedMap`、`NavigableMap`、`TreeMap` | `Map` のメンバー。キーはコンパレータ、またはキーの `compareTo` の順に並びます。`comparator`、`firstKey`、`lastKey`、`firstEntry`、`lastEntry`、`pollFirstEntry`、`pollLastEntry`、`lowerEntry`、`lowerKey`、`floorEntry`、`floorKey`、`ceilingEntry`、`ceilingKey`、`higherEntry`、`higherKey`、`navigableKeySet`、`descendingKeySet`、`descendingMap`、`reversed`、`putFirst`、`putLast`。境界を含むかの指定がある、またはない `subMap`、`headMap`、`tailMap`（いずれもマップのビュー）。引数なし、コンパレータ、マップ、ソート済みマップを取る `new TreeMap<>()` |
| `SortedSet`、`NavigableSet`、`TreeSet` | `TreeMap` のキーの上の `Set` のメンバー。`comparator`、`first`、`last`、`lower`、`floor`、`ceiling`、`higher`、`pollFirst`、`pollLast`、`getFirst`、`getLast`、`removeFirst`、`removeLast`、`addFirst`、`addLast`、`descendingSet`、`descendingIterator`、`reversed`。境界を含むかの指定がある、またはない `subSet`、`headSet`、`tailSet`。引数なし、コンパレータ、コレクション、ソート済みセットを取る `new TreeSet<>()` |
| `EnumSet`、`EnumMap` | 1 つの列挙型の定数を定数の順に持つ `Set` と `Map` のメンバー。`EnumSet.noneOf`、`allOf`、1 つ以上の定数を取る `of`、`range`、`complementOf`、コレクションまたは `EnumSet` を取る `copyOf`。列挙型のクラス、`EnumMap`、マップを取る `new EnumMap<>()` |
| `Queue`、`Deque`、`ArrayDeque` | `Collection` のメンバー。`offer`、`poll`、`peek`、`element`、`remove`。`addFirst`、`addLast`、`offerFirst`、`offerLast`、`removeFirst`、`removeLast`、`pollFirst`、`pollLast`、`getFirst`、`getLast`、`peekFirst`、`peekLast`、`push`、`pop`、`descendingIterator` |
| `LinkedList` | `List` と `Deque` のメンバー。`removeFirstOccurrence`、`removeLastOccurrence`。要素に `null` を使えます。引数なし、コレクションを取るコンストラクタ |
| `AbstractCollection`、`AbstractList`、`AbstractSequentialList`、`AbstractSet`、`AbstractMap` | プログラムのクラスがコレクションになるために継承するクラス |
| `Vector`、`Stack`、`Enumeration` | `ArrayList` のメンバー。`addElement`、`elementAt`、`firstElement`、`lastElement`、`setElementAt`、`insertElementAt`、`removeElementAt`、`removeElement`、`removeAllElements`、`elements`。`push`、`pop`、`peek`、`empty`、`search`。`hasMoreElements`、`nextElement` |
| `Hashtable` | `Map` のメンバー。`keys`、`elements`、`contains`。キーにも値にも `null` は使えません |
| `Arrays` | `asList`、`toString`、`fill`、`equals`、`copyOf`、`copyOfRange`、配列全体または範囲に対する、コンパレータの指定がある、またはない `sort`、`binarySearch`。対象は `int`、`long`、`double`、`char`、`boolean`、オブジェクトの配列。`byte[]` には `toString`、`fill`、`equals`、`copyOf`、`copyOfRange`。`short[]` と `float[]` には `toString` と `equals`。`hashCode` はすべての配列。`int[]`、`long[]`、`double[]`、オブジェクトの配列の `setAll`。`deepToString`、`deepEquals`、`deepHashCode`。オブジェクトの配列、`int[]`、`long[]`、`double[]` と、その範囲を取る `stream`。オブジェクトの配列、`int[]`、`long[]`、`double[]` と、その範囲を取る `spliterator` |
| `Collections` | `sort`、コンパレータの指定がある、またはない `binarySearch`、`reverse`、`shuffle`、`swap`、`max`、`min`、`frequency`、`addAll`、`fill`、`nCopies`、`emptyList`、`emptySet`、`emptyMap`、`singletonList`、`singleton`、`unmodifiableList`、`unmodifiableSet`、`unmodifiableCollection`、`unmodifiableMap`、`synchronizedList`、`synchronizedSet`、`synchronizedCollection`、`synchronizedMap`。`SortedSet`、`NavigableSet`、`SortedMap`、`NavigableMap` の `unmodifiable`、`synchronized`、`empty`。`reverseOrder`、`rotate`、`replaceAll`、`indexOfSubList`、`lastIndexOfSubList` |
| `Objects` | `equals`、`hashCode`、`hash`、`toString`、`isNull`、`nonNull`、メッセージまたはメッセージのサプライヤの指定がある、またはない `requireNonNull`、`requireNonNullElse`、`requireNonNullElseGet`、`compare`、`checkIndex`、`checkFromIndexSize`、`checkFromToIndex` |
| `Optional` | `empty`、`of`、`ofNullable`、`isPresent`、`isEmpty`、`get`、`orElse`、`orElseGet`、`orElseThrow`、`ifPresent`、`ifPresentOrElse`、`filter`、`map`、`flatMap`、`or`、`stream`、`equals`、`hashCode`、`toString` |
| `OptionalInt`、`OptionalLong`、`OptionalDouble` | `empty`、`of`、`isPresent`、`isEmpty`、`getAsInt` / `getAsLong` / `getAsDouble`、`orElse`、`orElseGet`、`orElseThrow`、`ifPresent`、`ifPresentOrElse`、`stream`、`equals`、`hashCode`、`toString` |
| `Stream` | `Stream.of`、`ofNullable`、`empty`、条件を指定できる `iterate`、`generate`、`concat`、`builder`。`filter`、`map`、`mapToInt`、`mapToLong`、`mapToDouble`、`flatMap`、`flatMapToInt`、`flatMapToLong`、`flatMapToDouble`、`mapMulti`、`mapMultiToInt`、`mapMultiToLong`、`mapMultiToDouble`、`gather`、`distinct`、コンパレータを指定できる `sorted`、`peek`、`limit`、`skip`、`takeWhile`、`dropWhile`。`forEach`、`forEachOrdered`、引数なし、または配列を作る関数を取る `toArray`、3 つの形の `reduce`、`Collector` または 3 つの関数を取る `collect`、`toList`、`min`、`max`、`count`、`anyMatch`、`allMatch`、`noneMatch`、`findFirst`、`findAny`、`iterator`、`spliterator`。`parallel`、`sequential`、`isParallel`、`unordered`、`onClose`、`close` |
| `IntStream`、`LongStream`、`DoubleStream` | `of`、`empty`、`iterate`、`generate`、`concat`、`builder`。前の 2 つには `range` と `rangeClosed`。数値に対する `Stream` のメンバーと、`mapToObj`、`boxed`、`asLongStream`、`asDoubleStream`、`sum`、`average`、`min`、`max`、`summaryStatistics`、`toArray`、`mapMulti`。`iterator` は `PrimitiveIterator.OfInt`、`OfLong`、`OfDouble`、`spliterator` は `Spliterator.OfInt`、`OfLong`、`OfDouble` です |
| `Collectors` | `toList`、`toSet`、`toCollection`、`toUnmodifiableList`、`toUnmodifiableSet`。2 つの関数、またはマージ関数も取る `toMap` と `toUnmodifiableMap`、マップのサプライヤーも取る `toMap`。引数なし、区切り、または区切りと接頭辞と接尾辞を取る `joining`。分類関数、コレクター、マップのサプライヤーを取る `groupingBy`。同じ 3 つの形の `groupingByConcurrent` と、`toMap` と同じ 3 つの形の `toConcurrentMap`（どちらも `ConcurrentHashMap` に集める）。コレクターを指定できる `partitioningBy`。`counting`、`summingInt` / `Long` / `Double`、`averagingInt` / `Long` / `Double`、`summarizingInt` / `Long` / `Double`、`minBy`、`maxBy`、3 つの形の `reducing`、`mapping`、`filtering`、`flatMapping`、`collectingAndThen`、`teeing` |
| `Collector` | `supplier`、`accumulator`、`combiner`、`finisher`、`characteristics`。フィニッシャーを指定できる `Collector.of`。`Collector.Characteristics` |
| `Gatherer`、`Gatherers` | `initializer`、`integrator`、`combiner`、`finisher`、`andThen`。各形の `Gatherer.of` と `ofSequential`、`defaultInitializer`、`defaultCombiner`、`defaultFinisher`。`of` と `ofGreedy` を持つ `Gatherer.Integrator`、`push` と `isRejecting` を持つ `Gatherer.Downstream`。プログラム自身のクラスのギャザラー。`Gatherers.fold`、`scan`、`windowFixed`、`windowSliding`、`mapConcurrent` |
| `StreamSupport` | スプリッテレーター、またはそれを返すサプライヤーと特性を取る `stream`、`intStream`、`longStream`、`doubleStream`。スプリッテレーターに要素を求めるのはストリームが読まれるとき。`SIZED` を持つスプリッテレーターのストリームの `count()` はどの段も実行しない |
| `IntSummaryStatistics`、`LongSummaryStatistics`、`DoubleSummaryStatistics` | 引数なし、または個数、最小値、最大値、合計を取るコンストラクタ。`accept`、`combine`、`getCount`、`getSum`、`getMin`、`getMax`、`getAverage`、`toString` |
| `Random` | `new Random()`、`new Random(long)`、`setSeed`、`nextInt()`、`nextInt(int)`、`nextLong`、`nextDouble`、`nextFloat`、`nextBoolean`、`nextGaussian`、`nextBytes`。1 つまたは 2 つの境界を取る `nextInt`（2 つのみ）、`nextLong`、`nextDouble`、`nextFloat`。引数なし、個数、2 つの境界、または個数と 2 つの境界を取る `ints`、`longs`、`doubles`。シードを指定すると仕様どおりの列になり、境界を指定した数値とストリームは JDK と同じ数値を返します |
| `UUID` | `new UUID(long, long)`、`fromString`、`randomUUID`。`getMostSignificantBits`、`getLeastSignificantBits`、`version`、`variant`、`compareTo`、`equals`、`hashCode`、`toString`。`fromString` は `1-1-1-1-1` のように JDK が受け付ける形も受け付けます。`randomUUID` はバージョン 4 です |
| `BigInteger` | `ZERO`、`ONE`、`TWO`、`TEN`、`valueOf`。文字列（基数の指定がある、またはない）、2 の補数のバイト列またはその範囲、符号と絶対値のバイト列を取る `new BigInteger`。`add`、`subtract`、`multiply`、`divide`、`remainder`、`mod`、`divideAndRemainder`、`pow`、`gcd`、`abs`、`negate`、`signum`、`sqrt`、`sqrtAndRemainder`、`modPow`、`modInverse`。`shiftLeft`、`shiftRight`、`and`、`or`、`xor`、`not`、`andNot`、`testBit`、`setBit`、`clearBit`、`flipBit`、`getLowestSetBit`、`bitLength`、`bitCount`。`compareTo`、`equals`、`hashCode`、`min`、`max`。基数の指定がある、またはない `toString`、`toByteArray`。`intValue`、`longValue`、`floatValue`、`doubleValue`、`intValueExact`、`longValueExact`、`shortValueExact`、`byteValueExact` |
| `BigDecimal` | `ZERO`、`ONE`、`TWO`、`TEN`。`long`、スケールなしの `long` とスケール、`double` を取る `valueOf`。文字列、`char[]` またはその範囲、`int`、`long`、`double`、`BigInteger`（スケールの指定がある、またはない）を取る `new BigDecimal`（どれも `MathContext` の指定がある、またはない）。`add`、`subtract`、`multiply`、`divide`、`divideToIntegralValue`、`remainder`、`divideAndRemainder`、`pow`（どれも `MathContext` の指定がある、またはない）。スケールまたは `RoundingMode` を取る `divide`。`sqrt`、`abs`、`negate`、`plus`、`round`。`signum`、`scale`、`precision`、`unscaledValue`、`setScale`、`movePointLeft`、`movePointRight`、`scaleByPowerOfTen`、`stripTrailingZeros`、`ulp`。`compareTo`、`equals`、`hashCode`、`min`、`max`。`toString`、`toEngineeringString`、`toPlainString`。`toBigInteger`、`toBigIntegerExact`、`intValue`、`longValue`、`floatValue`、`doubleValue`、`intValueExact`、`longValueExact`、`shortValueExact`、`byteValueExact`。古い `ROUND_UP` から `ROUND_UNNECESSARY` と、それを取るメンバー |
| `MathContext`、`RoundingMode` | 精度（`RoundingMode` の指定がある、またはない）、または `toString` が書く文字列を取る `new MathContext`。`UNLIMITED`、`DECIMAL32`、`DECIMAL64`、`DECIMAL128`。`getPrecision`、`getRoundingMode`、`equals`、`hashCode`、`toString`。8 つの丸めモードと `RoundingMode.valueOf(int)` |
| `Comparator` | `compare`、`reversed`、コンパレータ、キー、またはキーとそのコンパレータを取る `thenComparing`、`thenComparingInt`、`thenComparingLong`、`thenComparingDouble`、`Comparator.naturalOrder`、`reverseOrder`、キーのコンパレータの指定がある、またはない `comparing`、`comparingInt`、`comparingLong`、`comparingDouble`、`nullsFirst`、`nullsLast` |
| 例外 | `Throwable`、`Exception`、`Error`、`RuntimeException`、`IllegalArgumentException`、`IllegalStateException`、`ArithmeticException`、`NullPointerException`、`ClassCastException`、`IndexOutOfBoundsException`、`ArrayIndexOutOfBoundsException`、`StringIndexOutOfBoundsException`、`NegativeArraySizeException`、`UnsupportedOperationException`、`NumberFormatException`、`CloneNotSupportedException`、`InterruptedException`、`IllegalMonitorStateException`、`IllegalThreadStateException`、`NoSuchElementException`、`InputMismatchException`、`ConcurrentModificationException`、`EmptyStackException`、`MatchException`、`ArrayStoreException`、`SecurityException`、`AssertionError`、`VirtualMachineError`、`StackOverflowError`、`OutOfMemoryError`、`LinkageError`、`NoClassDefFoundError`、`ExceptionInInitializerError`（`getException` を含む）、`IOException`、`EOFException`、`FileNotFoundException`、`UTFDataFormatException`、`UnsupportedEncodingException`、`UncheckedIOException`、`UnsupportedCharsetException`、`IllegalCharsetNameException`。API のコンストラクタ（引数なし、メッセージ、メッセージと原因、原因、添字）、`getMessage`、`getLocalizedMessage`、`getCause`、`initCause`、`addSuppressed`、`getSuppressed`、`printStackTrace()`、`getStackTrace`、`setStackTrace`、`fillInStackTrace`、`toString`。`StackTraceElement` には 4 引数のコンストラクタ、`getClassName`、`getMethodName`、`getFileName`、`getLineNumber`、`isNativeMethod`。実行時に起きる `IllegalFormatException` とその 3 つのサブクラス（コンストラクタなし）：`IllegalFormatConversionException`、`UnknownFormatConversionException`、`MissingFormatArgumentException`。`java.util.concurrent` の `ExecutionException`、`TimeoutException`、`CancellationException`、`RejectedExecutionException`、`CompletionException`、`BrokenBarrierException` |
| `Runnable`、`Comparable`、`AutoCloseable`、`Closeable`、`Flushable` | `run`。`compareTo`（`String`、ボックス、列挙型の定数にもあります）。`close`。`flush` |
| `Thread` | 引数なし、`Runnable`、名前、その両方を取るコンストラクタ。`Thread` を継承するクラス。`start`、`run`、`join()`、`join(long)`、`join(Duration)`、`interrupt`、`isInterrupted`、`isAlive`、`getName`、`setName`、`isDaemon`、`setDaemon`、`getPriority`、`setPriority`、`isVirtual`、`threadId`、`getId`、`toString`。`Thread.currentThread`、`sleep(long)`、`sleep(long, int)`、`sleep(Duration)`、`yield`、`onSpinWait`、`interrupted`、`holdsLock`、`startVirtualThread`。`Thread.ofPlatform()` と `ofVirtual()`（`name`、`daemon`、`priority`、`start`、`unstarted`、`factory`）。`getState` と `Thread.State`。`setUncaughtExceptionHandler`、`getUncaughtExceptionHandler`、`Thread.setDefaultUncaughtExceptionHandler`、`getDefaultUncaughtExceptionHandler` |
| `ThreadLocal`、`InheritableThreadLocal` | `new ThreadLocal<>()`、`withInitial`、`get`、`set`、`remove`、`initialValue`。`childValue` |
| `AtomicInteger`、`AtomicLong` | `get`、`set`、`lazySet`、`getAndSet`、`compareAndSet`、`getAndAdd`、`addAndGet`、`getAndIncrement`、`getAndDecrement`、`incrementAndGet`、`decrementAndGet`、`getAndUpdate`、`updateAndGet`、`getAndAccumulate`、`accumulateAndGet`、`toString`。どちらも `Number` です |
| `AtomicBoolean`、`AtomicReference` | `get`、`set`、`lazySet`、`getAndSet`、`compareAndSet`、`toString`。`AtomicReference` には `getAndUpdate`、`updateAndGet`、`getAndAccumulate`、`accumulateAndGet` もあります |
| `AtomicIntegerArray`、`AtomicLongArray`、`AtomicReferenceArray`、`LongAdder` | 長さ、または配列を取るコンストラクタ。値が 1 つのアトミッククラスと同じメンバー（いずれも添字を取ります）、`length`、`toString`。`add`、`increment`、`decrement`、`sum`、`reset`、`sumThenReset`、`toString`。`LongAdder` は `Number` です |
| `LongAccumulator`、`DoubleAdder`、`DoubleAccumulator` | 関数とその単位元を取るコンストラクタ。`accumulate`、`get`、`reset`、`getThenReset`。`add`、`sum`、`reset`、`sumThenReset`。`toString`。いずれも `Number` です |
| `AtomicIntegerFieldUpdater`、`AtomicLongFieldUpdater`、`AtomicReferenceFieldUpdater` | クラスリテラルと定数のフィールド名を取る `newUpdater`。`get`、`set`、`lazySet`、`getAndSet`、`compareAndSet`、`weakCompareAndSet`、`getAndUpdate`、`updateAndGet`、`getAndAccumulate`、`accumulateAndGet`。`int` と `long` のアップデーターには `getAndAdd`、`addAndGet`、`getAndIncrement`、`getAndDecrement`、`incrementAndGet`、`decrementAndGet` もあります |
| `TimeUnit` | 7 つの単位。`convert`、`toNanos`、`toMicros`、`toMillis`、`toSeconds`、`toMinutes`、`toHours`、`toDays`、`sleep`、`timedJoin`、`timedWait` |
| `Lock`、`ReentrantLock`、`Condition` | `lock`、`lockInterruptibly`、制限時間の指定がある、またはない `tryLock`、`unlock`、`newCondition`、`isLocked`、`isHeldByCurrentThread`、`getHoldCount`、`isFair`、`hasQueuedThreads`、`getQueueLength`、`toString`。制限時間の指定がある、またはない `await`、`awaitNanos`、`awaitUninterruptibly`、`signal`、`signalAll` |
| `ReadWriteLock`、`ReentrantReadWriteLock` | `readLock` と `writeLock`（どちらも `Lock` で、書き込みロックは条件変数を作れます）。`getReadLockCount`、`getReadHoldCount`、`getWriteHoldCount`、`isWriteLocked`、`isWriteLockedByCurrentThread`、`isFair`、`hasQueuedThreads`、`getQueueLength`、`toString` |
| `StampedLock` | `writeLock`、`readLock`（どちらにも `Interruptibly` 版と、制限時間の指定がある、またはない `try..` 版があります）。`tryOptimisticRead`、`validate`。`unlockWrite`、`unlockRead`、`unlock`、`tryUnlockWrite`、`tryUnlockRead`。`tryConvertToWriteLock`、`tryConvertToReadLock`、`tryConvertToOptimisticRead`。`isWriteLocked`、`isReadLocked`、`getReadLockCount`、`isLockStamp`、`isWriteLockStamp`、`isReadLockStamp`、`isOptimisticReadStamp`。`asReadLock`、`asWriteLock`、`asReadWriteLock`。`toString`。スタンプの値は JDK 25 が返す値と同じです |
| `CountDownLatch`、`Semaphore` | 制限時間の指定がある、またはない `await`、`countDown`、`getCount`。`acquire`、`acquireUninterruptibly`、制限時間の指定がある、またはない `tryAcquire`、`release`（いずれも許可 1 つ、または複数）、`availablePermits`、`drainPermits`、`isFair`。どちらにも `toString` があります |
| `CyclicBarrier`、`Exchanger` | スレッド数を取り、アクションの指定がある、またはないコンストラクタ。制限時間の指定がある、またはない `await`、`reset`、`isBroken`、`getParties`、`getNumberWaiting`。制限時間の指定がある、またはない `exchange` |
| `Phaser` | 引数なし、またはパーティ数、親、その両方を取るコンストラクタ。`register`、`bulkRegister`、`arrive`、`arriveAndDeregister`、`arriveAndAwaitAdvance`、`awaitAdvance`、制限時間の指定がある、またはない `awaitAdvanceInterruptibly`、`forceTermination`、`getPhase`、`getRegisteredParties`、`getArrivedParties`、`getUnarrivedParties`、`getParent`、`getRoot`、`isTerminated`、`toString`。オーバーライドできる `onAdvance` |
| `BlockingQueue`、`ArrayBlockingQueue`、`LinkedBlockingQueue`、`SynchronousQueue`、`ConcurrentLinkedQueue` | `Queue` のメンバー。ブロッキングキューには `put`、`take`、制限時間を指定する `offer` と `poll`、`remainingCapacity`、`drainTo` もあります。`SynchronousQueue` は要素を保持せず、要素を入れるスレッドから取り出すスレッドへ直接渡します |
| `TransferQueue`、`LinkedTransferQueue` | ブロッキングキューのメンバー。`transfer`、制限時間の指定がある、またはない `tryTransfer`、`hasWaitingConsumer`、`getWaitingConsumerCount` |
| `BlockingDeque`、`LinkedBlockingDeque`、`ConcurrentLinkedDeque` | `Deque` のメンバー。ブロッキングデックにはブロッキングキューのメンバーと、両端それぞれの `putFirst`、`putLast`、`takeFirst`、`takeLast`、制限時間を指定する `offerFirst`、`offerLast`、`pollFirst`、`pollLast` もあります |
| `ConcurrentMap`、`ConcurrentHashMap` | `Map` のメンバー。キーと値を指定する `remove`、古い値を指定する `replace`、`mappingCount`、`contains`、`keys`、`elements`、値を指定する `keySet`、`ConcurrentHashMap.newKeySet`。引数なし、容量、マップを取るコンストラクタ。キーと値に `null` は使えません |
| `ConcurrentNavigableMap`、`ConcurrentSkipListMap`、`ConcurrentSkipListSet` | `NavigableMap` と `ConcurrentMap` のメンバー、または `NavigableSet` のメンバー。引数なし、コンパレータ、マップ、ソート済みマップ、コレクション、ソート済みセットを取るコンストラクタ。キーと値に `null` は使えません。イテレータは今の位置からキーをたどり、変更を拒否しません |
| `CopyOnWriteArrayList`、`CopyOnWriteArraySet` | `List` のメンバー、または `Set` のメンバー。`addIfAbsent`、`addAllAbsent`。イテレータは作られた時点のリストをたどり、リストを変更しません |
| `Executor`、`ExecutorService`、`Executors` | `execute`、`Callable` または `Runnable` を取る `submit`、制限時間の指定がある、またはない `invokeAll` と `invokeAny`、`shutdown`、`shutdownNow`、`isShutdown`、`isTerminated`、`awaitTermination`、`close`。`newFixedThreadPool`、`newSingleThreadExecutor`、`newCachedThreadPool`、`newScheduledThreadPool`、`newSingleThreadScheduledExecutor`（いずれも `ThreadFactory` の指定がある、またはない）、`newVirtualThreadPerTaskExecutor`、`newThreadPerTaskExecutor`、`unconfigurableExecutorService`、`unconfigurableScheduledExecutorService`、`defaultThreadFactory`、`callable` |
| `ThreadPoolExecutor`、`RejectedExecutionHandler` | コアサイズ、最大サイズ、時間、キューを取り、`ThreadFactory`、ハンドラー、またはその両方の指定がある、またはないコンストラクタ。`AbortPolicy`、`CallerRunsPolicy`、`DiscardPolicy`、`DiscardOldestPolicy`。`getPoolSize`、`getCorePoolSize`、`getMaximumPoolSize`、`getLargestPoolSize`、`getActiveCount`、`getTaskCount`、`getCompletedTaskCount`、`getKeepAliveTime`、`getQueue`、`getThreadFactory`、`getRejectedExecutionHandler`、`setRejectedExecutionHandler`、`setCorePoolSize`、`setMaximumPoolSize`、`setKeepAliveTime`、`setThreadFactory`、`allowCoreThreadTimeOut`、`allowsCoreThreadTimeOut`、`prestartCoreThread`、`prestartAllCoreThreads`、`remove`、`purge`、`isTerminating`、`toString`。オーバーライドできる `beforeExecute`、`afterExecute`、`terminated` |
| `ScheduledExecutorService`、`ScheduledThreadPoolExecutor`、`ScheduledFuture`、`Delayed` | `Runnable` または `Callable` を取る `schedule`、`scheduleAtFixedRate`、`scheduleWithFixedDelay`。コアサイズを取り、`ThreadFactory` の指定がある、またはないコンストラクタ。`setRemoveOnCancelPolicy`、`setExecuteExistingDelayedTasksAfterShutdownPolicy`、`setContinueExistingPeriodicTasksAfterShutdownPolicy` と、それぞれの値を読むメンバー。`getDelay`、`compareTo` |
| `ForkJoinPool`、`ForkJoinTask`、`RecursiveTask`、`RecursiveAction`、`CountedCompleter` | `new ForkJoinPool()`、並列度を取るコンストラクタ、`ForkJoinPool.commonPool`。`invoke`、`execute`、`submit`、`getParallelism`、`getCommonPoolParallelism`、`getPoolSize` と `ExecutorService` のメンバー。`fork`、`join`、`invoke`、`invokeAll`、制限時間の指定がある、またはない `get`、`cancel`、`isDone`、`isCancelled`、`isCompletedNormally`、`isCompletedAbnormally`、`getException`、`complete`、`completeExceptionally`、`quietlyComplete`、`quietlyJoin`、`quietlyInvoke`、`getRawResult`、`ForkJoinTask.adapt`、`inForkJoinPool`、`getPool`。`compute`。`CountedCompleter` には `tryComplete`、`propagateCompletion`、`onCompletion`、`onExceptionalCompletion`、`getCompleter`、`getRoot`、`getPendingCount`、`setPendingCount`、`addToPendingCount`、`compareAndSetPendingCount`、`decrementPendingCountUnlessZero`、`firstComplete`、`nextComplete`、`quietlyCompleteRoot`、`helpComplete` もあります |
| `Timer`、`TimerTask` | 引数なし、名前、デーモンかどうか、その両方を取るコンストラクタ。遅延を取り、周期の指定がある、またはない `schedule`、`scheduleAtFixedRate`、`cancel`、`purge`。`run`、`cancel`、`scheduledExecutionTime` |
| `Callable`、`Future`、`FutureTask`、`ThreadFactory` | `call`。制限時間の指定がある、またはない `get`、`isDone`、`cancel`、`isCancelled`、`state`、`resultNow`、`exceptionNow`。`run`、`toString`。`newThread` |
| `CompletableFuture`、`CompletionStage` | エグゼキューターの指定がある、またはない `supplyAsync` と `runAsync`、`completedFuture`、`failedFuture`、`completedStage`、`failedStage`、`allOf`、`anyOf`。`thenApply`、`thenAccept`、`thenRun`、`thenCompose`、`handle`、`whenComplete`、`thenCombine`、`thenAcceptBoth`、`runAfterBoth`、`applyToEither`、`acceptEither`、`runAfterEither`、`exceptionally`、`exceptionallyCompose`（いずれも、エグゼキューターの指定がある、またはない `Async` 版があります）。`complete`、`completeExceptionally`、`completeAsync`、`obtrudeValue`、`obtrudeException`、`cancel`、`orTimeout`、`completeOnTimeout`、`delayedExecutor`、`copy`、`minimalCompletionStage`、`newIncompleteFuture`、`defaultExecutor`、`join`、制限時間の指定がある、またはない `get`、`getNow`、`isDone`、`isCancelled`、`isCompletedExceptionally`、`getNumberOfDependents`、`state`、`resultNow`、`exceptionNow`、`toString` |
| `java.util.function` | `Function`、`BiFunction`、`UnaryOperator`、`BinaryOperator`、`Supplier`、`Consumer`、`BiConsumer`、`Predicate`、`BiPredicate`、`BooleanSupplier`。`Int`、`Long`、`Double` のそれぞれに `IntFunction`、`IntPredicate`、`IntUnaryOperator`、`IntBinaryOperator`、`IntSupplier`、`IntConsumer`、`ToIntFunction`、`ToIntBiFunction`、`ObjIntConsumer`。3 つの型の間の `IntToLongFunction`、`IntToDoubleFunction`、`LongToIntFunction`、`LongToDoubleFunction`、`DoubleToIntFunction`、`DoubleToLongFunction`。各インターフェースの抽象メソッドと `andThen`、`compose`、`and`、`or`、`negate`、`identity`、`not`、`isEqual` |
| `Duration` | `ZERO`。`ofDays`、`ofHours`、`ofMinutes`、ナノ秒の指定がある、またはない `ofSeconds`、`ofMillis`、`ofNanos`、`parse`。`getSeconds`、`getNano`、`isZero`、`isNegative`、`isPositive`。単位の個数を取る `of`、`between`、`from`。`Duration` または単位の個数を取る `plus` と `minus`、`plusDays` から `plusNanos`、`minusDays` から `minusNanos`、`multipliedBy`、`negated`、`abs`、`withSeconds`、`withNanos`、`truncatedTo`。`get`、`getUnits`、`addTo`、`subtractFrom`。`toDays`、`toHours`、`toMinutes`、`toSeconds`、`toMillis`、`toNanos`、`toDaysPart` から `toNanosPart`。`compareTo`、`equals`、`hashCode`、`toString`。`DateTimeException`、`DateTimeParseException` |
| `LocalDate` | `MIN`、`MAX`、`EPOCH`。ゾーンまたは `Clock` の指定がある、またはない `now`、`of`、`ofYearDay`、`ofEpochDay`、`ofInstant`、文字列だけを取る、またはフォーマッターも取る `parse`、`from`。`getYear`、`getMonth`、`getMonthValue`、`getDayOfMonth`、`getDayOfYear`、`getDayOfWeek`、`isLeapYear`、`lengthOfMonth`、`lengthOfYear`。`withYear` から `withDayOfYear`、`plusYears` から `plusDays`、`minusYears` から `minusDays`。日付までを `Period` で返す `until`。`atTime`、ゾーンの指定がある、またはない `atStartOfDay`、`toEpochDay`、`toEpochSecond`。`isAfter`、`isBefore`、`isEqual`、`compareTo`、`equals`、`hashCode`、`format`、`query`、`toString` |
| `LocalTime` | `MIN`、`MAX`、`MIDNIGHT`、`NOON`。`now`、`of`、`ofSecondOfDay`、`ofNanoOfDay`、`ofInstant`、文字列だけを取る、またはフォーマッターも取る `parse`、`from`。`getHour` から `getNano`。`withHour` から `withNano`、`plusHours` から `plusNanos`、`minusHours` から `minusNanos`、`truncatedTo`。`atDate`、`toSecondOfDay`、`toNanoOfDay`、`toEpochSecond`。`isAfter`、`isBefore`、`compareTo`、`equals`、`hashCode`、`format`、`query`、`toString` |
| `LocalDateTime` | `MIN`、`MAX`。`now`、日付と時刻またはその数値を取る `of`、`ofInstant`、`ofEpochSecond`、文字列だけを取る、またはフォーマッターも取る `parse`、`from`。`toLocalDate`、`toLocalTime` と両者のゲッター。`withYear` から `withNano`、`plusYears` から `plusNanos`、`minusYears` から `minusNanos`、`truncatedTo`。`atOffset`、`atZone`、`toEpochSecond`、`toInstant`。`isAfter`、`isBefore`、`isEqual`、`compareTo`、`equals`、`hashCode`、`format`、`query`、`toString` |
| `OffsetDateTime` | `MIN`、`MAX`。`now`、`of`、`ofInstant`、文字列だけを取る、またはフォーマッターも取る `parse`、`from`。`getOffset`、`withOffsetSameLocal`、`withOffsetSameInstant`、`toLocalDateTime`、`toLocalDate`、`toLocalTime` とゲッター、`LocalDateTime` と同じ `with..`、`plus..`、`minus..`、`truncatedTo`。`toInstant`、`toEpochSecond`、`toZonedDateTime`、`atZoneSameInstant`、`atZoneSimilarLocal`。`isAfter`、`isBefore`、`isEqual`、`compareTo`、`equals`、`hashCode`、`format`、`query`、`toString` |
| `ZonedDateTime` | `now`、`of`、`ofLocal`、`ofInstant`、`ofStrict`、文字列だけを取る、またはフォーマッターも取る `parse`、`from`。`getOffset`、`getZone`、`withZoneSameLocal`、`withZoneSameInstant`、`withFixedOffsetZone`、`withEarlierOffsetAtOverlap`、`withLaterOffsetAtOverlap`、`toLocalDateTime`、`toLocalDate`、`toLocalTime` とゲッター、`LocalDateTime` と同じ `with..`、`plus..`、`minus..`、`truncatedTo`。`toOffsetDateTime`、`toInstant`、`toEpochSecond`。`isAfter`、`isBefore`、`isEqual`、`compareTo`、`equals`、`hashCode`、`format`、`query`、`toString` |
| `Instant` | `EPOCH`、`MIN`、`MAX`。`Clock` の指定がある、またはない `now`、ナノ秒の指定がある、またはない `ofEpochSecond`、`ofEpochMilli`、`parse`、`from`。`getEpochSecond`、`getNano`、`toEpochMilli`。`plusSeconds`、`plusMillis`、`plusNanos`、`minusSeconds`、`minusMillis`、`minusNanos`、`truncatedTo`。`atOffset`、`atZone`。`isAfter`、`isBefore`、`compareTo`、`equals`、`hashCode`、`query`、`toString` |
| `Year`、`YearMonth` | `now`、`of`、文字列だけを取る、またはフォーマッターも取る `parse`、`from`。`Year.isLeap`、`getValue`、`isLeap`、`length`、`plusYears`、`minusYears`、`atDay`、`atMonth`。`getYear`、`getMonth`、`getMonthValue`、`isLeapYear`、`isValidDay`、`lengthOfMonth`、`lengthOfYear`、`withYear`、`withMonth`、`plusYears`、`plusMonths`、`minusYears`、`minusMonths`、`atDay`、`atEndOfMonth`。`isAfter`、`isBefore`、`compareTo`、`equals`、`hashCode`、`format`、`query`、`toString` |
| `Period` | `ZERO`。`of`、`ofYears`、`ofMonths`、`ofWeeks`、`ofDays`、`between`、`parse`、`from`。`getYears`、`getMonths`、`getDays`、`isZero`、`isNegative`、`toTotalMonths`。`withYears` から `withDays`、量を取る `plus` と `minus`、`plusYears` から `plusDays`、`minusYears` から `minusDays`、`multipliedBy`、`negated`、`normalized`。`get`、`getUnits`、`addTo`、`subtractFrom`。`equals`、`hashCode`、`toString` |
| `java.time.temporal` | `Temporal`、`TemporalAccessor`、`TemporalAdjuster`、`TemporalAmount`、`TemporalField`、`TemporalUnit`、`TemporalQuery`、`TemporalQueries`（`zoneId`、`zone`、`offset`、`localDate`、`localTime`、`precision`）、`ChronoField`、`ChronoUnit`、`ValueRange`、`UnsupportedTemporalTypeException`。上の日付と時刻の各クラスには、フィールドまたは単位を取る `isSupported`、`get`、`getLong`、`range`、フィールドまたはアジャスターを取る `with`、量または単位の個数を取る `plus` と `minus`、単位を取る `until`、`adjustInto`、`query` があります。フィールド、単位、アジャスター、量、クエリー、日付はプログラムでも書けます。プログラムの書いた日付は、`from` がフィールドを通して読みます |
| `java.time.chrono` | `ChronoLocalDate`、`ChronoLocalDateTime`、`ChronoZonedDateTime`。`LocalDate`、`LocalDateTime`、`ZonedDateTime` が実装するインターフェースで、変換と順序のメンバーを持ちます |
| `DateTimeFormatter` | `Locale` の指定がある、またはない `ofPattern`。パターンの文字は、[週、四半期、名前の文字](deviations.md)を除くすべて。`ISO_LOCAL_DATE`、`ISO_OFFSET_DATE`、`ISO_DATE`、`ISO_LOCAL_TIME`、`ISO_OFFSET_TIME`、`ISO_TIME`、`ISO_LOCAL_DATE_TIME`、`ISO_OFFSET_DATE_TIME`、`ISO_ZONED_DATE_TIME`、`ISO_DATE_TIME`、`ISO_ORDINAL_DATE`、`ISO_INSTANT`、`BASIC_ISO_DATE`、`RFC_1123_DATE_TIME`。`format`、`formatTo`、文字列だけを取る、またはクエリーも取る `parse`、`parseBest`。`withLocale`、`withResolverStyle`、`withZone` とそれぞれのゲッター。`parsedExcessDays`、`parsedLeapSecond`、`toString`。`DateTimeParseException` |
| `DateTimeFormatterBuilder` | `appendPattern`、幅を 1 つ、または 2 つと `SignStyle` を取る `appendValue`、基準値または基準日を取る `appendValueReduced`、`appendFraction`、スタイルまたは語の対応表を取る `appendText`、`appendLiteral`、`appendOffset`、`appendOffsetId`、`appendLocalizedOffset`、`appendZoneId`、`appendZoneRegionId`、`appendZoneOrOffsetId`、桁数の指定がある、またはない `appendInstant`、`append`、`appendOptional`、`optionalStart`、`optionalEnd`、`padNext`、`parseCaseSensitive`、`parseCaseInsensitive`、`parseStrict`、`parseLenient`、`parseDefaulting`、`Locale` の指定がある、またはない `toFormatter`。`ResolverStyle`、`SignStyle`、`TextStyle` |
| `ZoneOffset`、`ZoneId`、`ZoneRules` | `UTC`、`MIN`、`MAX`。`ZoneOffset.of`、`ofHours`、`ofHoursMinutes`、`ofHoursMinutesSeconds`、`ofTotalSeconds`、`from`、`getTotalSeconds`、`compareTo`。`ZoneId.of`、`ofOffset`、`systemDefault`、`getAvailableZoneIds`、`from`、`getId`、`getRules`、`normalized`、`equals`、`hashCode`、`toString`。オフセットまたはゾーンの変更を取る `ZoneRules.of`、`isFixedOffset`、瞬間またはローカル日時を取る `getOffset` と `getValidOffsets`、`isValidOffset`、`getStandardOffset`、`getDaylightSavings`、`isDaylightSavings`、`getTransition`、`nextTransition`、`previousTransition`、`getTransitions`、`getTransitionRules`。`ZoneRulesException`。プログラムが使える[ゾーン](deviations.md)は、オフセットが変わらないものと、プログラムが名前を書いた地域です |
| `ZoneOffsetTransition`、`ZoneOffsetTransitionRule` | `ZoneOffsetTransition.of`、`getInstant`、`toEpochSecond`、`getDateTimeBefore`、`getDateTimeAfter`、`getOffsetBefore`、`getOffsetAfter`、`getDuration`、`isGap`、`isOverlap`、`isValidOffset`、`compareTo`、`equals`、`hashCode`、`toString`。`ZoneOffsetTransitionRule.of`、`getMonth`、`getDayOfMonthIndicator`、`getDayOfWeek`、`getLocalTime`、`isMidnightEndOfDay`、`getTimeDefinition`、`getStandardOffset`、`getOffsetBefore`、`getOffsetAfter`、`createTransition`、`equals`、`hashCode`、`toString`。`TimeDefinition` と `createDateTime` |
| `Clock` | `systemUTC`、`systemDefaultZone`、`system`、`fixed`、`offset`、`tick`、`tickSeconds`、`tickMinutes`、`tickMillis`。`instant`、`millis`、`getZone`、`withZone`。`Clock` を継承するクラス |
| `DayOfWeek`、`Month` | 各定数。`of`、`from`、`getValue`、`plus`、`minus`。`Month.length`、`minLength`、`maxLength`、`firstDayOfYear`、`firstMonthOfQuarter` |
| `java.util.Date` | `new Date()`、`new Date(long)`、`Date.from`。`getTime`、`setTime`、`before`、`after`、`compareTo`、`equals`、`hashCode`、`toInstant`、`toString` |
| `java.sql.Date`、`Time`、`Timestamp` | モジュール `java.sql` のパッケージです。ミリ秒を取る `new`。文字列、`LocalDate`、`LocalTime`、`LocalDateTime` を取る `valueOf`。`toLocalDate`、`toLocalTime`、`toLocalDateTime`、`toString`。`Timestamp.from`、`toInstant`、`getNanos`、`setNanos`、`Timestamp` を取る `equals`、`compareTo`、`before`、`after`。日時はホストのゾーンで読み書きし、1582-10-15 より前はユリウス暦です |
| `URI`、`URISyntaxException` | `new URI(String)`、`URI.create`。`getScheme`、`getSchemeSpecificPart`、`getAuthority`、`getUserInfo`、`getHost`、`getPort`、`getPath`、`getQuery`、`getFragment` と、それぞれの `getRaw..`。`isAbsolute`、`isOpaque`、`URI` または文字列を取る `resolve`、`equals`、`hashCode`、`compareTo`、`toString`。`getInput`、`getReason`、`getIndex` |
| `HttpClient` | `newHttpClient`。`newBuilder` と、そのビルダーの `version`、`followRedirects`、`executor`、`connectTimeout`、`build`。`send`、`sendAsync`、`followRedirects`、`version`、`executor`、`connectTimeout`、`close`、`shutdown`、`shutdownNow`、`isTerminated`。`HttpClient.Version`、`HttpClient.Redirect` |
| `HttpRequest` | `URI` の指定がある、またはない `newBuilder` と、そのビルダーの `uri`、`header`、`headers`、`setHeader`、`GET`、`POST`、`PUT`、`DELETE`、`HEAD`、`method`、`version`、`expectContinue`、`timeout`、`copy`、`build`。`method`、`uri`、`headers`、`bodyPublisher`、`version`、`expectContinue`、`timeout`、`equals`、`hashCode`、`toString`。`Charset` の指定がある、またはない `BodyPublishers.ofString`、配列またはその範囲を取る `ofByteArray`、`ofInputStream`、`noBody`。`BodyPublisher.contentLength` |
| `HttpResponse` | `statusCode`、`headers`、`body`、`uri`、`request`、`previousResponse`、`version`、`toString`。`Charset` の指定がある、またはない `BodyHandlers.ofString`、`ofByteArray`、`ofInputStream`、`discarding`、`replacing`。同じ名前の `BodySubscribers` と `mapping`。`ResponseInfo`（`statusCode`、`headers`、`version`）を見てサブスクライバーを選ぶ、プログラム独自の `BodyHandler` |
| `HttpHeaders` | `firstValue`、`firstValueAsLong`、`allValues`、`map`、`HttpHeaders.of`、`equals`、`hashCode`、`toString` |
| `HttpTimeoutException`、`HttpConnectTimeoutException` | メッセージを取るコンストラクタ。時間制限を過ぎたときに `send` が投げ、`sendAsync` の future が失敗の原因として持つ例外です |
| `HttpServer`、`HttpContext`、`HttpHandler` | 引数なし、アドレスとバックログ、またはそれにパスとハンドラーとフィルターを加えた引数を取る `HttpServer.create`、`bind`、ハンドラーの指定がある、またはない `createContext`、パスまたはコンテキストを取る `removeContext`、`setExecutor`、`getExecutor`、`start`、`stop`、`getAddress`。`getPath`、`getHandler`、`setHandler`、`getServer`、`getAttributes`、`getFilters`、`setAuthenticator`、`getAuthenticator`。`handle` |
| `HttpExchange`、`Request` | `getRequestMethod`、`getRequestURI`、`getProtocol`、`getRequestHeaders`、`getRequestBody`、`getResponseHeaders`、`sendResponseHeaders`、`getResponseBody`、`getResponseCode`、`close`、`getHttpContext`、`getRemoteAddress`、`getLocalAddress`、`getAttribute`、`setAttribute`、`setStreams`、`getPrincipal`。`with` |
| `Filter`、`HttpHandlers` | `doFilter`、`description`、`Filter.beforeHandler`、`afterHandler`、`adaptRequest`。フィルターのリストとハンドラーを取る `new Filter.Chain` と、その `doFilter`。ステータスとフィールドとボディを取る `HttpHandlers.of`、`handleOrElse` |
| `Authenticator`、`BasicAuthenticator`、`HttpPrincipal` | `authenticate`。`Failure`、`Retry`、`Success` と、その `getResponseCode` または `getPrincipal`。レルムを取り、`Charset` の指定がある、またはない `BasicAuthenticator` のコンストラクタ、`getRealm`、`checkCredentials`。ユーザー名とレルムを取る `new HttpPrincipal`、`getUsername`、`getRealm`、`getName`、`equals`、`hashCode`、`toString` |
| `Headers` | `Map` のメンバー。`getFirst`、`add`、`set`。引数なし、またはマップを取る `new Headers()`、名前と値を交互に並べるかマップを取る `Headers.of` |
| `InetSocketAddress`、`InetAddress`、`BindException`、`UnknownHostException` | ポート、ホストとポート、`InetAddress` とポートを取るコンストラクタ、`createUnresolved`。`getPort`、`getHostString`、`getHostName`、`getAddress`、`isUnresolved`、`equals`、`hashCode`、`toString`。`getHostAddress`、`getHostName`、`getAddress`、`isLoopbackAddress`、`isAnyLocalAddress`、`InetAddress.getLoopbackAddress`、数値で書いたアドレスまたは `localhost` を取る `getByName` と `getAllByName`、`getByAddress` |

`String.format`、`formatted`、`printf` が受け付ける変換は `%d`、`%s`、`%S`、`%f`、`%e`、`%E`、
`%x`、`%X`、`%o`、`%c`、`%b`、`%h`、`%H`、`%n`、`%%` です。フラグ `-`、`0`、`+`、空白、`,`、`(`、`#` と、
幅、精度、引数の番号（`%2$s`）を指定できます。`BigInteger` は `%d`、`%x`、`%X`、`%o` で書けます。
符号は数字の前に付きます。`BigDecimal` は `%f`、`%e`、`%E` で書け、その数値自身の桁を四捨五入します。

`java.util.regex.Pattern` と `Matcher` は、`Pattern` のドキュメントが記す構文を受け付けます。
和と積のある文字クラス、グループ（捕捉、名前付き、アトミック）、先読みと後読み、後方参照、
貪欲・最短・所有の量指定子、インラインフラグ、`\\p{..}` のプロパティ、`\\X` と `\\b{g}` です。
`String.matches`、`replaceAll`、`replaceFirst`、`split` も同じ構文を取ります。不正なパターンは
JDK と同じメッセージの `PatternSyntaxException` になります。`Matcher` はリージョンの中を
検索でき（`region`、`useAnchoringBounds`、`useTransparentBounds`）、検索が入力の終わりを
読んだかどうかを答え（`hitEnd`、`requireEnd`）、マッチごとに置換結果を組み立て
（`appendReplacement`、`appendTail`、関数を取る `replaceAll`）、マッチを保存します
（`toMatchResult`）。`Pattern.splitAsStream` と `Matcher.results` はストリームを返します。
[言語との差異](deviations.md)も参照してください。

ストリームは、終端操作が要素を求めたときに、ソースから要素を 1 つずつ取り出します。ある
ステージに届くのは、それより前のステージをすべて通過した要素だけです。終わりのない
ストリームは `limit`、`takeWhile`、`findFirst` で打ち切れます。`count()` は、ソースと
ステージから要素数がわかる場合、JDK 25 と同じくステージを 1 つも実行しません。
コレクション、配列、範囲に `map`、`peek`、`sorted`、`skip`、`limit`、`boxed` を重ねた場合です。
ストリームは 1 回だけ読めます。2 回目の操作は `IllegalStateException` になります。

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

`java.io` のストリームは、配列、文字列、標準ストリーム、ほかのストリームの上に作ります。
ファイルはありません。`PrintStream`、`PrintWriter`、バイトと文字をつなぐ 2 つのクラスが
受け付ける文字セットは、`UTF-8`（デフォルト）、`ISO-8859-1`、`US-ASCII`、`UTF-16`、`UTF-16BE`、
`UTF-16LE` です。

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

`HashMap`、`HashSet`、`Hashtable` は、キーを `equals` と `hashCode` で探し、JDK と同じ順序で
キーをたどります。`LinkedHashMap` と `LinkedHashSet` は、キーを加えた順にたどります。`TreeMap` と
`TreeSet` は、コンパレータの順にたどります。

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
