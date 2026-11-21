package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaEmitter.Context;
import am.ik.rontolisp.java.JavaEmitter.Invocation;
import am.ik.rontolisp.java.JavaEmitter.Typed;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.java.JavaType.Variable;
import org.jspecify.annotations.Nullable;

import static am.ik.rontolisp.java.JavaForms.call;
import static am.ik.rontolisp.java.JavaForms.cl;
import static am.ik.rontolisp.java.JavaForms.integer;
import static am.ik.rontolisp.java.JavaForms.list;
import static am.ik.rontolisp.java.JavaForms.quote;
import static am.ik.rontolisp.java.JavaForms.runtimeCall;

/**
 * The classes of the library whose values are Lisp values, so that their members are
 * forms and not Java source: a {@code String} is the host's string, a boxed {@code int}
 * the number and a boxed {@code long} the number in a cons, a {@code StringBuilder} an
 * adjustable character vector; and the classes that hold no value at all ({@code Math},
 * {@code System}). Every other class of the library is Java source in the jar, and a
 * member of one that Java cannot say is declared {@code native} there and is a form here
 * ({@link #NATIVES}). Written here from the API specification's names and signatures;
 * nothing of a JDK is read ({@code .kb/java-frontend-provenance.md}). Each class has the
 * members programs reach, not its whole surface: a member missing here is refused by
 * name.
 */
final class JavaIntrinsics {

	private static final String OBJECT = "java.lang.Object";

	private static final String STRING = "java.lang.String";

	private static final String STRING_BUFFER = "java.lang.StringBuffer";

	private static final String NUMBER = "java.lang.Number";

	/**
	 * The three field updaters, whose {@code newUpdater} is lowered where it is called.
	 */
	static final List<String> FIELD_UPDATERS = List.of("java.util.concurrent.atomic.AtomicIntegerFieldUpdater",
			"java.util.concurrent.atomic.AtomicLongFieldUpdater",
			"java.util.concurrent.atomic.AtomicReferenceFieldUpdater");

	private static final String CHARS = "java.lang.CharSequence";

	private static final String COMPARABLE = "java.lang.Comparable";

	private static final String APPENDABLE = "java.lang.Appendable";

	/**
	 * The source class a thread is, which holds what the monitor members are written in.
	 */
	private static final String THREAD = "java.lang.Thread";

	private static final String THROWABLE = "java.lang.Throwable";

	private static final String INTERRUPTED = "java.lang.InterruptedException";

	private static final String UNSUPPORTED_ENCODING = "java.io.UnsupportedEncodingException";

	/** The source class holding the members of these classes that are written in Java. */
	private static final String SUPPORT = "rontolisp.internal.Support";

	/** The server of {@code com.sun.net.httpserver}, Java source over the transport. */
	static final String SERVING = "rontolisp.internal.HttpServing";

	private static final String PATTERN = "java.util.regex.Pattern";

	private static final String SCANNER = "java.util.Scanner";

	/** The natives of the library whose {@code String} may be {@code null}. */
	private static final Set<String> NULLABLE_NATIVES = Set.of(SCANNER + ".findInLine(String)",
			SCANNER + ".findWithinHorizon(String,int)");

	static final String CHARSET = "java.nio.charset.Charset";

	/** The interface of the numbers a format writes by their own digits. */
	static final String EXACT = "rontolisp.internal.Exact";

	private static final String STREAM = "java.util.stream.Stream";

	private static final String INT_STREAM = "java.util.stream.IntStream";

	/** The streams of a string, written in Java in the library's own source. */
	private static final String FLOWS = "rontolisp.internal.Flows";

	/** The source class of the UTF-16 charsets, which only a program naming one makes. */
	private static final String UTF16 = "rontolisp.internal.Utf16";

	private static final String LOCALE = "java.util.Locale";

	private static final String COMPARATOR = "java.util.Comparator";

	/**
	 * What {@code System.out} and {@code System.err} are as the receiver of a print: the
	 * host's stream itself. Their value anywhere else is an object of the library's
	 * {@code java.io.PrintStream}, made the first time one is asked for.
	 */
	static final String STANDARD_STREAM = "rontolisp.internal.StandardStream";

	/** The boxes, and what each holds. */
	private static final Map<String, Primitive> BOXES = Map.of("java.lang.Integer", Primitive.INT, "java.lang.Long",
			Primitive.LONG, "java.lang.Double", Primitive.DOUBLE, "java.lang.Boolean", Primitive.BOOLEAN,
			"java.lang.Character", Primitive.CHAR, "java.lang.Byte", Primitive.BYTE, "java.lang.Short", Primitive.SHORT,
			"java.lang.Float", Primitive.FLOAT);

	private static final LispSymbol OUT = cl("*standard-output*");

	private JavaIntrinsics() {
	}

	/**
	 * The primitive a box class holds, or {@code null} for any other type; a type
	 * variable bounded by a box is the box.
	 */
	static @Nullable Primitive unboxed(JavaType type) {
		if (type instanceof Variable variable) {
			for (JavaType bound : variable.parameter().bounds) {
				Primitive held = unboxed(bound);
				if (held != null) {
					return held;
				}
			}
			return null;
		}
		return type instanceof ClassType boxed ? BOXES.get(boxed.declaration().name) : null;
	}

	/** The box class of a primitive type. */
	static ClassType boxed(Primitive primitive, JavaClasses classes) {
		for (Map.Entry<String, Primitive> box : BOXES.entrySet()) {
			if (box.getValue() == primitive) {
				return classes.libraryType(box.getKey());
			}
		}
		throw new IllegalStateException("no box of " + primitive);
	}

	/** Whether a class's values are told from every other's by {@link #predicate}. */
	static boolean isPlain(JavaClass declaration) {
		return switch (declaration.name) {
			case "java.lang.StringBuilder", STRING_BUFFER, NUMBER -> true;
			default -> BOXES.containsKey(declaration.name);
		};
	}

	/**
	 * The function testing that a value is an instance of an intrinsic class, or
	 * {@code null} for a class holding no value.
	 */
	static @Nullable LispSymbol predicate(JavaClass declaration) {
		return switch (declaration.name) {
			case STRING -> cl("simple-string-p");
			// a boxed int is the number, a boxed long the number in a cons with its tag
			case "java.lang.Integer" -> cl("integerp");
			case "java.lang.Long" -> JavaForms.runtime("long-p");
			case "java.lang.Byte" -> JavaForms.runtime("byte-p");
			case "java.lang.Short" -> JavaForms.runtime("short-p");
			case "java.lang.Float" -> JavaForms.runtime("float-p");
			case "java.lang.Double" -> cl("floatp");
			case "java.lang.Character" -> cl("characterp");
			case "java.lang.Boolean" -> JavaForms.runtime("boolean-p");
			case NUMBER -> JavaForms.runtime("number-p");
			case "java.lang.StringBuilder", STRING_BUFFER -> JavaForms.runtime("sb-p");
			default -> null;
		};
	}

	/**
	 * The static method of the library's Java source answering the charset a name written
	 * as a constant names, for the names programs write, whatever their case;
	 * {@code null} for any other name, which the library looks up where the program runs.
	 * A subset of what {@code Charset.find} takes: a call given such a name reaches that
	 * one charset, where a lookup reaches them all.
	 */
	static JavaClass.@Nullable Accessor charsetNamed(String name) {
		return switch (name.toUpperCase(java.util.Locale.ROOT)) {
			case "UTF-8", "UTF8" -> new JavaClass.Accessor(CHARSET, "utf8");
			case "ISO-8859-1", "ISO8859_1", "LATIN1" -> new JavaClass.Accessor(CHARSET, "latin1");
			case "US-ASCII", "ASCII" -> new JavaClass.Accessor(CHARSET, "ascii");
			case "UTF-16" -> new JavaClass.Accessor(UTF16, "marked");
			case "UTF-16BE" -> new JavaClass.Accessor(UTF16, "big");
			case "UTF-16LE" -> new JavaClass.Accessor(UTF16, "little");
			default -> null;
		};
	}

	private static final String ZONE_ID = "java.time.ZoneId";

	private static final String REGIONAL = ZONE_ID + ".regional()";

	/** The same question, asked where a formatter reads a zone's name. */
	private static final String STEP_REGIONAL = "java.time.format.Step.regional()";

	private static final String ZONE_CHANGES = "java.time.zone.ZoneChanges";

	private static final String FOREIGN = "java.time.Iso.foreign()";

	private static final String WANTED = "java.time.format.DateTimeFormatter.wanted()";

	private static final String ZONE_WANTED = ZONE_ID + ".wanted()";

	/** The question of a calendar, asked in each package that answers it. */
	private static final List<String> CALENDARS = List.of("java.time.Iso.calendars()",
			"java.time.temporal.TemporalQueries.calendars()", "java.time.format.Parsed.calendars()");

	private static final String FORMATTER = "java.time.format.DateTimeFormatter";

	private static final String FORMATTER_BUILDER = "java.time.format.DateTimeFormatterBuilder";

	/**
	 * What a pattern's run of three or more of M, L, e or c notes beside its letter: a
	 * field in words.
	 */
	static final char WORDS = '#';

	/**
	 * What a pattern's run of two of u or y notes beside its letter: a year of two
	 * digits.
	 */
	static final char TWO_DIGITS = '2';

	/**
	 * The letters of a pattern each question of Patterns asks for, with {@link #WORDS},
	 * {@link #TWO_DIGITS} and the {@code [} that opens an optional section.
	 */
	private static final Map<String, String> PATTERN_LETTERS = Map.ofEntries(
			Map.entry("java.time.format.Patterns.weeks()", "YwWec"),
			Map.entry("java.time.format.Patterns.quarters()", "Qq"),
			Map.entry("java.time.format.Patterns.julian()", "g"), Map.entry("java.time.format.Patterns.zones()", "zv"),
			Map.entry("java.time.format.Patterns.periods()", "B"),
			Map.entry("java.time.format.Patterns.localizedOffsets()", "OZ"),
			Map.entry("java.time.format.Patterns.words()", "GEa" + WORDS),
			Map.entry("java.time.format.Patterns.reduced()", String.valueOf(TWO_DIGITS)),
			Map.entry("java.time.format.Patterns.fractions()", "S"),
			Map.entry("java.time.format.Patterns.zoneIds()", "V"),
			Map.entry("java.time.format.Patterns.offsets()", "XxZ"), Map.entry("java.time.format.Patterns.pads()", "p"),
			Map.entry("java.time.format.Patterns.optionals()", "["));

	/**
	 * The questions answered by whether the program makes an instance of a class of the
	 * library: the words of a quarter where it makes the fields of IsoFields, the
	 * resolving of a day's period where it makes the part that reads one, the reading of
	 * a zone's name in words where it makes the part that writes and reads one, the
	 * formatter's locale handed to its parts where it makes the part of a requested
	 * template.
	 */
	private static final Map<String, String> MADE = Map.of("java.time.format.Texts.quarters()",
			"java.time.temporal.IsoFields.Field", "java.time.format.Parsed.periods()",
			"java.time.format.Step.DayPeriod", "java.time.format.Parsed.zoneTexts()", "java.time.format.Step.ZoneText",
			"java.time.format.Step.zoneTexts()", "java.time.format.Step.ZoneText", "java.time.format.Step.templated()",
			"java.time.format.Step.Templated");

	/** A calendar of the program's own: a class under {@code Chronology} but ISO's. */
	private static final List<String> OWN_CALENDAR = List.of("java.time.chrono.Chronology",
			"java.time.chrono.IsoChronology");

	/**
	 * The questions answered by whether the program makes an instance of a class under
	 * one of the library's other than one of its own: the resolving of a field that is no
	 * {@code ChronoField} where it makes one; where it makes a calendar of its own, the
	 * refusal of a calendar of no type and what a value of another calendar is written,
	 * read, ordered and added in (each package asking its own).
	 */
	private static final Map<String, List<String>> OTHERS = Map.of("java.time.format.Parsed.others()",
			List.of("java.time.temporal.TemporalField", "java.time.temporal.ChronoField"),
			"java.time.format.Texts.ownCalendars()", OWN_CALENDAR, "java.time.Iso.ownCalendars()", OWN_CALENDAR,
			"java.time.temporal.IsoFields.ownCalendars()", OWN_CALENDAR);

	/**
	 * The questions answered by whether the program reaches a method of the library: the
	 * characters of a formatter's numbers where it can give one a decimal style, the
	 * cutting of the fields read where it can name the fields to resolve, a lenient
	 * resolving where it can give a resolver style that is no read of STRICT or SMART, a
	 * zoned date-time of a calendar of its own written where it can give a formatter a
	 * zone, every part a pattern can make where it makes the parts of a localized form's
	 * or a requested template's pattern.
	 */
	private static final Map<String, String> REACHES = Map.of("java.time.format.DateTimeFormatter.decimals()",
			FORMATTER + ".withDecimalStyle", "java.time.format.Parsed.filtered()", FORMATTER + ".withResolverFields",
			"java.time.format.Parsed.lenients()", FORMATTER + ".anyStyle", "java.time.format.DateTimeFormatter.zones()",
			FORMATTER + ".withZone", "java.time.format.Patterns.localizes()", FORMATTER_BUILDER + ".parts");

	/**
	 * Whether reaching a method of the library, by its class and name, answers a
	 * question.
	 */
	static boolean watched(String member) {
		return REACHES.containsValue(member);
	}

	private static final String ZONED = "java.time.ZonedDateTime";

	private static final String ZONE_RULES_PROVIDER = "java.time.zone.ZoneRulesProvider";

	/**
	 * What a member of the library's Java source with no parameter asks of the program it
	 * is lowered into, where it is such a question; {@code null} for any other member.
	 * The lowering answers it before it lowers what the answer decides, so the statement
	 * or the operand not taken is in no program: a program holding no region of the tz
	 * database has nothing of what a region needs, one making no date of a formatter's
	 * nothing of reading a date's fields, one reading no ISO formatter none of them.
	 */
	static @Nullable Boolean answered(String member, JavaEmitter.Context lowering) {
		return switch (member) {
			case REGIONAL, STEP_REGIONAL -> lowering.holdsRegions();
			case FOREIGN -> lowering.foreignTemporals();
			case WANTED, ZONE_WANTED -> lowering.wantedField();
			default -> OTHERS.containsKey(member)
					? Boolean.valueOf(lowering.makesOther(OTHERS.get(member).get(0), OTHERS.get(member).get(1)))
					: CALENDARS.contains(member) ? Boolean.valueOf(lowering.asksCalendars())
							: PATTERN_LETTERS.containsKey(member)
									? Boolean.valueOf(lowering.namesLetter(PATTERN_LETTERS.get(member)))
									: MADE.containsKey(member) ? Boolean.valueOf(lowering.makesClass(MADE.get(member)))
											: REACHES.containsKey(member)
													? Boolean.valueOf(lowering.reaches(REACHES.get(member))) : null;
		};
	}

	/** The intrinsic classes, as the class table asks for them. */
	static final JavaClasses.Library LIBRARY = new JavaClasses.Library() {
		@Override
		public @Nullable JavaClass create(String name, JavaClasses classes) {
			return JavaIntrinsics.create(name, classes);
		}

		@Override
		public List<String> implemented(String name) {
			return switch (name) {
				case STRING -> List.of(COMPARABLE, CHARS);
				case "java.lang.StringBuilder", STRING_BUFFER -> List.of(CHARS, APPENDABLE);
				default -> BOXES.containsKey(name) ? List.of(COMPARABLE) : List.of();
			};
		}

		@Override
		public @Nullable JavaEmitter emitter(String member) {
			return NATIVES.get(member);
		}

		@Override
		public boolean answersNull(String member) {
			return NULLABLE_NATIVES.contains(member);
		}

		@Override
		public void meets(JavaClass intrinsic, JavaClass named, JavaClasses classes) {
			JavaIntrinsics.meets(intrinsic, named, classes);
		}
	};

	/**
	 * The classes of the library's Java source that the members of a name have in their
	 * types, in an intrinsic class: {@link #meets} adds those members once the class is
	 * in the table.
	 */
	static List<String> named(String intrinsic, String member) {
		boolean builder = intrinsic.equals("java.lang.StringBuilder") || intrinsic.equals(STRING_BUFFER);
		if (member.equals("subSequence") && (intrinsic.equals(STRING) || builder)) {
			return List.of(CHARS);
		}
		if ((member.equals("codePointAt") || member.equals("codePointCount"))
				&& intrinsic.equals("java.lang.Character")) {
			return List.of(CHARS);
		}
		if (intrinsic.equals(STRING) && (member.equals("getBytes") || member.equals("<init>"))) {
			return List.of(CHARSET);
		}
		if ((member.equals("chars") || member.equals("codePoints")) && (intrinsic.equals(STRING) || builder)) {
			return List.of(INT_STREAM);
		}
		if (intrinsic.equals(STRING) && member.equals("lines")) {
			return List.of(STREAM);
		}
		if (intrinsic.equals(STRING) && member.equals("CASE_INSENSITIVE_ORDER")) {
			return List.of(COMPARATOR);
		}
		boolean formats = member.equals("format") || member.equals("printf");
		if ((intrinsic.equals(STRING)
				&& (member.equals("format") || member.equals("toUpperCase") || member.equals("toLowerCase")))
				|| (intrinsic.equals(STANDARD_STREAM) && formats)) {
			return List.of(LOCALE);
		}
		return List.of();
	}

	/**
	 * Adds to an intrinsic class the members whose types name a class of the library's
	 * Java source, once a program names that class: named from the start, every program
	 * would hold the class.
	 */
	private static void meets(JavaClass intrinsic, JavaClass named, JavaClasses classes) {
		boolean text = intrinsic.name.equals(STRING);
		boolean builder = intrinsic.name.equals("java.lang.StringBuilder") || intrinsic.name.equals(STRING_BUFFER);
		if (named.name.equals(CHARS) && (text || builder)) {
			JavaEmitter emitter = runtime(text ? "substring" : "sb-substring");
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "subSequence", List.of(Primitive.INT, Primitive.INT),
					new ClassType(named, List.of()), false, false, null,
					intrinsic.name.equals(STRING_BUFFER) ? guarded(emitter) : emitter, false));
		}
		if (named.name.equals(CHARS) && intrinsic.name.equals("java.lang.Character")) {
			// the members of a sequence of characters: a code point and a count of them
			ClassType characters = new ClassType(named, List.of());
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "codePointAt", List.of(characters, Primitive.INT),
					Primitive.INT, true, false, null, support("sequenceCodePointAt"), false));
			intrinsic.methods.add(
					new JavaClass.Method(intrinsic, "codePointCount", List.of(characters, Primitive.INT, Primitive.INT),
							Primitive.INT, true, false, null, support("sequenceCodePointCount"), false));
		}
		if (named.name.equals(APPENDABLE) && builder) {
			// the three members as the interface spells them, which a call through it
			// looks for by signature
			ClassType self = new ClassType(intrinsic, List.of());
			ClassType characters = classes.libraryType(CHARS);
			boolean held = intrinsic.name.equals(STRING_BUFFER);
			JavaEmitter whole = (lowering, call) -> runtimeCall("sb-append", call.self(), text(lowering, call, 0));
			JavaEmitter one = (lowering, call) -> runtimeCall("sb-append-char", call.self(), call.argument(0));
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "append", List.of(characters), self, false, false,
					null, held ? guarded(whole) : whole, false));
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "append", List.of(Primitive.CHAR), self, false, false,
					null, held ? guarded(one) : one, false));
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "append",
					List.of(characters, Primitive.INT, Primitive.INT), self, false, false, null,
					held ? guarded(JavaIntrinsics::appendRange) : JavaIntrinsics::appendRange, false));
		}
		if (named.name.equals(CHARSET) && text) {
			ClassType charset = new ClassType(named, List.of());
			ArrayType bytes = new ArrayType(Primitive.BYTE);
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "getBytes", List.of(charset), bytes, false, false,
					null, support("encode"), false));
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "<init>", List.of(bytes, charset), Primitive.VOID,
					false, false, null, support("decode"), false));
			intrinsic.methods
				.add(new JavaClass.Method(intrinsic, "<init>", List.of(bytes, Primitive.INT, Primitive.INT, charset),
						Primitive.VOID, false, false, null, support("decode"), false));
		}
		if (named.name.equals(LOCALE) && (text || intrinsic.name.equals(STANDARD_STREAM))) {
			// every locale there is writes a number and a letter's case as no locale
			// does: the argument is evaluated and dropped
			ClassType locale = new ClassType(named, List.of());
			ClassType string = classes.libraryType(STRING);
			List<JavaType> formatted = List.of(locale, string, new ArrayType(classes.libraryType(OBJECT)));
			if (text) {
				JavaClass.Method format = new JavaClass.Method(intrinsic, "format", formatted, string, true, false,
						null, (lowering, call) -> after(call.argument(0),
								formatted(lowering, call.arguments().get(1), call.argument(2))),
						false);
				format.varargs = true;
				intrinsic.methods.add(format);
				for (String cased : List.of("toUpperCase", "toLowerCase")) {
					String piece = cased.equals("toUpperCase") ? "%string-upcase-piece" : "%string-downcase-piece";
					intrinsic.methods.add(new JavaClass.Method(intrinsic, cased, List.of(locale), string, false, false,
							null, (lowering, call) -> after(call.argument(0), call(piece, call.self())), false));
				}
			}
			else {
				for (String name : List.of("printf", "format")) {
					JavaClass.Method print = new JavaClass.Method(intrinsic, name, formatted, Primitive.VOID, false,
							false, null,
							(lowering, call) -> after(call.argument(0), printf(lowering, new Invocation(call.receiver(),
									call.arguments().subList(1, call.arguments().size()), call.discard(), call.pos()))),
							false);
					print.varargs = true;
					intrinsic.methods.add(print);
				}
			}
		}
		if (named.name.equals(INT_STREAM) && (text || builder)) {
			// the UTF-16 units toCharArray answers, and the code points that are a
			// string's elements
			for (String name : List.of("chars", "codePoints")) {
				JavaEmitter stream = (lowering, call) -> lowering.library(FLOWS, name,
						List.of(text ? call.self() : runtimeCall("sb-string", call.self())));
				intrinsic.methods.add(new JavaClass.Method(intrinsic, name, List.of(), new ClassType(named, List.of()),
						false, false, null, intrinsic.name.equals(STRING_BUFFER) ? guarded(stream) : stream, false));
			}
		}
		if (named.name.equals(STREAM) && text) {
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "lines", List.of(),
					new ClassType(named, List.of(new ClassType(intrinsic, List.of()))), false, false, null,
					(lowering, call) -> lowering.library(FLOWS, "lines", List.of(call.self())), false));
		}
		if (named.name.equals(COMPARATOR) && text) {
			JavaClass.Field order = new JavaClass.Field(intrinsic, "CASE_INSENSITIVE_ORDER",
					new ClassType(named, List.of(new ClassType(intrinsic, List.of()))), true, true, null, null);
			order.accessor = new JavaClass.Accessor("rontolisp.internal.CaseInsensitive", "order");
			intrinsic.fields.add(order);
		}
		if (named.name.equals("java.lang.Iterable") && text) {
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "join",
					List.of(classes.libraryType(OBJECT), new ClassType(named, List.of())),
					new ClassType(intrinsic, List.of()), true, false, null, (lowering, call) -> lowering
						.library(SUPPORT, "join", List.of(text(lowering, call, 0), call.argument(1))),
					false));
		}
	}

	// ---- emitters

	/**
	 * {@code append} of a range: of an array of characters by offset and count, of
	 * anything else -- a text, {@code null} read as its four letters -- by start and end.
	 */
	private static LispVal appendRange(Context lowering, Invocation call) {
		boolean array = call.arguments().get(0).type().equals(new ArrayType(Primitive.CHAR));
		return lowering.library(SUPPORT, array ? "appendChars" : "appendRange",
				List.of(call.self(), array ? call.argument(0) : lowering.stringOf(call.arguments().get(0), call.pos()),
						call.argument(1), call.argument(2)));
	}

	/** A form whose value is another's, evaluated after one that is only evaluated. */
	private static LispVal after(LispVal dropped, LispVal form) {
		return JavaForms.isSimple(dropped) ? form : call("progn", dropped, form);
	}

	/** A member that is a call of a run-time function: its receiver, its arguments. */
	private static JavaEmitter runtime(String name) {
		return (lowering, call) -> JavaForms.form(JavaForms.runtime(name), operands(call));
	}

	/**
	 * A request handed to fetch: the future in a vector, or the text of what fetch
	 * refused at the call.
	 */
	private static LispVal fetched(JavaEmitter.Context lowering, LispVal url, LispVal options) {
		LispSymbol condition = lowering.temporary();
		return list(cl("handler-case"),
				runtimeCall("http-exchange", list(new LispSymbol("RONTOLISP:FETCH"), url, options)),
				list(cl("error"), list(condition), call("princ-to-string", condition)));
	}

	/** A member that is a call of a host operator: its receiver, its arguments. */
	private static JavaEmitter host(String name) {
		return (lowering, call) -> JavaForms.form(cl(name), operands(call));
	}

	/** A member that is a static method of another class of the library's Java source. */
	private static JavaEmitter library(String className, String name) {
		return (lowering, call) -> lowering.library(className, name, operands(call));
	}

	/** A member written in Java, in the library's own source. */
	private static JavaEmitter support(String name) {
		return (lowering, call) -> lowering.library(SUPPORT, name, operands(call));
	}

	/**
	 * A member that is a function of the regular-expression engine every front end shares
	 * ({@code eval/regex.lisp}): its arguments.
	 */
	private static JavaEmitter engine(String name) {
		return (lowering, call) -> JavaForms.form(engineFunction(name), operands(call));
	}

	/**
	 * A member that is a function of {@code java.net.URI}'s rules as the front ends share
	 * them ({@code eval/uri.lisp}): its arguments.
	 */
	private static JavaEmitter shared(String name) {
		return (lowering, call) -> JavaForms.form(sharedFunction(name), operands(call));
	}

	private static LispSymbol sharedFunction(String name) {
		return new LispSymbol("RONTOLISP::%URI-" + name.toUpperCase(java.util.Locale.ROOT));
	}

	private static LispSymbol engineFunction(String name) {
		return new LispSymbol("RONTOLISP::%REGEX-" + name.toUpperCase(java.util.Locale.ROOT));
	}

	/**
	 * The engine's providers a pattern is compiled with, as three forms: of the Unicode
	 * properties, of the word characters past ASCII and of the grapheme clusters. The
	 * engine reaches its tables only through them, so a program carries a table only
	 * where a pattern may name what reads it: a pattern written as a constant is read for
	 * a backslash before {@code p}, {@code P}, {@code b}, {@code B} or {@code X} and for
	 * the {@code U} flag (too wide a yes costs the tables, never an answer), any other
	 * one may name anything.
	 * @param regex the pattern
	 * @param flags the flags it is compiled under, or {@code null} for none
	 * @return the three forms, {@code nil} for a provider the pattern cannot need
	 */
	private static List<LispVal> regexProviders(Typed regex, @Nullable Typed flags) {
		boolean unicode = true;
		boolean category = true;
		boolean grapheme = true;
		if (regex.constant() instanceof String text) {
			boolean anyFlags = flags != null && !(flags.constant() instanceof Number);
			int known = flags != null && flags.constant() instanceof Number number ? number.intValue() : 0;
			unicode = anyFlags || (known & 256) != 0 || text.contains("\\p") || text.contains("\\P")
					|| text.contains("(?") && text.indexOf('U') >= 0;
			category = unicode || text.contains("\\b") || text.contains("\\B");
			grapheme = text.contains("\\X") || text.contains("\\b") && text.contains("{g");
		}
		return List.of(unicode ? list(engineFunction("unicode-provider")) : JavaForms.NIL,
				category ? list(engineFunction("category-provider")) : JavaForms.NIL,
				grapheme ? list(engineFunction("grapheme-provider")) : JavaForms.NIL);
	}

	/** A call of one of {@code Pattern}'s entries: the arguments, then the providers. */
	private static LispVal pattern(Context lowering, String entry, List<LispVal> arguments, Typed regex,
			@Nullable Typed flags) {
		List<LispVal> all = new ArrayList<>(arguments);
		all.addAll(regexProviders(regex, flags));
		return lowering.library(PATTERN, entry, all);
	}

	/**
	 * A member of {@code Scanner} taking a pattern's text: the static member of
	 * {@code bridge} with the scanner, the text where {@code text} says, the pattern
	 * compiled here with the providers the text may need, and the arguments after the
	 * text. The scanner and the text are taken once, in that order.
	 */
	private static JavaEmitter scanning(String bridge, boolean text) {
		return (lowering, call) -> {
			LispSymbol scanner = lowering.temporary();
			LispSymbol regex = lowering.temporary();
			Typed written = call.arguments().get(0);
			List<LispVal> arguments = new ArrayList<>();
			arguments.add(scanner);
			if (text) {
				arguments.add(regex);
			}
			arguments.add(pattern(lowering, "make", List.of(regex, integer(0)), written, null));
			for (int i = 1; i < call.arguments().size(); i++) {
				arguments.add(call.argument(i));
			}
			return JavaForms.let(scanner, call.self(), List
				.of(JavaForms.let(regex, written.form(), List.of(lowering.library(SCANNER, bridge, arguments)))));
		};
	}

	/**
	 * Whether a pattern written as a constant is a text with no operator, which
	 * {@code String.split} cuts by without the engine.
	 */
	private static boolean plainText(Typed regex) {
		if (!(regex.constant() instanceof String text) || text.isEmpty()) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			if ("\\.[]{}()*+?^$|".indexOf(text.charAt(i)) >= 0) {
				return false;
			}
		}
		return true;
	}

	private static LispVal split(Context lowering, Invocation call, LispVal limit) {
		Typed regex = call.arguments().get(0);
		return plainText(regex) ? runtimeCall("split-text", call.self(), regex.form(), limit)
				: pattern(lowering, "splitText", List.of(call.self(), regex.form(), limit), regex, null);
	}

	/** A member that is one of fdlibm's functions over doubles. */
	private static JavaEmitter strict(String name) {
		return (lowering, call) -> JavaForms.form(cl("%strict-math"),
				prepend(new LispSymbol(":" + name.toUpperCase(java.util.Locale.ROOT)), operands(call)));
	}

	/**
	 * Whether a form reads a static field: its variable, after the initialization of its
	 * class where that is another's.
	 */
	private static boolean reads(LispVal form, String field) {
		LispVal value = form;
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && head.name().equals("PROGN")) {
			List<LispVal> body = cons.toList();
			value = body.get(body.size() - 1);
		}
		return value instanceof LispSymbol symbol && symbol.name().equals(field);
	}

	/**
	 * Whether the style of a localized form's time is short, medium or none, so that its
	 * pattern names no zone in words: a read of one of those constants or a null.
	 */
	private static boolean briefTime(LispVal form) {
		return form.equals(JavaForms.NIL) || reads(form, "java.time.format.FormatStyle.SHORT")
				|| reads(form, "java.time.format.FormatStyle.MEDIUM");
	}

	/** A formatter's pattern names its letters. */
	private static final JavaEmitter.Naming PATTERN_NAMING = (lowering, text) -> lowering.namesPattern(text);

	/** A requested template names the letters its patterns can hold. */
	private static final JavaEmitter.Naming TEMPLATE_NAMING = (lowering, text) -> lowering
		.namesPattern(templateLetters(text));

	/** A zone's id names its region. */
	private static final JavaEmitter.Naming ZONE_NAMING = (lowering, text) -> lowering.namesZone(text);

	/**
	 * The letters of a pattern that a requested template's patterns can hold and that a
	 * formatter makes only where a pattern names them: a week's and its year's of w, a
	 * quarter's of Q, a period's of the day of B, a zone's of v; all of them for a
	 * template computed where the program runs. A template's other letters find patterns
	 * of letters every formatter of a template has (Patterns.localizes()), and z finds
	 * none.
	 */
	static String templateLetters(@Nullable String template) {
		if (template == null) {
			return "wYQBv";
		}
		StringBuilder letters = new StringBuilder();
		if (template.indexOf('w') >= 0) {
			letters.append("wY");
		}
		for (char letter : new char[] { 'Q', 'B', 'v' }) {
			if (template.indexOf(letter) >= 0) {
				letters.append(letter);
			}
		}
		return letters.toString();
	}

	private static List<LispVal> operands(Invocation call) {
		List<LispVal> operands = new ArrayList<>();
		if (call.receiver() != null) {
			operands.add(call.self());
		}
		for (Typed argument : call.arguments()) {
			operands.add(argument.form());
		}
		return operands;
	}

	private static List<LispVal> prepend(LispVal first, List<LispVal> rest) {
		List<LispVal> all = new ArrayList<>();
		all.add(first);
		all.addAll(rest);
		return all;
	}

	/**
	 * A member of a class every member of which holds its receiver's monitor
	 * ({@code StringBuffer}): the same form, held in a program that can tell.
	 */
	private static JavaEmitter guarded(JavaEmitter emitter) {
		return (lowering, call) -> lowering.holding(call, emitter);
	}

	private static final class Builder {

		private final JavaClass made;

		/** Whether every instance member holds its receiver's monitor. */
		private final boolean guarded;

		Builder(String name, boolean isAbstract, JavaClasses classes) {
			this.made = new JavaClass(name, false, isAbstract, true, null, null);
			this.guarded = name.equals(STRING_BUFFER);
			classes.register(this.made);
		}

		Builder method(String name, JavaType returnType, JavaEmitter emitter, JavaType... parameters) {
			this.made.methods.add(new JavaClass.Method(this.made, name, Arrays.asList(parameters), returnType, false,
					false, null, guarded(name, emitter), false));
			return this;
		}

		/**
		 * A member a class of Java source under this one has to declare: abstract, and
		 * the form given answers for the values that are Lisp values.
		 */
		Builder required(String name, JavaType returnType, JavaEmitter emitter) {
			this.made.methods
				.add(new JavaClass.Method(this.made, name, List.of(), returnType, false, true, null, emitter, false));
			return this;
		}

		private JavaEmitter guarded(String name, JavaEmitter emitter) {
			return this.guarded && !name.equals("<init>") ? JavaIntrinsics.guarded(emitter) : emitter;
		}

		Builder statik(String name, JavaType returnType, JavaEmitter emitter, JavaType... parameters) {
			this.made.methods.add(new JavaClass.Method(this.made, name, Arrays.asList(parameters), returnType, true,
					false, null, emitter, false));
			return this;
		}

		/** A member of one argument of any type, which it takes as it is typed. */
		Builder any(String name, boolean isStatic, JavaType returnType, JavaType parameter, JavaEmitter emitter) {
			this.made.methods.add(new JavaClass.Method(this.made, name, List.of(parameter), returnType, isStatic, false,
					null, isStatic ? emitter : guarded(name, emitter), true));
			return this;
		}

		/** The member added last may answer {@code null} where it answers a text. */
		Builder nullable() {
			this.made.methods.get(this.made.methods.size() - 1).answersNull = true;
			return this;
		}

		/**
		 * The member added last throws checked exceptions, named here: the classes are
		 * read where a call of it is lowered.
		 */
		Builder throwing(String... exceptions) {
			this.made.methods.get(this.made.methods.size() - 1).raises = List.of(exceptions);
			return this;
		}

		/** The member added last takes the arguments past its others as an array. */
		Builder varargs() {
			this.made.methods.get(this.made.methods.size() - 1).varargs = true;
			return this;
		}

		Builder constructor(JavaEmitter emitter, JavaType... parameters) {
			return method("<init>", Primitive.VOID, emitter, parameters);
		}

		/**
		 * A field holding an object of the library's Java source, which the static method
		 * named as the field answers, and what it is as the receiver of a member that
		 * needs no object.
		 */
		Builder standard(String name, JavaType type, String source, @Nullable Typed direct) {
			return standard(name, type, new JavaClass.Accessor(source, name), direct);
		}

		Builder standard(String name, JavaType type, JavaClass.Accessor accessor, @Nullable Typed direct) {
			JavaClass.Field field = new JavaClass.Field(this.made, name, type, true, true, null, null);
			field.accessor = accessor;
			field.direct = direct;
			this.made.fields.add(field);
			return this;
		}

		Builder field(String name, JavaType type, LispVal form) {
			this.made.fields.add(new JavaClass.Field(this.made, name, type, true, true, null, form));
			return this;
		}

	}

	/**
	 * Makes the intrinsic class of a qualified name and adds it to the table.
	 * @param name the qualified name
	 * @param classes the table
	 * @return the class, or {@code null} when the name is no intrinsic class's
	 */
	private static @Nullable JavaClass create(String name, JavaClasses classes) {
		switch (name) {
			case OBJECT: {
				Builder builder = new Builder(name, false, classes);
				ClassType object = new ClassType(builder.made, List.of());
				// an Object that is no other class's is a vector nothing else makes
				builder
					.constructor((lowering, call) -> list(cl("make-array"), integer(0), new LispSymbol(":FILL-POINTER"),
							integer(0)))
					.method("toString", classes.libraryType(STRING),
							(lowering, call) -> lowering.objectMethod("toString()",
									java.util.Objects.requireNonNull(call.receiver()), call))
					.method("equals", Primitive.BOOLEAN,
							(lowering, call) -> lowering.objectMethod("equals(Object)",
									java.util.Objects.requireNonNull(call.receiver()), call),
							object)
					.method("hashCode", Primitive.INT,
							(lowering, call) -> lowering.objectMethod("hashCode()",
									java.util.Objects.requireNonNull(call.receiver()), call))
					// the monitor members are Java over the run time's: Thread.java
					// throws
					// their exceptions
					.method("wait", Primitive.VOID,
							(lowering, call) -> lowering.library(THREAD, "await", List.of(call.self(), integer(0))))
					.throwing(INTERRUPTED)
					.method("wait", Primitive.VOID,
							(lowering, call) -> lowering.library(THREAD, "await",
									List.of(call.self(), call.argument(0))),
							Primitive.LONG)
					.throwing(INTERRUPTED)
					.method("notify", Primitive.VOID,
							(lowering, call) -> lowering.library(THREAD, "signal", List.of(call.self(), JavaForms.NIL)))
					.method("notifyAll", Primitive.VOID,
							(lowering, call) -> lowering.library(THREAD, "signal", List.of(call.self(), JavaForms.T)));
				return builder.made;
			}
			case STRING:
				return string(new Builder(name, false, classes), classes);
			case "java.lang.StringBuilder":
			case STRING_BUFFER:
				return stringBuilder(new Builder(name, false, classes), classes);
			case "java.lang.Math":
			case "java.lang.StrictMath":
				return math(new Builder(name, false, classes));
			case NUMBER:
				// the four a class under it has to declare are the boxes' here; the two
				// it has a body for narrow what the receiver's class answers as an int
				return new Builder(name, true, classes).required("intValue", Primitive.INT, runtime("number-int"))
					.required("longValue", Primitive.LONG, runtime("number-long"))
					.required("floatValue", Primitive.FLOAT,
							(lowering, call) -> call("%single-float", runtimeCall("number-double", call.self())))
					.required("doubleValue", Primitive.DOUBLE, runtime("number-double"))
					.method("byteValue", Primitive.BYTE,
							(lowering, call) -> JavaForms.signed(lowering.member(NUMBER, "intValue()", call.receiver()),
									8))
					.method("shortValue", Primitive.SHORT, (lowering, call) -> JavaForms
						.signed(lowering.member(NUMBER, "intValue()", call.receiver()), 16)).made;
			case "java.lang.Integer":
				return integerClass(new Builder(name, false, classes), classes);
			case "java.lang.Long":
				return longClass(new Builder(name, false, classes), classes);
			case "java.lang.Double":
				return doubleClass(new Builder(name, false, classes), classes);
			case "java.lang.Float":
				return floatClass(new Builder(name, false, classes), classes);
			case "java.lang.Byte":
				return smallClass(new Builder(name, false, classes), classes, Primitive.BYTE);
			case "java.lang.Short":
				return smallClass(new Builder(name, false, classes), classes, Primitive.SHORT);
			case "java.lang.Boolean":
				return booleanClass(new Builder(name, false, classes), classes);
			case "java.lang.Character":
				return character(new Builder(name, false, classes), classes);
			case "java.lang.System": {
				ClassType object = classes.libraryType(OBJECT);
				Builder system = new Builder(name, false, classes);
				ClassType stream = classes.libraryType("java.io.PrintStream");
				ClassType direct = classes.libraryType(STANDARD_STREAM);
				system.standard("out", stream, "java.io.StandardOutput", new Typed(OUT, direct))
					.standard("err", stream, "java.io.StandardOutput", new Typed(cl("*error-output*"), direct))
					.standard("in", classes.libraryType("java.io.InputStream"), "java.io.StandardInput", null);
				return system
					.statik("currentTimeMillis", Primitive.LONG,
							(lowering, call) -> call("values",
									call("floor", call("%unix-nano-time"), integer(1000000))))
					.statik("nanoTime", Primitive.LONG, host("%nano-time"))
					.statik("exit", Primitive.VOID, runtime("exit"), Primitive.INT)
					.statik("arraycopy", Primitive.VOID, (lowering, call) -> runtimeCall("arraycopy", call.argument(0),
							call.argument(1), call.argument(2), call.argument(3), call.argument(4),
							arrayKind(call.arguments().get(0).type()), arrayKind(call.arguments().get(2).type())),
							object, Primitive.INT, object, Primitive.INT, Primitive.INT)
					.statik("identityHashCode", Primitive.INT, runtime("identity-hash-code"), object)
					.statik("getProperty", classes.libraryType(STRING), support("property"),
							classes.libraryType(STRING))
					.nullable()
					// the host's variable, null where it has none: the function every
					// front end reads the environment through
					.statik("getenv", classes.libraryType(STRING), host("%host-getenv"), classes.libraryType(STRING))
					.nullable()
					.statik("getProperty", classes.libraryType(STRING), support("propertyOr"),
							classes.libraryType(STRING), classes.libraryType(STRING))
					.nullable()
					// nothing a program can tell is collected at its ask
					.statik("gc", Primitive.VOID, (lowering, call) -> JavaForms.NIL)
					.statik("lineSeparator", classes.libraryType(STRING),
							(lowering, call) -> JavaForms.string("\n")).made;
			}
			case "java.nio.charset.StandardCharsets": {
				// each field is read through the static method answering its charset,
				// so
				// that a program holds the UTF-16 ones only where it names one
				ClassType charset = classes.libraryType(CHARSET);
				Builder charsets = new Builder(name, false, classes);
				for (String field : List.of("UTF_8", "ISO_8859_1", "US_ASCII", "UTF_16", "UTF_16BE", "UTF_16LE")) {
					charsets.standard(field, charset,
							java.util.Objects.requireNonNull(charsetNamed(field.replace('_', '-'))), null);
				}
				return charsets.made;
			}
			case STANDARD_STREAM: {
				// printf and format answer nothing here: a call whose value is used is
				// the
				// object's
				ClassType object = classes.libraryType(OBJECT);
				return new Builder(name, false, classes)
					.any("println", false, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-line", call.self()))
					.method("println", Primitive.VOID, (lowering, call) -> newline(call.self()))
					.any("print", false, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-string", call.self()))
					.method("printf", Primitive.VOID, JavaIntrinsics::printf, classes.libraryType(STRING),
							new ArrayType(object))
					.varargs()
					.method("format", Primitive.VOID, JavaIntrinsics::printf, classes.libraryType(STRING),
							new ArrayType(object))
					.varargs()
					.method("flush", Primitive.VOID, host("finish-output")).made;
			}
			case "java.lang.IO": {
				ClassType object = classes.libraryType(OBJECT);
				ClassType string = classes.libraryType(STRING);
				return new Builder(name, false, classes)
					.any("println", true, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-line", OUT))
					.statik("println", Primitive.VOID, (lowering, call) -> newline(OUT))
					.any("print", true, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-string", OUT))
					.statik("readln", string, runtime("readln"))
					.nullable()
					.statik("readln", string,
							(lowering, call) -> call("progn", call("write-string", call.argument(0)),
									call("finish-output"), runtimeCall("readln")),
							string)
					.nullable().made;
			}
			default:
				return null;
		}
	}

	private static LispVal newline(LispVal stream) {
		return stream.equals(OUT) ? call("terpri") : call("terpri", stream);
	}

	private static LispVal print(Context lowering, Invocation call, String writer, LispVal stream) {
		LispVal text = text(lowering, call, 0);
		return stream.equals(OUT) ? call(writer, text) : call(writer, text, stream);
	}

	private static LispVal printf(Context lowering, Invocation call) {
		LispVal text = formatted(lowering, call.arguments().get(0), call.argument(1));
		return call.self().equals(OUT) ? call("write-string", text) : call("write-string", text, call.self());
	}

	/** A conversion of {@code String.format} that writes its argument's hash code. */
	private static final java.util.regex.Pattern HASH_CONVERSION = java.util.regex.Pattern
		.compile("%[-#+ 0,(\\d$.]*[hH]");

	/**
	 * {@code String.format} over a control and the array of its arguments. The function
	 * answering any value's {@code hashCode()} goes with it only where the control may
	 * hold the conversion that asks for one -- it is no constant, or names {@code %h} --,
	 * so that no other program holds that function for its formats; the function reading
	 * a {@code BigInteger} and a {@code BigDecimal} only in a program that makes one.
	 */
	private static LispVal formatted(Context lowering, Typed control, LispVal arguments) {
		boolean hashes = !(control.constant() instanceof String text) || HASH_CONVERSION.matcher(text).find();
		return runtimeCall("format", control.form(), arguments, lowering.stringFunction(),
				hashes ? lowering.hashFunction() : JavaForms.NIL, lowering.exactFunction());
	}

	/** An argument as the text {@code String.valueOf} makes of it. */
	private static LispVal text(Context lowering, Invocation call, int index) {
		Typed argument = call.arguments().get(index);
		if (argument.type().equals(new ArrayType(Primitive.CHAR))) {
			return runtimeCall("chars-string", argument.form(), integer(0), JavaForms.NIL);
		}
		return lowering.stringOf(argument, call.pos());
	}

	private static JavaClass string(Builder builder, JavaClasses classes) {
		ClassType string = new ClassType(builder.made, List.of());
		ClassType object = classes.libraryType(OBJECT);
		Primitive i = Primitive.INT;
		Primitive b = Primitive.BOOLEAN;
		ArrayType characters = new ArrayType(Primitive.CHAR);
		ArrayType bytes = new ArrayType(Primitive.BYTE);
		JavaEmitter format = (lowering, call) -> formatted(lowering, java.util.Objects.requireNonNull(call.receiver()),
				call.argument(0));
		return builder.constructor((lowering, call) -> JavaForms.string(""))
			.constructor(runtime("string-copy"), string)
			.constructor((lowering, call) -> runtimeCall("chars-string", call.argument(0), integer(0), JavaForms.NIL),
					characters)
			.constructor((lowering, call) -> runtimeCall("chars-string", call.argument(0), call.argument(1),
					call.argument(2)), characters, i, i)
			.constructor((lowering, call) -> runtimeCall("utf8-string", call.argument(0), integer(0), JavaForms.NIL),
					bytes)
			.constructor(runtime("utf8-string"), bytes, i, i)
			.constructor(support("codePoints"), new ArrayType(i), i, i)
			.constructor(support("decodeNamed"), bytes, string)
			.throwing(UNSUPPORTED_ENCODING)
			.constructor(support("decodeNamed"), bytes, i, i, string)
			.throwing(UNSUPPORTED_ENCODING)
			.method("getBytes", bytes, runtime("utf8-bytes"))
			.method("getBytes", bytes, support("encodeNamed"), string)
			.throwing(UNSUPPORTED_ENCODING)
			.method("length", i, host("length"))
			.method("charAt", Primitive.CHAR, JavaIntrinsics::charAt, i)
			// a string's elements are code points
			.method("codePointAt", i, JavaIntrinsics::charAt, i)
			.method("codePointBefore", i,
					(lowering, call) -> code(lowering, call.self(), call("-", call.argument(0), integer(1))), i)
			.method("isEmpty", b, (lowering, call) -> call("=", call("length", call.self()), integer(0)))
			.method("isBlank", b, runtime("blank-p"))
			.method("equals", b, (lowering, call) -> {
				JavaType other = call.arguments().get(0).type();
				return other.equals(string) || other == JavaType.Null.NULL
						? call("equal", call.self(), call.argument(0))
						: runtimeCall("string-equals", call.self(), call.argument(0));
			}, object)
			.method("equalsIgnoreCase", b, runtime("string-equals-ignore-case"), string)
			.method("hashCode", i, runtime("string-hash"))
			.method("compareTo", i, runtime("string-compare"), string)
			.method("compareToIgnoreCase", i, runtime("string-compare-ignore-case"), string)
			.method("toString", string, (lowering, call) -> call.self())
			.method("intern", string, runtime("intern"))
			.method("substring", string,
					(lowering, call) -> runtimeCall("substring", call.self(), call.argument(0), JavaForms.NIL), i)
			.method("substring", string, runtime("substring"), i, i)
			.method("indexOf", i,
					(lowering, call) -> call("or", call("search", call.argument(0), call.self()), integer(-1)), string)
			.method("indexOf", i, runtime("index-of"), string, i)
			.method("indexOf", i,
					(lowering, call) -> runtimeCall("index-of-char", call.self(), call.argument(0), integer(0)), i)
			.method("indexOf", i, runtime("index-of-char"), i, i)
			.method("indexOf", i, support("indexOfChar"), i, i, i)
			.method("indexOf", i, support("indexOfText"), string, i, i)
			.method("codePointCount", i, support("codePointCount"), i, i)
			.method("offsetByCodePoints", i, support("offsetByCodePoints"), i, i)
			.method("lastIndexOf", i, runtime("last-index-of"), string)
			.method("lastIndexOf", i, runtime("last-index-of-char"), i)
			.method("lastIndexOf", i, runtime("last-index-of-from"), string, i)
			.method("lastIndexOf", i, runtime("last-index-of-char-from"), i, i)
			.method("regionMatches", b,
					(lowering, call) -> runtimeCall("region-matches", call.self(), JavaForms.NIL, call.argument(0),
							call.argument(1), call.argument(2), call.argument(3)),
					i, string, i, i)
			.method("regionMatches", b, runtime("region-matches"), b, i, string, i, i)
			.method("contentEquals", b, support("contentEquals"), object)
			.method("getChars", Primitive.VOID, runtime("get-chars"), i, i, characters, i)
			.method("indent", string, support("indent"), i)
			.method("stripIndent", string, support("stripIndent"))
			.method("translateEscapes", string, support("translateEscapes"))
			.method("contains", b,
					(lowering, call) -> call("if", call("search", text(lowering, call, 0), call.self()), JavaForms.T,
							JavaForms.NIL),
					object)
			.method("startsWith", b,
					(lowering, call) -> runtimeCall("starts-with", call.self(), call.argument(0), integer(0)), string)
			.method("startsWith", b, runtime("starts-with"), string, i)
			.method("endsWith", b, runtime("ends-with"), string)
			.method("toUpperCase", string, host("%string-upcase-piece"))
			.method("toLowerCase", string, host("%string-downcase-piece"))
			.method("trim", string, runtime("trim"))
			.method("strip", string, (lowering, call) -> runtimeCall("strip", call.self(), JavaForms.T, JavaForms.T))
			.method("stripLeading", string,
					(lowering, call) -> runtimeCall("strip", call.self(), JavaForms.T, JavaForms.NIL))
			.method("stripTrailing", string,
					(lowering, call) -> runtimeCall("strip", call.self(), JavaForms.NIL, JavaForms.T))
			.method("repeat", string, runtime("repeat"), i)
			.method("replace", string, runtime("replace-char"), Primitive.CHAR, Primitive.CHAR)
			.method("replace", string,
					(lowering, call) -> runtimeCall("replace-text", call.self(), text(lowering, call, 0),
							text(lowering, call, 1)),
					object, object)
			.method("concat", string, (lowering, call) -> call("%string-concat", call.self(), call.argument(0)), string)
			.method("toCharArray", characters, runtime("to-chars"))
			.method("split", new ArrayType(string), (lowering, call) -> split(lowering, call, integer(0)), string)
			.method("split", new ArrayType(string), (lowering, call) -> split(lowering, call, call.argument(1)), string,
					i)
			.method("matches", b,
					(lowering, call) -> pattern(lowering, "matchesText", List.of(call.argument(0), call.self()),
							call.arguments().get(0), null),
					string)
			.method("replaceAll", string,
					(lowering, call) -> pattern(lowering, "replaceText",
							List.of(call.self(), call.argument(0), call.argument(1), JavaForms.T),
							call.arguments().get(0), null),
					string, string)
			.method("replaceFirst", string,
					(lowering, call) -> pattern(lowering, "replaceText",
							List.of(call.self(), call.argument(0), call.argument(1), JavaForms.NIL),
							call.arguments().get(0), null),
					string, string)
			.method("formatted", string, format, new ArrayType(object))
			.varargs()
			.statik("format", string,
					(lowering, call) -> formatted(lowering, call.arguments().get(0), call.argument(1)), string,
					new ArrayType(object))
			.varargs()
			.statik("join", string,
					(lowering, call) -> runtimeCall("join", text(lowering, call, 0), call.argument(1),
							lowering.stringFunction()),
					object, new ArrayType(object))
			.varargs()
			.statik("copyValueOf", string,
					(lowering, call) -> runtimeCall("chars-string", call.argument(0), integer(0), JavaForms.NIL),
					characters)
			.statik("copyValueOf", string, runtime("chars-string"), characters, i, i)
			.statik("valueOf", string, runtime("chars-string"), characters, i, i)
			.any("valueOf", true, string, object, (lowering, call) -> text(lowering, call, 0)).made;
	}

	/**
	 * An array's type as the message of {@code System.arraycopy} names it: the element's
	 * primitive type, {@code object array}; {@code nil} for a type that is no array.
	 */
	private static LispVal arrayKind(JavaType type) {
		if (!(type instanceof ArrayType array)) {
			return JavaForms.NIL;
		}
		return JavaForms.string(array.element() instanceof Primitive primitive ? primitive.toString() : "object array");
	}

	/**
	 * {@code String.charAt}: the character's code where the index is one of the string,
	 * the language's exception where it is not -- one read that tests the index, the
	 * host's {@code char} checking nothing on the compiled backends
	 * (.kb/java-frontend.md, "Exceptions").
	 */
	private static LispVal charAt(Context lowering, Invocation call) {
		return code(lowering, call.self(), call.argument(0));
	}

	/** The code of a string's character at an index, the two given as forms. */
	private static LispVal code(Context lowering, LispVal string, LispVal at) {
		List<LispVal> bindings = new ArrayList<>();
		LispVal text = held(lowering, string, bindings);
		LispVal index = held(lowering, at, bindings);
		LispSymbol code = lowering.temporary();
		bindings.add(list(code, call("%string-code", text, index)));
		return list(cl("let*"), list(bindings),
				call("if", call("<", code, integer(0)), runtimeCall("string-index", index, text), code));
	}

	/** A form read twice: itself when it is simple, else a variable bound to it. */
	private static LispVal held(Context lowering, LispVal form, List<LispVal> bindings) {
		if (JavaForms.isSimple(form)) {
			return form;
		}
		LispSymbol variable = lowering.temporary();
		bindings.add(list(variable, form));
		return variable;
	}

	private static LispVal characters() {
		return list(cl("make-array"), integer(16), new LispSymbol(":ELEMENT-TYPE"), quote(cl("character")),
				new LispSymbol(":ADJUSTABLE"), JavaForms.T, new LispSymbol(":FILL-POINTER"), integer(0));
	}

	private static JavaClass stringBuilder(Builder builder, JavaClasses classes) {
		ClassType self = new ClassType(builder.made, List.of());
		ClassType string = classes.libraryType(STRING);
		ClassType object = classes.libraryType(OBJECT);
		Primitive i = Primitive.INT;
		return builder.constructor((lowering, call) -> characters())
			.constructor((lowering, call) -> characters(), i)
			.constructor((lowering, call) -> runtimeCall("sb-append", characters(), call.argument(0)), string)
			.constructor((lowering, call) -> runtimeCall("sb-append", characters(), text(lowering, call, 0)), object)
			.any("append", false, self, object,
					(lowering, call) -> call.arguments().get(0).type() == Primitive.CHAR
							? runtimeCall("sb-append-char", call.self(), call.argument(0))
							: runtimeCall("sb-append", call.self(), text(lowering, call, 0)))
			.method("append", self, JavaIntrinsics::appendRange, object, i, i)
			.method("insert", self,
					(lowering, call) -> runtimeCall("sb-insert", call.self(), call.argument(0), text(lowering,
							new Invocation(call.receiver(), call.arguments().subList(1, 2), false, call.pos()), 0)),
					i, object)
			.method("reverse", self, runtime("sb-reverse"))
			.method("setLength", Primitive.VOID, runtime("sb-set-length"), i)
			.method("setCharAt", Primitive.VOID, runtime("sb-set-char"), i, Primitive.CHAR)
			.method("deleteCharAt", self,
					(lowering, call) -> runtimeCall("sb-delete-char", call.self(), call.argument(0)), i)
			.method("delete", self, runtime("sb-delete"), i, i)
			.method("replace", self, runtime("sb-replace"), i, i, string)
			.method("indexOf", i, runtime("sb-index-of"), string)
			.method("indexOf", i, runtime("index-of"), string, i)
			.method("lastIndexOf", i, runtime("last-index-of"), string)
			.method("lastIndexOf", i, runtime("last-index-of-from"), string, i)
			.method("compareTo", i,
					(lowering, call) -> runtimeCall("string-compare", runtimeCall("sb-string", call.self()),
							runtimeCall("sb-string", call.argument(0))),
					self)
			.method("appendCodePoint", self,
					(lowering, call) -> runtimeCall("sb-append-char", call.self(),
							runtimeCall("code-point", call.argument(0))),
					i)
			.method("repeat", self, runtime("sb-repeat-code"), i, i)
			.method("repeat", self,
					(lowering, call) -> runtimeCall("sb-repeat", call.self(), text(lowering, call, 0),
							call.argument(1)),
					object, i)
			.method("toString", string, runtime("sb-string"))
			.method("length", i, host("length"))
			.method("isEmpty", Primitive.BOOLEAN,
					(lowering, call) -> call("=", call("length", call.self()), integer(0)))
			.method("charAt", Primitive.CHAR, runtime("sb-char"), i)
			.method("substring", string,
					(lowering, call) -> runtimeCall("sb-substring", call.self(), call.argument(0), JavaForms.NIL), i)
			.method("substring", string, runtime("sb-substring"), i, i).made;
	}

	/**
	 * The members of {@code Integer} and {@code Long} over the bits of a number: the same
	 * forms at 32 and at 64 bits.
	 */
	private static void bits(Builder builder, Primitive type, int width) {
		Primitive i = Primitive.INT;
		LispVal bits = integer(width);
		LispVal mask = integer(java.math.BigInteger.ONE.shiftLeft(width).subtract(java.math.BigInteger.ONE));
		builder.field("SIZE", i, integer(width)).field("BYTES", i, integer(width / 8));
		for (String name : List.of("highestOneBit", "lowestOneBit")) {
			String function = name.equals("highestOneBit") ? "highest-one-bit" : "lowest-one-bit";
			builder.statik(name, type, (lowering, call) -> runtimeCall(function, call.argument(0), bits), type);
		}
		builder
			.statik("numberOfLeadingZeros", i, (lowering, call) -> runtimeCall("leading-zeros", call.argument(0), bits),
					type)
			.statik("numberOfTrailingZeros", i,
					(lowering, call) -> runtimeCall("trailing-zeros", call.argument(0), bits), type)
			.statik("reverse", type,
					(lowering, call) -> runtimeCall("reverse-bits", call.argument(0), bits, integer(1)), type)
			.statik("reverseBytes", type,
					(lowering, call) -> runtimeCall("reverse-bits", call.argument(0), bits, integer(8)), type)
			.statik("rotateLeft", type,
					(lowering, call) -> runtimeCall("rotate", call.argument(0), call.argument(1), bits), type, i)
			.statik("rotateRight", type,
					(lowering, call) -> runtimeCall("rotate", call.argument(0), call("-", call.argument(1)), bits),
					type, i)
			.statik("divideUnsigned", type,
					(lowering, call) -> runtimeCall("unsigned-divide", call.argument(0), call.argument(1), bits), type,
					type)
			.statik("remainderUnsigned", type,
					(lowering, call) -> runtimeCall("unsigned-remainder", call.argument(0), call.argument(1), bits),
					type, type)
			.statik("compareUnsigned", i, (lowering, call) -> runtimeCall("compare",
					call("logand", call.argument(0), mask), call("logand", call.argument(1), mask)), type, type);
	}

	private static JavaClass integerClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		bits(builder, i, 32);
		return builder.field("MAX_VALUE", i, integer(Integer.MAX_VALUE))
			.field("MIN_VALUE", i, integer(Integer.MIN_VALUE))
			.statik("parseInt", i,
					(lowering, call) -> lowering.library(SUPPORT, "parseInt", List.of(call.argument(0), integer(10))),
					string)
			.statik("parseInt", i, support("parseInt"), string, i)
			.statik("valueOf", self,
					(lowering, call) -> lowering.library(SUPPORT, "parseInt", List.of(call.argument(0), integer(10))),
					string)
			.statik("parseUnsignedInt", i,
					(lowering, call) -> lowering.library(SUPPORT, "parseUnsignedInt",
							List.of(call.argument(0), integer(10))),
					string)
			.statik("parseUnsignedInt", i, support("parseUnsignedInt"), string, i)
			.statik("toUnsignedString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(10), integer(32)), i)
			.statik("toUnsignedString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), call.argument(1), integer(32)),
					i, i)
			.statik("toUnsignedLong", Primitive.LONG,
					(lowering, call) -> call("logand", call.argument(0), integer(0xFFFFFFFFL)), i)
			.statik("decode", self,
					(lowering, call) -> lowering.library(SUPPORT, "decode",
							List.of(call.argument(0), integer(Integer.MIN_VALUE), integer(Integer.MAX_VALUE))),
					string)
			.statik("toString", string, host("%decimal-piece"), i)
			.statik("toString", string, runtime("radix-string"), i, i)
			.statik("toBinaryString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(2), integer(32)), i)
			.statik("toHexString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(16), integer(32)), i)
			.statik("toOctalString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(8), integer(32)), i)
			.statik("valueOf", self, (lowering, call) -> call.argument(0), i)
			.statik("compare", i, runtime("compare"), i, i)
			.statik("signum", i, host("signum"), i)
			.statik("sum", i, (lowering, call) -> JavaForms.wrap(i, call("+", call.argument(0), call.argument(1))), i,
					i)
			.statik("max", i, host("max"), i, i)
			.statik("min", i, host("min"), i, i)
			.statik("bitCount", i,
					(lowering, call) -> call("logcount", call("logand", call.argument(0), integer(0xFFFFFFFFL))), i)
			.statik("hashCode", i, (lowering, call) -> call.argument(0), i)
			.method("compareTo", i, runtime("compare"), self)
			.method("intValue", i, (lowering, call) -> call.self())
			.method("longValue", Primitive.LONG, (lowering, call) -> call.self())
			.method("doubleValue", Primitive.DOUBLE,
					(lowering, call) -> call("float", call.self(), new LispDouble(1.0))).made;
	}

	private static JavaClass longClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		Primitive l = Primitive.LONG;
		bits(builder, l, 64);
		return builder.field("MAX_VALUE", l, integer(Long.MAX_VALUE))
			.field("MIN_VALUE", l, integer(Long.MIN_VALUE))
			.statik("parseUnsignedLong", l,
					(lowering, call) -> lowering.library(SUPPORT, "parseUnsignedLong",
							List.of(call.argument(0), integer(10))),
					string)
			.statik("parseUnsignedLong", l, support("parseUnsignedLong"), string, i)
			.statik("decode", self,
					(lowering, call) -> call("cons",
							lowering.library(SUPPORT, "decode",
									List.of(call.argument(0), integer(Long.MIN_VALUE), integer(Long.MAX_VALUE))),
							JavaForms.BOXED_LONG),
					string)
			.statik("toUnsignedString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(10), integer(64)), l)
			.statik("toUnsignedString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), call.argument(1), integer(64)),
					l, i)
			.statik("toOctalString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(8), integer(64)), l)
			.statik("parseLong", l,
					(lowering, call) -> lowering.library(SUPPORT, "parseLong", List.of(call.argument(0), integer(10))),
					string)
			.statik("parseLong", l, support("parseLong"), string, i)
			.statik("valueOf", self,
					(lowering, call) -> call("cons",
							lowering.library(SUPPORT, "parseLong", List.of(call.argument(0), integer(10))),
							JavaForms.BOXED_LONG),
					string)
			.statik("toString", string, host("%decimal-piece"), l)
			.statik("toString", string, runtime("radix-string"), l, i)
			.statik("toBinaryString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(2), integer(64)), l)
			.statik("toHexString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(16), integer(64)), l)
			.statik("hashCode", i, runtime("long-hash"), l)
			.statik("valueOf", self, (lowering, call) -> call("cons", call.argument(0), JavaForms.BOXED_LONG), l)
			.statik("compare", i, runtime("compare"), l, l)
			.statik("signum", i, host("signum"), l)
			.statik("sum", l, (lowering, call) -> JavaForms.wrap(l, call("+", call.argument(0), call.argument(1))), l,
					l)
			.statik("max", l, host("max"), l, l)
			.statik("min", l, host("min"), l, l)
			.statik("bitCount", i,
					(lowering, call) -> call("logcount", call("logand", call.argument(0),
							integer(java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE)))),
					l)
			.method("compareTo", i,
					(lowering, call) -> runtimeCall("compare", call("car", call.self()), call("car", call.argument(0))),
					self)
			.method("intValue", i, (lowering, call) -> JavaForms.wrap(i, call("car", call.self())))
			.method("longValue", l, (lowering, call) -> call("car", call.self()))
			.method("doubleValue", Primitive.DOUBLE,
					(lowering, call) -> call("float", call("car", call.self()), new LispDouble(1.0))).made;
	}

	private static JavaClass doubleClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		Primitive d = Primitive.DOUBLE;
		Primitive b = Primitive.BOOLEAN;
		JavaEmitter nan = (lowering, call) -> runtimeCall("nan-p", operands(call).get(0));
		JavaEmitter infinite = (lowering, call) -> runtimeCall("infinite-p", operands(call).get(0));
		return builder.field("MAX_VALUE", d, new LispDouble(Double.MAX_VALUE))
			.field("MIN_VALUE", d, new LispDouble(Double.MIN_VALUE))
			.field("POSITIVE_INFINITY", d, runtimeCall("infinity", JavaForms.NIL))
			.field("NEGATIVE_INFINITY", d, runtimeCall("infinity", JavaForms.T))
			.field("NaN", d, runtimeCall("nan"))
			.field("MIN_NORMAL", d, new LispDouble(Double.MIN_NORMAL))
			.field("MAX_EXPONENT", i, integer(Double.MAX_EXPONENT))
			.field("MIN_EXPONENT", i, integer(Double.MIN_EXPONENT))
			.field("SIZE", i, integer(Double.SIZE))
			.field("BYTES", i, integer(Double.BYTES))
			.statik("toHexString", string, support("hexString"), d)
			.statik("parseDouble", d, support("parseDouble"), string)
			.statik("valueOf", self, support("parseDouble"), string)
			.statik("toString", string, runtime("double-string"), d)
			.statik("compare", i, runtime("double-compare"), d, d)
			.statik("hashCode", i, runtime("double-hash"), d)
			.statik("doubleToLongBits", Primitive.LONG, runtime("double-bits"), d)
			.statik("doubleToRawLongBits", Primitive.LONG, runtime("double-bits"), d)
			.statik("longBitsToDouble", d, runtime("bits-double"), Primitive.LONG)
			.statik("valueOf", self, (lowering, call) -> call.argument(0), d)
			.statik("isNaN", b, nan, d)
			.statik("isInfinite", b, infinite, d)
			.statik("isFinite", b,
					(lowering, call) -> call("not",
							call("or", runtimeCall("nan-p", call.argument(0)),
									runtimeCall("infinite-p", call.argument(0)))),
					d)
			.statik("sum", d, host("+"), d, d)
			.statik("max", d, runtime("double-max"), d, d)
			.statik("min", d, runtime("double-min"), d, d)
			.method("isNaN", b, nan)
			.method("isInfinite", b, infinite)
			.method("compareTo", i, runtime("double-compare"), self)
			.method("intValue", i, runtime("double-to-int"))
			.method("longValue", Primitive.LONG, runtime("double-to-long"))
			.method("doubleValue", d, (lowering, call) -> call.self()).made;
	}

	/**
	 * {@code Byte} and {@code Short}: the number in a cons with its tag, {@code :byte} or
	 * {@code :short}.
	 */
	private static JavaClass smallClass(Builder builder, JavaClasses classes, Primitive held) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		boolean isByte = held == Primitive.BYTE;
		LispSymbol tag = java.util.Objects.requireNonNull(JavaForms.boxTag(held));
		String parse = isByte ? "parseByte" : "parseShort";
		JavaEmitter box = (lowering, call) -> call("cons", call.argument(0), tag);
		JavaEmitter value = (lowering, call) -> call("car", call.self());
		return builder.field("MAX_VALUE", held, integer(isByte ? Byte.MAX_VALUE : Short.MAX_VALUE))
			.field("MIN_VALUE", held, integer(isByte ? Byte.MIN_VALUE : Short.MIN_VALUE))
			.field("SIZE", i, integer(isByte ? Byte.SIZE : Short.SIZE))
			.field("BYTES", i, integer(isByte ? Byte.BYTES : Short.BYTES))
			.statik(parse, held,
					(lowering, call) -> lowering.library(SUPPORT, parse, List.of(call.argument(0), integer(10))),
					string)
			.statik(parse, held, support(parse), string, i)
			.statik("valueOf", self,
					(lowering, call) -> call("cons",
							lowering.library(SUPPORT, parse, List.of(call.argument(0), integer(10))), tag),
					string)
			.statik("valueOf", self, box, held)
			.statik("toString", string, host("%decimal-piece"), held)
			.statik("compare", i, host("-"), held, held)
			.statik("hashCode", i, (lowering, call) -> call.argument(0), held)
			.statik("toUnsignedInt", i,
					(lowering, call) -> call("logand", call.argument(0), integer(isByte ? 0xFF : 0xFFFF)), held)
			.method("compareTo", i,
					(lowering, call) -> call("-", call("car", call.self()), call("car", call.argument(0))), self)
			.method(isByte ? "byteValue" : "shortValue", held, value)
			.method("intValue", i, value)
			.method("longValue", Primitive.LONG, value)
			.method("floatValue", Primitive.FLOAT,
					(lowering, call) -> call("float", call("car", call.self()), new LispDouble(1.0)))
			.method("doubleValue", Primitive.DOUBLE,
					(lowering, call) -> call("float", call("car", call.self()), new LispDouble(1.0))).made;
	}

	/**
	 * {@code Float}: the float, a double holding a binary32, in a cons with
	 * {@code :float}.
	 */
	private static JavaClass floatClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		Primitive f = Primitive.FLOAT;
		Primitive b = Primitive.BOOLEAN;
		LispSymbol tag = java.util.Objects.requireNonNull(JavaForms.boxTag(f));
		JavaEmitter value = (lowering, call) -> call("car", call.self());
		return builder.field("MAX_VALUE", f, new LispDouble(Float.MAX_VALUE))
			.field("MIN_VALUE", f, new LispDouble(Float.MIN_VALUE))
			.field("MIN_NORMAL", f, new LispDouble(Float.MIN_NORMAL))
			.field("POSITIVE_INFINITY", f, runtimeCall("infinity", JavaForms.NIL))
			.field("NEGATIVE_INFINITY", f, runtimeCall("infinity", JavaForms.T))
			.field("NaN", f, runtimeCall("nan"))
			.field("MAX_EXPONENT", i, integer(Float.MAX_EXPONENT))
			.field("MIN_EXPONENT", i, integer(Float.MIN_EXPONENT))
			.field("SIZE", i, integer(Float.SIZE))
			.field("BYTES", i, integer(Float.BYTES))
			.statik("toHexString", string, support("hexStringOfFloat"), f)
			.statik("parseFloat", f, support("parseFloat"), string)
			.statik("valueOf", self,
					(lowering, call) -> call("cons", lowering.library(SUPPORT, "parseFloat", List.of(call.argument(0))),
							tag),
					string)
			.statik("valueOf", self, (lowering, call) -> call("cons", call.argument(0), tag), f)
			.statik("toString", string, runtime("float-string"), f)
			.statik("compare", i, runtime("double-compare"), f, f)
			.statik("hashCode", i, runtime("float-bits"), f)
			.statik("floatToIntBits", i, runtime("float-bits"), f)
			.statik("floatToRawIntBits", i, runtime("float-bits"), f)
			.statik("intBitsToFloat", f, runtime("bits-float"), i)
			.statik("isNaN", b, runtime("nan-p"), f)
			.statik("isInfinite", b, runtime("infinite-p"), f)
			.statik("isFinite", b,
					(lowering, call) -> call("not",
							call("or", runtimeCall("nan-p", call.argument(0)),
									runtimeCall("infinite-p", call.argument(0)))),
					f)
			.statik("sum", f, (lowering, call) -> call("%single-float", call("+", call.argument(0), call.argument(1))),
					f, f)
			.statik("max", f, runtime("double-max"), f, f)
			.statik("min", f, runtime("double-min"), f, f)
			.method("isNaN", b, (lowering, call) -> runtimeCall("nan-p", call("car", call.self())))
			.method("isInfinite", b, (lowering, call) -> runtimeCall("infinite-p", call("car", call.self())))
			.method("compareTo", i,
					(lowering, call) -> runtimeCall("double-compare", call("car", call.self()),
							call("car", call.argument(0))),
					self)
			.method("floatValue", f, value)
			.method("doubleValue", Primitive.DOUBLE, value)
			.method("intValue", i, (lowering, call) -> runtimeCall("double-to-int", call("car", call.self())))
			.method("longValue", Primitive.LONG,
					(lowering, call) -> runtimeCall("double-to-long", call("car", call.self()))).made;
	}

	private static JavaClass booleanClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		Primitive i = Primitive.INT;
		Primitive b = Primitive.BOOLEAN;
		JavaEmitter compare = (lowering, call) -> {
			List<LispVal> operands = operands(call);
			return call("-", call("if", operands.get(0), integer(1), integer(0)),
					call("if", operands.get(1), integer(1), integer(0)));
		};
		return builder.field("TRUE", self, JavaForms.T)
			.field("FALSE", self, JavaForms.BOXED_FALSE)
			.statik("parseBoolean", b, runtime("parse-boolean"), string)
			.statik("valueOf", self, (lowering, call) -> call.argument(0), b)
			.statik("valueOf", self,
					(lowering, call) -> call("if", runtimeCall("parse-boolean", call.argument(0)), JavaForms.T,
							JavaForms.BOXED_FALSE),
					string)
			.statik("toString", string,
					(lowering, call) -> call("if", call.argument(0), JavaForms.string("true"),
							JavaForms.string("false")),
					b)
			.statik("hashCode", i, (lowering, call) -> call("if", call.argument(0), integer(1231), integer(1237)), b)
			.statik("compare", i, compare, b, b)
			.statik("logicalAnd", b, host("and"), b, b)
			.statik("logicalOr", b, host("or"), b, b)
			.statik("logicalXor", b, (lowering, call) -> call("not", call("eq", call.argument(0), call.argument(1))), b,
					b)
			.method("booleanValue", b, (lowering, call) -> runtimeCall("unbox-boolean", call.self()))
			.method("compareTo", i,
					(lowering, call) -> call("-",
							call("if", runtimeCall("unbox-boolean", call.self()), integer(1), integer(0)),
							call("if", runtimeCall("unbox-boolean", call.argument(0)), integer(1), integer(0))),
					self).made;
	}

	private static JavaClass character(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		Primitive i = Primitive.INT;
		Primitive c = Primitive.CHAR;
		Primitive b = Primitive.BOOLEAN;
		// a boxed char is a character, a char its code
		builder.field("MAX_VALUE", c, integer(0xFFFF))
			.field("MIN_VALUE", c, integer(0))
			.statik("valueOf", self, (lowering, call) -> call("code-char", call.argument(0)), c)
			.statik("toString", string, runtime("char-string"), c)
			.statik("compare", i, host("-"), c, c)
			.statik("hashCode", i, (lowering, call) -> call.argument(0), c)
			.statik("getNumericValue", i, runtime("numeric-value"), c)
			.statik("digit", i, runtime("digit"), c, i)
			.statik("forDigit", c, runtime("for-digit"), i, i)
			.statik("toUpperCase", c, runtime("char-upcase"), c)
			.statik("toLowerCase", c, runtime("char-downcase"), c)
			.statik("toTitleCase", c, runtime("char-titlecase"), c)
			.method("compareTo", i,
					(lowering, call) -> call("-", call("char-code", call.self()), call("char-code", call.argument(0))),
					self)
			.method("charValue", c, (lowering, call) -> call("char-code", call.self()));
		for (Primitive unit : List.of(c, i)) {
			for (String test : List.of("isWhitespace", "isSpaceChar")) {
				String name = "char-"
						+ test.substring(2).replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT)
						+ "-p";
				builder.statik(test, b, runtime(name), unit);
			}
			// the predicates the Unicode tables answer, each by its bit there
			// (regex.lisp)
			List<String> tabled = List.of("isAlphabetic", "isLowerCase", "isUpperCase", "isTitleCase", "isIdeographic",
					"isDigit", "isLetter", "isLetterOrDigit", "isDefined", "isMirrored", "isJavaIdentifierStart",
					"isJavaIdentifierPart", "isUnicodeIdentifierStart", "isUnicodeIdentifierPart",
					"isIdentifierIgnorable");
			for (int bit = 0; bit < tabled.size(); bit++) {
				LispVal mask = integer(1L << bit);
				builder.statik(tabled.get(bit), b, (lowering, call) -> runtimeCall("char-is", call.argument(0), mask),
						unit);
			}
			builder
				.statik("isISOControl", b, (lowering, call) -> runtimeCall("char-iso-control-p", call.argument(0)),
						unit)
				.statik("getType", i, runtime("char-type"), unit);
		}
		List<String> pictured = List.of("isEmoji", "isEmojiPresentation", "isEmojiModifier", "isEmojiModifierBase",
				"isEmojiComponent", "isExtendedPictographic");
		for (int bit = 0; bit < pictured.size(); bit++) {
			LispVal mask = integer(1L << (18 + bit));
			builder.statik(pictured.get(bit), b, (lowering, call) -> runtimeCall("char-is", call.argument(0), mask), i);
		}
		// the general categories, as getType numbers them
		List<String> categories = List.of("UNASSIGNED", "UPPERCASE_LETTER", "LOWERCASE_LETTER", "TITLECASE_LETTER",
				"MODIFIER_LETTER", "OTHER_LETTER", "NON_SPACING_MARK", "ENCLOSING_MARK", "COMBINING_SPACING_MARK",
				"DECIMAL_DIGIT_NUMBER", "LETTER_NUMBER", "OTHER_NUMBER", "SPACE_SEPARATOR", "LINE_SEPARATOR",
				"PARAGRAPH_SEPARATOR", "CONTROL", "FORMAT", "", "PRIVATE_USE", "SURROGATE", "DASH_PUNCTUATION",
				"START_PUNCTUATION", "END_PUNCTUATION", "CONNECTOR_PUNCTUATION", "OTHER_PUNCTUATION", "MATH_SYMBOL",
				"CURRENCY_SYMBOL", "MODIFIER_SYMBOL", "OTHER_SYMBOL", "INITIAL_QUOTE_PUNCTUATION",
				"FINAL_QUOTE_PUNCTUATION");
		for (int number = 0; number < categories.size(); number++) {
			if (!categories.get(number).isEmpty()) {
				builder.field(categories.get(number), Primitive.BYTE, integer(number));
			}
		}
		// a code point and the two units of one outside the first plane
		builder.field("MIN_RADIX", i, integer(2))
			.field("MAX_RADIX", i, integer(36))
			.field("SIZE", i, integer(16))
			.field("BYTES", i, integer(2))
			.field("MIN_CODE_POINT", i, integer(0))
			.field("MAX_CODE_POINT", i, integer(0x10FFFF))
			.field("MIN_SUPPLEMENTARY_CODE_POINT", i, integer(0x10000))
			.field("MIN_HIGH_SURROGATE", c, integer(0xD800))
			.field("MAX_HIGH_SURROGATE", c, integer(0xDBFF))
			.field("MIN_LOW_SURROGATE", c, integer(0xDC00))
			.field("MAX_LOW_SURROGATE", c, integer(0xDFFF))
			.field("MIN_SURROGATE", c, integer(0xD800))
			.field("MAX_SURROGATE", c, integer(0xDFFF))
			.statik("toString", string,
					(lowering, call) -> runtimeCall("char-string", runtimeCall("code-point", call.argument(0))), i)
			.statik("toChars", new ArrayType(c), runtime("code-point-chars"), i)
			.statik("toChars", i, support("toChars"), i, new ArrayType(c), i)
			.statik("codePointAt", i, support("codePointAt"), new ArrayType(c), i)
			.statik("reverseBytes", c,
					(lowering, call) -> call("logior",
							call("ash", call("logand", call.argument(0), integer(0xFF)), integer(8)),
							call("ash", call.argument(0), integer(-8))),
					c)
			.statik("charCount", i,
					(lowering, call) -> call("if", call(">=", call.argument(0), integer(0x10000)), integer(2),
							integer(1)),
					i)
			.statik("isValidCodePoint", b, (lowering, call) -> between(call.argument(0), 0, 0x10FFFF), i)
			.statik("isBmpCodePoint", b, (lowering, call) -> between(call.argument(0), 0, 0xFFFF), i)
			.statik("isSupplementaryCodePoint", b, (lowering, call) -> between(call.argument(0), 0x10000, 0x10FFFF), i)
			.statik("isHighSurrogate", b, (lowering, call) -> between(call.argument(0), 0xD800, 0xDBFF), c)
			.statik("isLowSurrogate", b, (lowering, call) -> between(call.argument(0), 0xDC00, 0xDFFF), c)
			.statik("isSurrogate", b, (lowering, call) -> between(call.argument(0), 0xD800, 0xDFFF), c)
			.statik("isSurrogatePair", b,
					(lowering, call) -> call("and", between(call.argument(0), 0xD800, 0xDBFF),
							between(call.argument(1), 0xDC00, 0xDFFF)),
					c, c)
			.statik("toCodePoint", i,
					(lowering, call) -> call("+", call("ash", call.argument(0), integer(10)), call.argument(1),
							integer(0x10000 - (0xD800 << 10) - 0xDC00)),
					c, c)
			.statik("highSurrogate", c,
					(lowering, call) -> call("+", integer(0xD800 - (0x10000 >> 10)),
							call("ash", call.argument(0), integer(-10))),
					i)
			.statik("lowSurrogate", c,
					(lowering, call) -> call("+", integer(0xDC00), call("logand", call.argument(0), integer(0x3FF))), i)
			.statik("getNumericValue", i, runtime("numeric-value"), i)
			.statik("digit", i, runtime("digit"), i, i)
			.statik("toUpperCase", i, runtime("char-upcase"), i)
			.statik("toLowerCase", i, runtime("char-downcase"), i)
			.statik("toTitleCase", i, runtime("char-titlecase"), i);
		return builder.made;
	}

	/** Whether a number is one of a range, both ends in it: the number is read once. */
	private static LispVal between(LispVal number, int least, int most) {
		return call("<=", integer(least), number, integer(most));
	}

	/**
	 * {@code Math.floorDiv}: the floored quotient wrapped to its type, each operand
	 * asserted of its own as an operator's are ({@link JavaForms#known}).
	 */
	private static LispVal floorDiv(Primitive type, Primitive divisor, JavaEmitter.Invocation call) {
		return JavaForms.wrap(type, call("values",
				call("floor", JavaForms.known(type, call.argument(0)), JavaForms.known(divisor, call.argument(1)))));
	}

	private static JavaClass math(Builder builder) {
		Primitive i = Primitive.INT;
		Primitive l = Primitive.LONG;
		Primitive f = Primitive.FLOAT;
		Primitive d = Primitive.DOUBLE;
		builder.field("PI", d, new LispDouble(Math.PI))
			.field("E", d, new LispDouble(Math.E))
			.statik("pow", d, strict("pow"), d, d)
			.statik("atan2", d, strict("atan2"), d, d)
			.statik("hypot", d, strict("hypot"), d, d)
			.statik("abs", i, (lowering, call) -> JavaForms.wrap(i, call("abs", call.argument(0))), i)
			.statik("abs", l, (lowering, call) -> JavaForms.wrap(l, call("abs", call.argument(0))), l)
			.statik("abs", d, host("abs"), d)
			.statik("abs", f, host("abs"), f)
			.statik("round", l, runtime("round"), d)
			.statik("round", i,
					(lowering, call) -> call("max", integer(Integer.MIN_VALUE),
							call("min", integer(Integer.MAX_VALUE), runtimeCall("round", call.argument(0)))),
					f)
			.statik("signum", f, strict("signum"), f)
			.statik("random", d, (lowering, call) -> call("random", new LispDouble(1.0)))
			.statik("toRadians", d,
					(lowering, call) -> call("*", call.argument(0), new LispDouble(0.017453292519943295)), d)
			.statik("toDegrees", d, (lowering, call) -> call("*", call.argument(0), new LispDouble(57.29577951308232)),
					d)
			.statik("floorDiv", i, (lowering, call) -> floorDiv(i, i, call), i, i)
			.statik("floorDiv", l, (lowering, call) -> floorDiv(l, l, call), l, l)
			.statik("floorMod", i, host("mod"), i, i)
			.statik("floorMod", l, host("mod"), l, l);
		for (String name : List.of("sqrt", "cbrt", "floor", "ceil", "rint", "exp", "log", "log10", "sin", "cos", "tan",
				"asin", "acos", "atan", "sinh", "cosh", "tanh", "signum", "log1p", "expm1")) {
			builder.statik(name, d, strict(name), d);
		}
		for (String name : List.of("max", "min")) {
			for (Primitive type : List.of(i, l)) {
				builder.statik(name, type, host(name), type, type);
			}
			builder.statik(name, f, runtime("double-" + name), f, f);
			builder.statik(name, d, runtime("double-" + name), d, d);
		}
		for (String name : List.of("addExact", "subtractExact", "multiplyExact")) {
			builder.statik(name, i, support(name), i, i).statik(name, l, support(name + "Long"), l, l);
		}
		builder.statik("negateExact", i, support("negateExact"), i).statik("toIntExact", i, support("toIntExact"), l);
		for (String name : List.of("absExact", "incrementExact", "decrementExact")) {
			builder.statik(name, i, support(name), i).statik(name, l, support(name + "Long"), l);
		}
		for (String name : List.of("divideExact", "floorDivExact", "ceilDivExact")) {
			builder.statik(name, i, support(name), i, i).statik(name, l, support(name + "Long"), l, l);
		}
		builder.statik("negateExact", l, support("negateExactLong"), l)
			.statik("multiplyExact", l, support("multiplyExactLong"), l, i)
			.statik("clamp", i, support("clamp"), l, i, i)
			.statik("clamp", l, support("clampLong"), l, l, l)
			.statik("clamp", d, support("clampDouble"), d, d, d)
			.statik("clamp", f, support("clampFloat"), f, f, f)
			.field("TAU", d, new LispDouble(Math.TAU))
			.statik("multiplyFull", l, host("*"), i, i)
			.statik("multiplyHigh", l,
					(lowering, call) -> call("ash", call("*", call.argument(0), call.argument(1)), integer(-64)), l, l)
			.statik("unsignedMultiplyHigh", l, (lowering, call) -> {
				LispVal mask = integer(java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE));
				return JavaForms.wrap(l, call("ash",
						call("*", call("logand", call.argument(0), mask), call("logand", call.argument(1), mask)),
						integer(-64)));
			}, l, l)
			.statik("IEEEremainder", d, strict("ieee-remainder"), d, d)
			.statik("copySign", d, strict("copy-sign"), d, d)
			.statik("copySign", f, strict("copy-sign"), f, f)
			.statik("getExponent", i, strict("get-exponent"), d)
			.statik("getExponent", i, runtime("float-exponent"), f)
			.statik("nextAfter", d,
					(lowering, call) -> runtimeCall("next-after", call.argument(0), call.argument(1), JavaForms.T), d,
					d)
			.statik("nextAfter", f,
					(lowering, call) -> runtimeCall("next-after", call.argument(0), call.argument(1), JavaForms.NIL), f,
					d);
		// a quotient rounded toward an infinity, and what is left of it
		for (List<Primitive> operands : List.of(List.of(i, i), List.of(l, i), List.of(l, l))) {
			Primitive wide = operands.get(0);
			Primitive divisor = operands.get(1);
			if (divisor != wide) {
				builder.statik("floorDiv", wide, (lowering, call) -> floorDiv(wide, divisor, call), wide, divisor)
					.statik("floorMod", divisor, host("mod"), wide, divisor);
			}
			builder
				.statik("ceilDiv", wide,
						(lowering, call) -> JavaForms.wrap(wide,
								call("values", call("ceiling", call.argument(0), call.argument(1)))),
						wide, divisor)
				.statik("ceilMod", divisor, runtime("ceil-mod"), wide, divisor);
		}
		// over the bits: a double's and a float's, the float rounded as one
		for (Primitive type : List.of(d, f)) {
			LispVal wide = type == d ? JavaForms.T : JavaForms.NIL;
			builder.statik("ulp", type, (lowering, call) -> runtimeCall("ulp", call.argument(0), wide), type)
				.statik("nextUp", type, (lowering, call) -> runtimeCall("next", call.argument(0), JavaForms.T, wide),
						type)
				.statik("nextDown", type,
						(lowering, call) -> runtimeCall("next", call.argument(0), JavaForms.NIL, wide), type)
				.statik("fma", type,
						(lowering, call) -> runtimeCall("fma", call.argument(0), call.argument(1), call.argument(2),
								wide),
						type, type, type)
				.statik("scalb", type,
						(lowering, call) -> runtimeCall("scalb", call.argument(0), call.argument(1), wide), type, i);
		}
		return builder.made;
	}

	/** The host's stream a standard stream writes to: the error stream or the output. */
	private static LispVal standard(LispVal error) {
		return call("if", error, cl("*error-output*"), OUT);
	}

	// ---- the native members of the library's Java source

	/**
	 * The {@code native} members of the library's Java source, by class, name and erased
	 * parameter types: what Java cannot say, or says slowly, over the value model.
	 */
	private static final Map<String, JavaEmitter> NATIVES = natives();

	private static Map<String, JavaEmitter> natives() {
		Map<String, JavaEmitter> natives = new HashMap<>();
		String arrays = "java.util.Arrays.";
		// an array of references is an untyped vector whatever its element type
		natives.put(arrays + "newArray(Object[],int)",
				(lowering, call) -> lowering.arrayLike(
						call("make-array", call.argument(1), new LispSymbol(":INITIAL-ELEMENT"), JavaForms.NIL),
						call.argument(0)));
		natives.put(arrays + "sortRange(int[],int,int)", runtime("sort-ints"));
		natives.put("java.util.Objects.requireNonNull(Object)",
				(lowering, call) -> call("or", call.argument(0), JavaForms.runtimeCall("npe")));
		natives.put(arrays + "sortRange(long[],int,int)", runtime("sort-numbers"));
		natives.put(arrays + "sortRange(double[],int,int)", runtime("sort-doubles"));
		natives.put(arrays + "sortRange(char[],int,int)", runtime("sort-numbers"));
		// a list of List.of's kind that holds nulls, made by a class of java.util the
		// streams' package cannot name
		natives.put("rontolisp.internal.Flow.listed(Object[])", (lowering, call) -> lowering
			.library("java.util.ImmutableCollections", "listed", List.of(call.argument(0))));
		natives.put("rontolisp.internal.Support.readDouble(String)", runtime("read-double"));
		natives.put("rontolisp.internal.Support.utf8(String)", runtime("utf8-bytes"));
		natives.put("rontolisp.internal.Support.utf8String(byte[],int,int)", runtime("utf8-string"));
		// the standard streams: the host's, which are streams of characters
		natives.put("java.io.StandardOutput.write0(boolean,String)",
				(lowering, call) -> call("write-string", call.argument(1), standard(call.argument(0))));
		natives.put("java.io.StandardOutput.put0(boolean,int)", (lowering, call) -> call("write-char",
				call("code-char", call.argument(1)), standard(call.argument(0))));
		natives.put("java.io.StandardOutput.flush0(boolean)",
				(lowering, call) -> call("finish-output", standard(call.argument(0))));
		natives.put("java.io.StandardInput.read0()", runtime("read-character"));
		natives.put("java.io.StandardInput.line0()", runtime("readln"));
		natives.put("java.io.StandardInput.ready0()", runtime("input-ready"));
		natives.put("java.io.StandardInput.more0(int)", runtime("input-more"));
		// what a class object holds of its class: the lowering's functions
		natives.put("java.lang.Class.test0(Object,Object)", (lowering, call) -> call("if",
				call("funcall", call.argument(0), call.argument(1)), JavaForms.T, JavaForms.NIL));
		natives.put("java.lang.Class.constants0(Object)", (lowering, call) -> call("funcall", call.argument(0)));
		String thread = THREAD + ".";
		natives.put(thread + "carrier0(Thread)", runtime("thread-carrier"));
		natives.put(thread + "adopt0(Thread)", runtime("thread-adopt"));
		natives.put(thread + "current0()", runtime("thread-current"));
		natives.put(thread + "named0()", runtime("thread-named"));
		natives.put(thread + "sleep0(long)", runtime("thread-sleep"));
		natives.put(thread + "yield0()", runtime("thread-yield"));
		natives.put(thread + "interrupt0(Object)", runtime("thread-interrupt"));
		natives.put(thread + "blocked0(Object)", runtime("thread-blocked"));
		natives.put(thread + "interrupted0(Object,boolean)", runtime("thread-interrupted"));
		natives.put(thread + "holds0(Object)", runtime("monitor-held"));
		natives.put(thread + "wait0(Object,long)", runtime("monitor-wait"));
		natives.put(thread + "notify0(Object,boolean)", runtime("monitor-notify"));
		// what a started thread runs: its run(), what it leaves uncaught written as a JDK
		// writes it, and its end, which the threads in join() wait for
		natives.put(thread + "start0(Thread,Object,boolean)", (lowering, call) -> {
			LispSymbol started = lowering.temporary();
			LispSymbol condition = lowering.temporary();
			LispSymbol thrown = lowering.temporary();
			LispVal trace = list(cl("lambda"), list(thrown), lowering.library(THROWABLE, "trace", List.of(thrown)));
			LispVal uncaught = list(cl("error"), list(condition),
					lowering.library(THREAD, "report", List.of(started, runtimeCall("traced-text", condition, trace))));
			LispVal body = list(cl("lambda"), JavaForms.NIL,
					list(cl("unwind-protect"),
							list(cl("handler-case"), lowering.library(THREAD, "body", List.of(started)), uncaught),
							lowering.library(THREAD, "finish", List.of(started))));
			return JavaForms.let(started, call.argument(0),
					List.of(runtimeCall("thread-start", call.argument(1), call.argument(2), body)));
		});
		// afterExecute is handed what a pool's task threw: the catch of every exception
		// around a task is made only in a program one of whose classes declares a hook
		String pool = "java.util.concurrent.ThreadPoolExecutor";
		natives.put(pool + ".hooks()",
				(lowering, call) -> lowering.declaresBelow(pool, Set.of("beforeExecute", "afterExecute"))
						? lowering.library(pool, "hooked", List.of()) : JavaForms.NIL);
		// the field updaters: a field is the name the lowering gave it where newUpdater
		// was called, and the program's three functions choose the field by it
		for (String updater : FIELD_UPDATERS) {
			String value = updater.endsWith("IntegerFieldUpdater") ? "int"
					: updater.endsWith("LongFieldUpdater") ? "long" : "Object";
			natives.put(updater + ".get0(Object,Object)", (lowering, call) -> lowering.updated("get", operands(call)));
			natives.put(updater + ".set0(Object,Object," + value + ")",
					(lowering, call) -> lowering.updated("set", operands(call)));
			natives.put(updater + ".swap0(Object,Object," + value + "," + value + ")",
					(lowering, call) -> lowering.updated("swap", operands(call)));
			natives.put(updater + ".newUpdater(Class," + (value.equals("Object") ? "Class," : "") + "String)",
					(lowering, call) -> {
						throw lowering.unsupported(call.pos(),
								"newUpdater but called by its class's name with class literals and a constant name");
					});
		}
		// java.util.regex over the engine every front end shares: compile and matches are
		// lowered where they are called, with the providers their pattern may need
		String regex = PATTERN + ".";
		natives.put(regex + "compile(String)", (lowering, call) -> pattern(lowering, "make",
				List.of(call.argument(0), integer(0)), call.arguments().get(0), null));
		natives.put(regex + "compile(String,int)", (lowering, call) -> pattern(lowering, "make",
				List.of(call.argument(0), call.argument(1)), call.arguments().get(0), call.arguments().get(1)));
		natives.put(regex + "matches(String,CharSequence)", (lowering, call) -> pattern(lowering, "matchesText",
				List.of(call.argument(0), call.argument(1)), call.arguments().get(0), null));
		// a Scanner's members of a pattern's text compile it where they are called
		natives.put(SCANNER + ".hasNext(String)", scanning("hasNextText", true));
		natives.put(SCANNER + ".next(String)", scanning("nextText", true));
		natives.put(SCANNER + ".useDelimiter(String)", scanning("delimiterText", false));
		natives.put(SCANNER + ".findInLine(String)", scanning("findInLineText", false));
		natives.put(SCANNER + ".findWithinHorizon(String,int)", scanning("findWithinHorizonText", false));
		natives.put(SCANNER + ".skip(String)", scanning("skipText", false));
		natives.put(SCANNER + ".findAll(String)", scanning("findAllText", false));
		// a charset named by a constant is that charset; any other name is looked up
		natives.put(CHARSET + ".forName(String)", (lowering, call) -> {
			JavaClass.Accessor named = call.arguments().get(0).constant() instanceof String name ? charsetNamed(name)
					: null;
			return named != null ? lowering.library(named.className(), named.method(), List.of())
					: lowering.library(CHARSET, "lookup", List.of(call.argument(0)));
		});
		natives.put(regex + "compile0(String,int,Object,Object,Object)", runtime("regex-compile"));
		natives.put(regex + "refused0(Object)", runtime("regex-refused-p"));
		natives.put(regex + "description0(Object)", runtime("regex-refusal-text"));
		natives.put(regex + "index0(Object)", runtime("regex-refusal-index"));
		natives.put(regex + "message0(String,int,String)", engine("syntax-message"));
		natives.put(regex + "quote0(String)", engine("quote"));
		natives.put(regex + "split0(Object,String,int)", runtime("regex-split"));
		natives.put(regex + "groupCount0(Object)", engine("group-count"));
		natives.put(regex + "groupIndex0(Object,String)", runtime("regex-group-index"));
		String matcher = "java.util.regex.Matcher.";
		natives.put(matcher + "matcher0(Object,String)", engine("matcher"));
		natives.put(matcher + "find0(Object)", engine("matcher-find"));
		natives.put(matcher + "findFrom0(Object,int)", engine("matcher-find-from"));
		natives.put(matcher + "lookingAt0(Object)", engine("matcher-looking-at"));
		natives.put(matcher + "matches0(Object)", engine("matcher-matches"));
		natives.put(matcher + "reset0(Object)", engine("matcher-reset"));
		natives.put(matcher + "region0(Object,int,int)", engine("matcher-region"));
		natives.put(matcher + "bounds0(Object,boolean,boolean)", engine("matcher-bounds"));
		natives.put(matcher + "expand0(Object,String)", runtime("regex-expand"));
		natives.put(matcher + "partial0(Object)", runtime("regex-refusal-partial"));
		natives.put(matcher + "hitEnd0(Object)", engine("matcher-hit-end"));
		natives.put(matcher + "requireEnd0(Object)", engine("matcher-require-end"));
		natives.put(matcher + "matched0(Object)", engine("matcher-matched-p"));
		natives.put(matcher + "start0(Object,int)", engine("matcher-start"));
		natives.put(matcher + "end0(Object,int)", engine("matcher-end"));
		natives.put(matcher + "group0(Object,int)", engine("matcher-group"));
		natives.put(matcher + "replace0(Object,String,String,boolean)", runtime("regex-replace"));
		natives.put(matcher + "text0(Object)", (lowering, call) -> call.argument(0));
		// java.net.http over rontolisp:fetch, which the program itself calls: the
		// transports see a program that fetches, and a target with none refuses it
		String http = "rontolisp.internal.Http.";
		// the future is held in a vector: a function a thread is suspended in answers
		// its value through an await, which would wait for a future handed back bare
		natives.put(http + "start0(String,String,String[],byte[])", (lowering, call) -> {
			LispVal options = call("list", new LispSymbol(":METHOD"), call.argument(1), new LispSymbol(":HEADERS"),
					runtimeCall("http-fields", call.argument(2)), new LispSymbol(":BODY"), call.argument(3));
			return fetched(lowering, call.argument(0), options);
		});
		// the two timeouts in milliseconds, 0 for none: fetch takes an absent option as
		// nil. Reached only by a program that gives a client or a request a time limit
		// (Http.TimedWire), so only it names the keywords a transport's timeout arm is
		// spliced for
		natives.put(http + "timed0(String,String,String[],byte[],long,long)", (lowering, call) -> {
			LispSymbol connect = lowering.temporary();
			LispSymbol reply = lowering.temporary();
			LispVal options = call("list", new LispSymbol(":METHOD"), call.argument(1), new LispSymbol(":HEADERS"),
					runtimeCall("http-fields", call.argument(2)), new LispSymbol(":BODY"), call.argument(3),
					new LispSymbol(":CONNECT-TIMEOUT"), call("and", call(">", connect, integer(0)), connect),
					new LispSymbol(":FIRST-BYTE-TIMEOUT"), call("and", call(">", reply, integer(0)), reply));
			return JavaForms.let(connect, call.argument(4), List
				.of(JavaForms.let(reply, call.argument(5), List.of(fetched(lowering, call.argument(0), options)))));
		});
		natives.put(http + "reply0(Object)", runtime("http-reply"));
		natives.put(http + "failed0(Object)", host("stringp"));
		natives.put(http + "text0(Object)", (lowering, call) -> call.argument(0));
		natives.put(http + "status0(Object)", (lowering, call) -> call("aref", call.argument(0), integer(0)));
		natives.put(http + "fields0(Object)", (lowering, call) -> call("aref", call.argument(0), integer(1)));
		natives.put(http + "octets0(Object)", (lowering, call) -> call("aref", call.argument(0), integer(2)));
		// com.sun.net.httpserver over the transport every adapter serves through
		// (http-serve.lisp), which the program itself calls: the passes that splice a
		// transport's leg read a program that serves. The application is a function of
		// Clack's environment answering Clack's delayed response, a function of the
		// transport's responder; each request runs HttpServing.answer, on the
		// transport's thread or, where the target has one stack, as a thread of the
		// program's (JavaResumable reads the function handed over as one awaited)
		String serving = SERVING + ".";
		natives.put(serving + "open0(Object,int,String)", (lowering, call) -> {
			LispSymbol server = lowering.temporary();
			LispSymbol env = lowering.temporary();
			LispSymbol responder = lowering.temporary();
			LispSymbol handed = lowering.temporary();
			LispSymbol condition = lowering.temporary();
			// the request's threads answer through the responder the run time hands
			// them: the transport's, or one of its own over it
			LispVal answer = list(cl("lambda"), list(handed),
					lowering.library(SERVING, "answer", List.of(server, env, handed)));
			LispVal application = list(cl("lambda"), list(env),
					list(cl("lambda"), list(responder), runtimeCall("http-request", answer, responder)));
			return JavaForms.let(server, call.argument(0),
					List.of(list(cl("handler-case"),
							list(new LispSymbol("RONTOLISP::%HTTP-SERVE-BEGIN"), application, call.argument(1),
									call.argument(2)),
							list(cl("error"), list(condition), call("princ-to-string", condition)))));
		});
		natives.put(serving + "failed0(Object)", host("stringp"));
		natives.put(serving + "text0(Object)", (lowering, call) -> call.argument(0));
		natives.put(serving + "port0(Object)", (lowering, call) -> call("or",
				list(new LispSymbol("RONTOLISP::%HTTP-SERVE-PORT"), call.argument(0)), integer(0)));
		natives.put(serving + "close0(Object)",
				(lowering, call) -> list(new LispSymbol("RONTOLISP::%HTTP-SERVE-STOP"), call.argument(0)));
		// the thread the program started on waits for the stop where the host's threads
		// serve, then for the transport to have stopped; where the target has one
		// stack the program's own scheduler serves, and this is not reached
		natives.put(serving + "run0(Object)", (lowering, call) -> call("progn", runtimeCall("http-wait"),
				list(new LispSymbol("RONTOLISP::%HTTP-SERVE-RUN"), call.argument(0))));
		natives.put(serving + "shared0()", runtime("http-shared"));
		natives.put(serving + "gate0()", runtime("http-gate"));
		natives.put(serving + "turn0(Object,Object,boolean)",
				(lowering, call) -> runtimeCall("http-turn", call.argument(0), call.argument(1),
						call("if", call.argument(2), new LispSymbol(":STARTED"), new LispSymbol(":STOPPED"))));
		natives.put(serving + "self0()", runtime("self"));
		natives.put(serving + "admitted0(Object)", runtime("http-admitted"));
		natives.put(serving + "method0(Object)", runtime("http-method"));
		natives.put(serving + "target0(Object)", runtime("http-target"));
		natives.put(serving + "protocol0(Object)", runtime("http-protocol"));
		natives.put(serving + "fields0(Object)", runtime("http-request-fields"));
		natives.put(serving + "body0(Object)", runtime("http-request-body"));
		natives.put(serving + "end0(Object,boolean)", runtime("http-end"));
		natives.put(serving + "respond0(Object,int,String[],byte[])", runtime("http-respond"));
		natives.put(serving + "stream0(Object,int,String[])", runtime("http-stream"));
		natives.put(serving + "write0(Object,byte[])", runtime("http-write"));
		natives.put(serving + "finish0(Object)", runtime("http-finish"));
		// java.net.URI over the rules the front ends share (eval/uri.lisp)
		String uri = "java.net.URI.";
		natives.put(uri + "refusal0(String)", shared("refusal"));
		natives.put(uri + "reason0(Object)", host("car"));
		natives.put(uri + "index0(Object)", host("cdr"));
		natives.put(uri + "split0(String)", (lowering, call) -> call("coerce",
				JavaForms.form(sharedFunction("split"), operands(call)), list(cl("quote"), cl("simple-vector"))));
		natives.put(uri + "resolve0(String,String)", shared("resolve"));
		// java.text.Normalizer over the forms the front ends share
		// (eval/unicode-normalization.lisp)
		natives.put("java.text.Normalizer.normalize0(String,boolean,boolean)",
				(lowering, call) -> JavaForms.form(new LispSymbol("RONTOLISP::%UNORM-NORMALIZE"), operands(call)));
		// java.math.BigInteger over the host's integers, which have no bound: the field
		// of a BigInteger holds one, so the arithmetic is the host's
		String big = "java.math.BigInteger.";
		natives.put(big + "wide(long)", (lowering, call) -> call.argument(0));
		natives.put(big + "low(Object)", (lowering, call) -> JavaForms.wrap(Primitive.LONG, call.argument(0)));
		natives.put(big + "sum(Object,Object)", host("+"));
		natives.put(big + "difference(Object,Object)", host("-"));
		natives.put(big + "product(Object,Object)", host("*"));
		natives.put(big + "quotient(Object,Object)",
				(lowering, call) -> call("values", host("truncate").emit(lowering, call)));
		natives.put(big + "rest(Object,Object)", host("rem"));
		natives.put(big + "modulo(Object,Object)", host("mod"));
		natives.put(big + "power(Object,int)", host("expt"));
		natives.put(big + "common(Object,Object)", host("gcd"));
		natives.put(big + "root(Object)", host("isqrt"));
		natives.put(big + "shifted(Object,long)", host("ash"));
		natives.put(big + "both(Object,Object)", host("logand"));
		natives.put(big + "either(Object,Object)", host("logior"));
		natives.put(big + "differing(Object,Object)", host("logxor"));
		natives.put(big + "inverted(Object)", host("lognot"));
		natives.put(big + "bit(int,Object)", host("logbitp"));
		natives.put(big + "length(Object)", host("integer-length"));
		natives.put(big + "count(Object)", host("logcount"));
		natives.put(big + "sign(Object)", host("signum"));
		natives.put(big + "below(Object,Object)", host("<"));
		natives.put(big + "same(Object,Object)", host("="));
		natives.put(big + "digits(Object,int)", runtime("digits"));
		natives.put(big + "rounded(Object,Object,int,int,int)", runtime("nearest-binary"));
		natives.put("rontolisp.internal.Support.floatHash(float)", runtime("float-bits"));
		// a Float is the float in a cons with its tag
		natives.put("rontolisp.internal.Support.readFloat(String)", (lowering, call) -> {
			LispSymbol read = new LispSymbol("%float");
			return JavaForms.let(read, runtimeCall("read-float", call.argument(0)),
					List.of(call("if", read,
							call("cons", read, java.util.Objects.requireNonNull(JavaForms.boxTag(Primitive.FLOAT))),
							JavaForms.NIL)));
		});
		// java.time: the host's wall clock in nanoseconds, and the offset its zone has
		// at an instant (seconds east of UTC, doubled, one more in summer time)
		natives.put("java.time.Instant.wall0()", host("%unix-nano-time"));
		natives.put("java.time.ZoneId.local0(long)", host("%local-time-offset"));
		// the regions of the tz database: a program holds the ones it names, so a call
		// naming a zone is noted before it is the library's own lookup, and what the
		// library asks of the program is answered here (answered, below)
		natives.put(ZONE_ID + ".of(String)", (lowering, call) -> {
			lowering.names(call.arguments().get(0), ZONE_NAMING);
			return lowering.library(ZONE_ID, "ofId", List.of(call.argument(0)));
		});
		natives.put(ZONE_ID + ".getAvailableZoneIds()", (lowering, call) -> {
			lowering.namesZone(null);
			return lowering.library(ZONE_ID, "ids", List.of());
		});
		// a short name of SHORT_IDS brings the zone it stands for, any other map them all
		natives.put(ZONE_ID + ".of(String,java.util.Map)", (lowering, call) -> {
			lowering.namesZone(call.arguments().get(0).constant() instanceof String name
					&& reads(call.argument(1), ZONE_ID + ".SHORT_IDS")
							? JavaZones.shipped().shortIds().getOrDefault(name, name) : null);
			return lowering.library(ZONE_ID, "aliased", operands(call));
		});
		natives.put(ZONE_ID + ".wanted()", (lowering, call) -> lowering.wantedField() ? JavaForms.T : JavaForms.NIL);
		natives.put(ZONE_ID + ".short0()", (lowering, call) -> JavaForms.string(JavaZones.shipped().shortIdsText()));
		natives.put(ZONE_ID + ".link0(String)", host("%read-link"));
		natives.put(ZONE_RULES_PROVIDER + ".getAvailableZoneIds()", (lowering, call) -> {
			lowering.namesZone(null);
			return lowering.library(ZONE_ID, "providedIds", List.of());
		});
		natives.put(ZONE_RULES_PROVIDER + ".getRules(String,boolean)", (lowering, call) -> {
			lowering.names(call.arguments().get(0), ZONE_NAMING);
			return lowering.library(ZONE_ID, "provided", operands(call));
		});
		natives.put(ZONE_RULES_PROVIDER + ".getVersions(String)", (lowering, call) -> {
			lowering.names(call.arguments().get(0), ZONE_NAMING);
			return lowering.library(ZONE_RULES_PROVIDER, "versions", operands(call));
		});
		natives.put(ZONE_RULES_PROVIDER + ".version0()",
				(lowering, call) -> JavaForms.string(JavaZones.shipped().version()));
		// a zoned text the program's own files parse: the regions a constant names, all
		// of them for one computed where the program runs
		JavaEmitter parse = (lowering, call) -> {
			lowering.readsZones(call.arguments().get(0).constant() instanceof String text ? text : null);
			return lowering.library(ZONED, "parsed", operands(call));
		};
		natives.put(ZONED + ".parse(CharSequence)", parse);
		natives.put(ZONED + ".parse(CharSequence,java.time.format.DateTimeFormatter)", parse);
		natives.put(REGIONAL, (lowering, call) -> lowering.holdsRegions() ? JavaForms.T : JavaForms.NIL);
		// java.time's classes read a date of another class through its fields only in a
		// program that can hold one; a formatter's ISO constants are made where read; a
		// formatter reads a zone's name through ZoneId's own lookup, which notes none
		natives.put(FOREIGN, (lowering, call) -> lowering.foreignTemporals() ? JavaForms.T : JavaForms.NIL);
		natives.put(WANTED, (lowering, call) -> lowering.wantedField() ? JavaForms.T : JavaForms.NIL);
		// a date or a time is asked its calendar only where the program can ask it
		for (String calendars : CALENDARS) {
			natives.put(calendars, (lowering, call) -> lowering.asksCalendars() ? JavaForms.T : JavaForms.NIL);
		}
		// a formatter's pattern: the parts of the letters of weeks, quarters and the
		// modified Julian day are made where a pattern of the program can name them, the
		// words of a quarter where it makes the fields of IsoFields, and the resolving of
		// a field that is no ChronoField where it makes one
		JavaEmitter pattern = (lowering, call) -> {
			lowering.names(call.arguments().get(0), PATTERN_NAMING);
			return lowering.library(FORMATTER, "patterned", operands(call));
		};
		natives.put(FORMATTER + ".ofPattern(String)", pattern);
		natives.put(FORMATTER + ".ofPattern(String,java.util.Locale)", pattern);
		natives.put(FORMATTER_BUILDER + ".appendPattern(String)", (lowering, call) -> {
			lowering.names(call.arguments().get(0), PATTERN_NAMING);
			return lowering.library(FORMATTER_BUILDER, "appended", operands(call));
		});
		// a localized form's time in full or long names its zone in words, the letter z
		// of its pattern: noted unless the time's style is short, medium or none
		natives.put(FORMATTER + ".ofLocalizedTime(java.time.format.FormatStyle)", (lowering, call) -> {
			lowering.namesPattern(briefTime(call.argument(0)) ? "" : "z");
			return lowering.library(FORMATTER, "localizedTime", operands(call));
		});
		natives.put(FORMATTER + ".ofLocalizedDateTime(java.time.format.FormatStyle)", (lowering, call) -> {
			lowering.namesPattern(briefTime(call.argument(0)) ? "" : "z");
			return lowering.library(FORMATTER, "localizedDateTime", operands(call));
		});
		natives.put(FORMATTER + ".ofLocalizedDateTime(java.time.format.FormatStyle,java.time.format.FormatStyle)",
				(lowering, call) -> {
					lowering.namesPattern(briefTime(call.argument(1)) ? "" : "z");
					return lowering.library(FORMATTER, "localizedDateTime", operands(call));
				});
		natives.put(FORMATTER_BUILDER + ".appendLocalized(java.time.format.FormatStyle,java.time.format.FormatStyle)",
				(lowering, call) -> {
					lowering.namesPattern(briefTime(call.argument(1)) ? "" : "z");
					return lowering.library(FORMATTER_BUILDER, "localized", operands(call));
				});
		// a formatter resolves leniently only where it can be given a style that is no
		// read of STRICT or SMART
		natives.put(FORMATTER + ".withResolverStyle(java.time.format.ResolverStyle)", (lowering, call) -> lowering
			.library(FORMATTER,
					reads(call.argument(0), "java.time.format.ResolverStyle.STRICT")
							|| reads(call.argument(0), "java.time.format.ResolverStyle.SMART") ? "styled" : "anyStyle",
					operands(call)));
		// a localized form of a requested template names the letters its patterns can
		// hold
		natives.put(FORMATTER + ".ofLocalizedPattern(String)", (lowering, call) -> {
			lowering.names(call.arguments().get(0), TEMPLATE_NAMING);
			return lowering.library(FORMATTER, "localizedPattern", operands(call));
		});
		natives.put(FORMATTER_BUILDER + ".appendLocalized(String)", (lowering, call) -> {
			lowering.names(call.arguments().get(0), TEMPLATE_NAMING);
			return lowering.library(FORMATTER_BUILDER, "templated", operands(call));
		});
		PATTERN_LETTERS.forEach((question, letters) -> natives.put(question,
				(lowering, call) -> lowering.namesLetter(letters) ? JavaForms.T : JavaForms.NIL));
		MADE.forEach((question, made) -> natives.put(question,
				(lowering, call) -> lowering.makesClass(made) ? JavaForms.T : JavaForms.NIL));
		OTHERS.forEach((question, classes) -> natives.put(question,
				(lowering, call) -> lowering.makesOther(classes.get(0), classes.get(1)) ? JavaForms.T : JavaForms.NIL));
		REACHES.forEach((question, member) -> natives.put(question,
				(lowering, call) -> lowering.reaches(member) ? JavaForms.T : JavaForms.NIL));
		natives.put("java.time.format.Step.region0(String)", library(ZONE_ID, "region"));
		natives.put("java.time.format.Step.held0()", library(ZONE_CHANGES, "held"));
		natives.put("java.time.format.Step.regional()",
				(lowering, call) -> lowering.holdsRegions() ? JavaForms.T : JavaForms.NIL);
		// the rules of a region are read and asked in their own package, which the
		// classes of java.time reach here: a static method each, of the class the rules
		// of a region are
		natives.put(ZONE_CHANGES + ".table0()", (lowering, call) -> lowering.regions());
		// the names in words of the regions a program holds, written and read
		natives.put("java.time.format.ZoneNames.written0()", (lowering, call) -> lowering.zoneNames(false));
		natives.put("java.time.format.ZoneNames.read0()", (lowering, call) -> lowering.zoneNames(true));
		natives.put(ZONE_ID + ".held0(String)", library(ZONE_CHANGES, "find"));
		natives.put(ZONE_ID + ".names0(java.util.Set)", library(ZONE_CHANGES, "names"));
		natives.put("java.time.TzRegion.wall0(java.time.zone.ZoneRules,long)", library(ZONE_CHANGES, "wall"));
		natives.put("java.time.TzRegion.placed0(java.time.zone.ZoneRules,long,long[])",
				library(ZONE_CHANGES, "placed"));
		// the resources a program reads: found while it is lowered
		natives.put("java.lang.Class.getResourceAsStream(String)",
				(lowering, call) -> lowering.classResource(call, false));
		natives.put("java.lang.Class.getResource(String)", (lowering, call) -> lowering.classResource(call, true));
		natives.put("java.lang.Class.getClassLoader()", (lowering, call) -> lowering.classLoader(call));
		natives.put("java.lang.Class.getModule()", (lowering, call) -> lowering.library("java.lang.ClassLoader",
				"moduleOf", List.of(lowering.classLoader(call))));
		for (String loader : List.of("java.lang.ClassLoader.get", "java.lang.ClassLoader.getSystem")) {
			natives.put(loader + "ResourceAsStream(String)", (lowering, call) -> lowering.loaderResource(call, 0));
			natives.put(loader + "Resource(String)", (lowering, call) -> lowering.loaderResource(call, 1));
			natives.put(loader + "Resources(String)", (lowering, call) -> lowering.loaderResource(call, 2));
		}
		natives.put("rontolisp.internal.ResourceTable.table0()", (lowering, call) -> lowering.resourceTable());
		natives.put("rontolisp.internal.ResourceTable.located0(int,String,String,String)",
				library("java.net.URL", "located"));
		JavaEmitter bundle = (lowering, call) -> lowering.bundle(call);
		natives.put("java.util.ResourceBundle.getBundle(String)", bundle);
		natives.put("java.util.ResourceBundle.getBundle(String,java.util.Locale)", bundle);
		natives.put("java.util.ResourceBundle.getBundle(String,java.util.Locale,ClassLoader)", bundle);
		natives.put("java.util.ResourceBundle.getBundle(String,java.util.ResourceBundle.Control)", bundle);
		natives.put("java.util.ResourceBundle.getBundle(String,java.util.Locale,java.util.ResourceBundle.Control)",
				bundle);
		natives.put(
				"java.util.ResourceBundle.getBundle(String,java.util.Locale,ClassLoader,java.util.ResourceBundle.Control)",
				bundle);
		natives.put("java.util.ResourceBundle.getBundle(String,Module)", bundle);
		natives.put("java.util.ResourceBundle.getBundle(String,java.util.Locale,Module)", bundle);
		natives.put("java.util.ResourceBundle.reach(ClassLoader)", library("java.lang.ClassLoader", "reach"));
		natives.put("java.util.ResourceBundle.loader0(int)", library("java.lang.ClassLoader", "ofReach"));
		natives.put("java.util.ResourceBundle.classBundle(String,boolean)",
				(lowering, call) -> lowering.classBundle(call));
		natives.put("java.util.ServiceLoader.load(Class)", (lowering, call) -> lowering.services(call, 0));
		natives.put("java.util.ServiceLoader.load(Class,ClassLoader)", (lowering, call) -> lowering.services(call, 1));
		natives.put("java.util.ServiceLoader.loadInstalled(Class)", (lowering, call) -> lowering.services(call, 2));
		// java.util.Date and java.sql: the host's offset at any instant, as above
		natives.put("rontolisp.internal.Dates.local0(long)", host("%local-time-offset"));
		return Map.copyOf(natives);
	}

}
