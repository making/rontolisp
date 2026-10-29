package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Interop forms of the Clojure lowering: the java: surface over host classes.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureInteropLowering {

	private ClojureInteropLowering() {
	}

	static final LispSymbol JAVA_CALL = new LispSymbol("JAVA:CALL");

	static final LispSymbol JAVA_STATIC = new LispSymbol("JAVA:STATIC");

	static final LispSymbol JAVA_NEW = new LispSymbol("JAVA:NEW");

	static final LispSymbol JAVA_FIELD = new LispSymbol("JAVA:FIELD");

	static final LispSymbol JAVA_PROXY = new LispSymbol("JAVA:PROXY");

	static final LispSymbol JAVA_SUBCLASS = new LispSymbol("JAVA:SUBCLASS");

	private static final String OBJECT = "java.lang.Object";

	private static final String STRING = "java.lang.String";

	/**
	 * A {@code java:new} / {@code java:call} / {@code java:static} call over its parts
	 * (the names, then the arguments), ended in the markers of the oracle's conventions
	 * ({@code compiler.JavaMarkers}): {@code :java-false} always -- Java's {@code false}
	 * comes back as the false object, as the oracle's is Java's {@code Boolean.FALSE},
	 * and a fn implementing {@code Comparator} answers as the oracle's
	 * {@code AFunction.compare}, and one converted to {@code Runnable}, {@code Callable}
	 * or {@code Comparator} is the fn Java hands back -- and {@code :functional} when an
	 * argument may be a function: a Clojure fn passed where an interface is expected
	 * implements every abstract method by the method's arguments, as the oracle's fn
	 * implements a functional interface, never as a {@code java:proxy} called with the
	 * method's name first; where the host is, Java calls it through {@link #HOST_CALL}
	 * ({@link #answered}). A literal {@code false} argument is the quoted false object,
	 * which the site resolves on; any other argument that may be a Clojure value with no
	 * Java object of its own -- a collection, a keyword, a symbol, a ratio, an atom, a
	 * type -- goes through {@link #HOST_VALUE} ({@link #hostArgument}), behind the io
	 * family's view of a clojure.java.io value ({@code ClojureIoLowering.crossing}, which
	 * a {@code java:call}'s receiver crosses too).
	 * @param ctx the hub
	 * @param operator the {@code java:} operator
	 * @param parts the names and the arguments
	 * @param names how many leading parts are names, not arguments
	 * @return the call
	 */
	static LispVal hostCall(ClojureLowering ctx, LispSymbol operator, List<LispVal> parts, int names) {
		return hostCall(ctx, operator, parts, names, false);
	}

	/**
	 * {@link #hostCall(ClojureLowering, LispSymbol, List, int)} whose answer may carry a
	 * {@code byte[]} ({@link #carriesBytes}): where the host is, such a call ends in
	 * {@code :octets} too and its answer goes through {@link #HOST_ANSWER}, so a
	 * {@code byte[]} -- the answer, or one an array it answers holds -- is a byte array,
	 * as the oracle's {@code byte[]} is one. A call whose answer the lowering knows to
	 * carry none stays as it was, so the site keeps resolving on its declared type.
	 * @param ctx the hub
	 * @param operator the {@code java:} operator
	 * @param parts the names and the arguments
	 * @param names how many leading parts are names, not arguments
	 * @param bytes whether the answer may carry a {@code byte[]}
	 * @return the call
	 */
	static LispVal hostCall(ClojureLowering ctx, LispSymbol operator, List<LispVal> parts, int names, boolean bytes) {
		// where the host is not (wasm) no member runs, so no value crosses to one
		List<LispVal> crossed = ctx.hostTarget ? ClojureIoLowering.crossing(operator, parts, names) : parts;
		List<LispVal> ended = new ArrayList<>(parts.size() + 2);
		ended.addAll(crossed.subList(0, names));
		boolean literal = true;
		boolean stringConstruction = operator.equals(JAVA_NEW) && !parts.isEmpty()
				&& parts.get(0) instanceof LispString named && named.value().equals("java.lang.String");
		for (int i = names; i < parts.size(); i++) {
			LispVal argument = parts.get(i);
			if (isFalseValue(argument)) {
				ended.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("QUOTE"),
						new LispSymbol(ClojureLowering.FALSE_VALUE_NAME)));
				continue;
			}
			literal &= isLiteral(argument);
			if (stringConstruction && ClojureCharsetLowering.isValue(argument)) {
				// a String construction reads a charset value as it is, whatever the
				// backend (ClojureBytesLowering#stringConstruction)
				ended.add(argument);
				continue;
			}
			ended.add(hostArgument(ctx, argument, crossed.get(i)));
		}
		if (!literal) {
			ended.add(new LispSymbol(LispNames.JAVA_FUNCTIONAL_MARKER));
		}
		ended.add(JAVA_FALSE_MARKER);
		return answered(ctx, operator, ended, bytes, !literal);
	}

	/**
	 * The library function a host call's answer goes through where it may carry a
	 * {@code byte[]} ({@code clojure.lisp}): an {@code (unsigned-byte 8)} vector -- what
	 * a {@code byte[]} is at a call ending in {@code :octets} -- becomes a byte array,
	 * and so does one an array the call answers holds; anything else is itself. A fn or a
	 * proxy body Java calls reads its arguments through it too ({@link #HOST_CALL}).
	 */
	static final String HOST_ANSWER = "RONTOLISP::%CLOJURE-HOST-ANSWER";

	/** The marker a call answering through {@link #HOST_ANSWER} ends in too. */
	static final LispSymbol OCTETS_MARKER = new LispSymbol(LispNames.JAVA_OCTETS_MARKER);

	/**
	 * The library function Java calls a Clojure fn through ({@code clojure.lisp}), named
	 * after the {@code :through} a host call converting a fn ends in: the fn over its
	 * arguments as Clojure values -- read through {@link #HOST_ANSWER}, so a
	 * {@code byte[]} is a byte array -- answering as the oracle's functional interface
	 * adapter answers for the kind Java takes ({@code compiler.JavaMarkers#through}):
	 * where the method answers an object the value's own object
	 * ({@code %clojure-host-member}: a map a {@code Map}, a keyword one Java hands back
	 * as the keyword, a byte array its {@code byte[]}), where it answers a primitive the
	 * adapter's cast (its compiler's where the call chose its member before it ran, its
	 * reflector's otherwise), and where it answers nothing the answer as it is: an answer
	 * a void method drops is never converted, so a lazy seq stays unrealized and no
	 * collection is walked per call.
	 */
	static final String HOST_CALL = "RONTOLISP::%CLOJURE-HOST-CALL";

	/**
	 * The markers ending a form whose functions Java calls through {@link #HOST_CALL}:
	 * {@code :through #'rontolisp::%clojure-host-call}.
	 */
	static final List<LispVal> THROUGH_HOST_CALL = through(HOST_CALL);

	/**
	 * The library function Java calls a {@code proxy}'s body through
	 * ({@code clojure.lisp}): {@link #HOST_CALL}'s reading of its arguments, its answer
	 * unboxed as the oracle's proxy class unboxes one (a primitive by a {@code Boolean},
	 * {@code Character} or {@code Number} cast, unchecked).
	 */
	static final String PROXY_CALL = "RONTOLISP::%CLOJURE-PROXY-CALL";

	/**
	 * {@link #PROXY_CALL} for a {@code proxy} over a class, whose body Java hands the
	 * object first; a fn its constructor arguments convert is called through it too.
	 */
	static final String SUBCLASS_CALL = "RONTOLISP::%CLOJURE-SUBCLASS-CALL";

	// :through #'name.
	private static List<LispVal> through(String name) {
		return List.of(new LispSymbol(LispNames.JAVA_THROUGH_MARKER),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("FUNCTION"), new LispSymbol(name)));
	}

	// The java: call over its ended parts where the host is, ending in :octets too when
	// its answer may carry a byte[] -- answering through HOST_ANSWER -- or it converts a
	// fn, which Java calls through HOST_CALL (and may hand a byte[]).
	private static LispVal answered(ClojureLowering ctx, LispSymbol operator, List<LispVal> ended, boolean bytes,
			boolean converts) {
		if (!ctx.hostTarget || !bytes && !converts) {
			return ClojureLowerUtil.cons(operator, ended);
		}
		List<LispVal> marked = new ArrayList<>(ended);
		marked.add(OCTETS_MARKER);
		if (converts) {
			marked.addAll(THROUGH_HOST_CALL);
		}
		LispVal call = ClojureLowerUtil.cons(operator, marked);
		return bytes ? ClojureLowerUtil.list(new LispSymbol(HOST_ANSWER), call) : call;
	}

	/**
	 * Whether a value of a member's declared type may carry a {@code byte[]} into the
	 * program: {@code byte[]} itself, a type every array is ({@code Object},
	 * {@code Cloneable}, {@code Serializable}), or an array whose elements may.
	 * @param type the declared type's name, in {@link Class#getName()} spelling
	 * @return whether the value may be or hold a {@code byte[]}
	 */
	static boolean carriesBytes(String type) {
		if (ClojureHostClasses.isArray(type)) {
			return type.equals("[B") || carriesBytes(ClojureHostClasses.componentType(type));
		}
		return type.equals(OBJECT) || type.equals("java.lang.Cloneable") || type.equals("java.io.Serializable");
	}

	/**
	 * Whether a call of the member at this count may answer a {@code byte[]}: true unless
	 * the class loads and every public method of the name the call may choose -- static
	 * ones only for a static call -- declares a type that carries none
	 * ({@link #carriesBytes}). An unknown class or member may answer anything.
	 * @param className the class, or null when the receiver's class is unknown
	 * @param designator the member, perhaps tagged ({@code m(T1,T2)})
	 * @param arity the argument count
	 * @param statics whether the call is a static one
	 * @return whether the answer may carry a {@code byte[]}
	 */
	static boolean answersBytes(@Nullable String className, String designator, int arity, boolean statics) {
		if (className == null) {
			return true;
		}
		int tagged = designator.indexOf('(');
		String member = tagged < 0 ? designator : designator.substring(0, tagged);
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		if (found == null) {
			return true;
		}
		try {
			boolean seen = false;
			for (ClojureJavaClasses.Member method : found.methods()) {
				if (!method.name().equals(member) || statics && !method.isStatic()) {
					continue;
				}
				int count = method.parameterCount();
				if (count != arity && !(method.isVarArgs() && count - 1 <= arity)) {
					continue;
				}
				seen = true;
				if (carriesBytes(method.returnType())) {
					return true;
				}
			}
			return !seen;
		}
		catch (LinkageError | SecurityException _) {
			return true;
		}
	}

	/**
	 * Whether a static field's value may carry a {@code byte[]}: true unless the class
	 * loads and has the public field, of a type that carries none.
	 * @param className the class
	 * @param field the field
	 * @return whether its value may carry a {@code byte[]}
	 */
	static boolean fieldCarriesBytes(String className, String field) {
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		if (found == null) {
			return true;
		}
		try {
			ClojureJavaClasses.Field declared = found.field(field);
			return declared == null || carriesBytes(declared.type());
		}
		catch (LinkageError | SecurityException _) {
			return true;
		}
	}

	/**
	 * The library function a value handed to a {@code java:} member goes through
	 * ({@code clojure.lisp}): a Clojure value with no Java object of its own becomes an
	 * object Java reads as the oracle's -- a collection a read-only {@code java:view}, a
	 * keyword, symbol or ratio a {@code java:handle} hashing and ordering as the
	 * oracle's, any other value a handle equal only to itself -- which Java hands back as
	 * the value itself; a fn (which the call converts, Java calling it through
	 * {@link #HOST_CALL}), a condition and a value Java has already are themselves.
	 */
	static final String HOST_VALUE = "RONTOLISP::%CLOJURE-HOST-VALUE";

	/**
	 * An argument of a host call as the call hands it to Java: through
	 * {@link #HOST_VALUE}, unless it is a value no Clojure value Java lacks can be -- a
	 * literal, a fn form, a construction, a {@code make-array}, a local bound to one of
	 * those -- which the site keeps resolving on (a fn the call converts, Java calling it
	 * through {@link #HOST_CALL}), or a Date or UUID construction, which hands the host
	 * object at once ({@link ClojureTimeValueLowering#hostConstruction}). Where the host
	 * is not (wasm), every {@code java:} call is a call-time error and the argument is
	 * left as it is.
	 * @param ctx the hub
	 * @param argument the lowered argument
	 * @return what the call hands Java
	 */
	static LispVal hostArgument(ClojureLowering ctx, LispVal argument) {
		return hostArgument(ctx, argument, argument);
	}

	/**
	 * {@link #hostArgument(ClojureLowering, LispVal)} over the argument as it crosses
	 * ({@code ClojureIoLowering.crossing}: behind the io family's view of a
	 * clojure.java.io value), which a plain argument -- no io value either -- does not
	 * need.
	 * @param ctx the hub
	 * @param argument the lowered argument
	 * @param crossed the argument behind the io view, or the argument itself
	 * @return what the call hands Java
	 */
	static LispVal hostArgument(ClojureLowering ctx, LispVal argument, LispVal crossed) {
		if (!ctx.hostTarget) {
			return crossed;
		}
		@Nullable LispVal hostObject = ClojureTimeValueLowering.hostConstruction(argument);
		if (hostObject != null) {
			// a Date or UUID made for the member alone: the host object at once
			return hostObject;
		}
		hostObject = ClojureCharsetLowering.hostConstruction(argument);
		if (hostObject != null) {
			// a charset named for the member alone: the host Charset at once
			return hostObject;
		}
		if (isLiteral(argument) || isPlainForm(argument)
				|| argument instanceof LispSymbol local && isPlainLocal(ctx, local)) {
			return argument;
		}
		return ClojureLowerUtil.list(new LispSymbol(HOST_VALUE), crossed);
	}

	/**
	 * Whether a lowered form's value is no Clojure value Java lacks, and has a kind a
	 * {@code java:} site resolves on: a literal, a fn ({@code lambda} or
	 * {@code function}), a {@code java:new} or a {@code proxy} construction -- or an
	 * array a {@code make-array} makes, which is a vector here and converts where Java
	 * expects an array, never the read-only {@code List} a Clojure vector crosses as.
	 */
	static boolean isPlainForm(LispVal form) {
		if (isLiteral(form) || constructedClass(form) != null) {
			return true;
		}
		return form instanceof LispCons cell && (ClojureLowerUtil.isSymbolNamed(cell.car(), "LAMBDA")
				|| ClojureLowerUtil.isSymbolNamed(cell.car(), "FUNCTION")
				|| ClojureLowerUtil.isSymbolNamed(cell.car(), "MAKE-ARRAY"));
	}

	/**
	 * Whether a {@code let}-bound local holds a value {@link #isPlainForm} -- recorded
	 * where it was bound ({@code ClojureBindingLowering.noteHostClass}) and visible here.
	 */
	static boolean isPlainLocal(ClojureLowering ctx, LispSymbol ref) {
		ClojureLowering.HostClass held = ctx.hostClasses.get(ref.name());
		if (held == null) {
			return false;
		}
		for (int i = held.depth(); i < ctx.scopes.size(); i++) {
			if (ctx.scopes.get(i).containsKey(held.name())) {
				return false;
			}
		}
		return true;
	}

	/** The marker every host call and implementation the lowering builds ends in. */
	static final LispSymbol JAVA_FALSE_MARKER = new LispSymbol(LispNames.JAVA_FALSE_MARKER);

	// Whether a lowered form is the false object's variable: what a false literal lowers
	// to.
	private static boolean isFalseValue(LispVal form) {
		return form instanceof LispSymbol symbol && ClojureLowering.FALSE_VARIABLE.equals(symbol.name());
	}

	// Whether every lowered argument is a literal no fn can be: a string, number,
	// character, nil, true, false or a keyword.
	private static boolean allLiteral(List<LispVal> arguments) {
		for (LispVal argument : arguments) {
			if (!isLiteral(argument) && !isFalseValue(argument)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isLiteral(LispVal argument) {
		return argument instanceof LispString || argument instanceof LispInteger || argument instanceof LispDouble
				|| argument instanceof LispChar || argument instanceof LispNil || argument instanceof LispTrue
				|| argument instanceof LispSymbol symbol && symbol.isKeyword();
	}

	/**
	 * A {@code java:field} read over its two parts, ended in {@code :java-false}: a false
	 * field is the false object ({@code Boolean/FALSE} is the oracle's {@code false}).
	 * @param target the class name literal or the object form
	 * @param field the field name
	 * @return the read
	 */
	static LispVal fieldCall(LispVal target, String field) {
		return ClojureLowerUtil.cons(JAVA_FIELD, List.of(target, LispString.literal(field), JAVA_FALSE_MARKER));
	}

	/**
	 * {@link #fieldCall(LispVal, String)} whose value may carry a {@code byte[]}: where
	 * the host is, ended in {@code :octets} too and answering through
	 * {@link #HOST_ANSWER}.
	 * @param ctx the hub
	 * @param target the class name literal or the object form
	 * @param field the field name
	 * @param bytes whether the value may carry a {@code byte[]}
	 * @return the read
	 */
	static LispVal fieldCall(ClojureLowering ctx, LispVal target, String field, boolean bytes) {
		return answered(ctx, JAVA_FIELD, List.of(target, LispString.literal(field), JAVA_FALSE_MARKER), bytes, false);
	}

	/**
	 * The host classes the lowering builds itself as a Common Lisp string output stream
	 * on every backend -- never a host object, which no backend writes to and wasm
	 * refuses outright. A zero-argument {@code java.io.StringWriter} is one (the oracle's
	 * own {@code with-out-str} construction), and so are a zero-argument
	 * {@code java.lang.StringBuilder} or {@code java.lang.StringBuffer}: to these
	 * programs a builder is the same object ({@code .append} of any value's {@code str},
	 * {@code .toString}, {@code .length}, {@code .setLength}, {@code .charAt}). A
	 * one-argument builder or buffer construction is one the argument fills at run time
	 * ({@link #STRING_BUILDER_1}); a one-argument {@code java.io.StringWriter}
	 * construction is an empty one ({@link #STRING_WRITER_1}).
	 */
	static final String STRING_WRITER_CLASS = "java.io.StringWriter";

	static final String STRING_BUILDER_CLASS = "java.lang.StringBuilder";

	static final String STRING_BUFFER_CLASS = "java.lang.StringBuffer";

	/**
	 * {@code java.util.HashMap}: a zero-argument construction is the empty map and a
	 * one-argument construction the map itself where the host is not (wasm;
	 * {@link #hashMapEmpty}, {@link #hashMapCopy}), like the portable {@code :cljs}
	 * branch of reitit's {@code fast-map}. Where the host is the construction keeps its
	 * {@code java:new}, whose mutable object the host tests pin.
	 */
	static final String HASH_MAP_CLASS = "java.util.HashMap";

	/**
	 * {@code java.util.Random}: a zero- or one-argument construction is the portable
	 * 48-bit LCG generator where the host is not (wasm; {@link #randomConstruction},
	 * {@link #RANDOM_NEW}), so data.generators' {@code *rnd*} root loads there. Where the
	 * host is the construction keeps its {@code java:new}, whose host object the host
	 * tests pin.
	 */
	static final String RANDOM_CLASS = "java.util.Random";

	/** A portable {@code java.util.Random}: its {@code (:C%RANDOM seed ...)} list. */
	static final String RANDOM_NEW = "RONTOLISP::%CLOJURE-RANDOM-NEW";

	/** Host entropy as a 48-bit integer: a zero-argument construction's seed. */
	static final String RANDOM_ENTROPY = "RONTOLISP::%CLOJURE-RANDOM-ENTROPY";

	/** Whether X is a portable {@code java.util.Random}. */
	static final String RANDOM_P = "RONTOLISP::%CLOJURE-RANDOM-P";

	/** A portable draw of the JDK's {@code nextInt()}. */
	static final String RANDOM_NEXT_INT = "RONTOLISP::%CLOJURE-RANDOM-INT";

	/** A portable draw of the JDK's {@code nextInt(int)}. */
	static final String RANDOM_NEXT_INT_BOUND = "RONTOLISP::%CLOJURE-RANDOM-INT-BOUND";

	/** A portable draw of the JDK's {@code nextLong()}. */
	static final String RANDOM_NEXT_LONG = "RONTOLISP::%CLOJURE-RANDOM-LONG";

	/** A portable draw of the JDK's {@code nextFloat()}. */
	static final String RANDOM_NEXT_FLOAT = "RONTOLISP::%CLOJURE-RANDOM-FLOAT";

	/** A portable draw of the JDK's {@code nextDouble()}. */
	static final String RANDOM_NEXT_DOUBLE = "RONTOLISP::%CLOJURE-RANDOM-DOUBLE";

	/** A portable draw of the JDK's {@code nextBoolean()}. */
	static final String RANDOM_NEXT_BOOLEAN = "RONTOLISP::%CLOJURE-RANDOM-BOOLEAN";

	/** A portable draw of the JDK's {@code nextGaussian()}. */
	static final String RANDOM_NEXT_GAUSSIAN = "RONTOLISP::%CLOJURE-RANDOM-GAUSSIAN";

	/** A portable {@code setSeed(long)}: re-seeds and answers nil. */
	static final String RANDOM_SET_SEED = "RONTOLISP::%CLOJURE-RANDOM-SET-SEED";

	/**
	 * The portable {@code java.util.Random} members by name and argument count:
	 * {@code next} is protected and no Clojure call reaches it, so it stays out.
	 */
	static final Map<String, Set<Integer>> RANDOM_METHODS = Map.of("nextInt", Set.of(0, 1), "nextLong", Set.of(0),
			"nextFloat", Set.of(0), "nextDouble", Set.of(0), "nextBoolean", Set.of(0), "nextGaussian", Set.of(0),
			"setSeed", Set.of(1));

	/** A zero-argument {@code java.io.StringWriter}: a string output stream. */
	static final String STRING_WRITER = "RONTOLISP::%CLOJURE-STRING-WRITER";

	/**
	 * A one-argument {@code java.lang.StringBuilder} or {@code java.lang.StringBuffer}
	 * construction: a string output stream.
	 */
	static final String STRING_BUILDER_1 = "RONTOLISP::%CLOJURE-STRING-BUILDER-1";

	/**
	 * A one-argument {@code java.io.StringWriter} construction: a string output stream.
	 */
	static final String STRING_WRITER_1 = "RONTOLISP::%CLOJURE-STRING-WRITER-1";

	/**
	 * Whether X is a string output stream: a {@code java.io.StringWriter},
	 * {@code java.lang.StringBuilder} or {@code java.lang.StringBuffer} here, one object.
	 */
	static final String STRING_OUTPUT_P = "RONTOLISP::%CLOJURE-STRING-OUTPUT-P";

	/**
	 * {@code StringBuilder.setLength} of a string output stream: the text so far cut or
	 * null-padded to the length, answering nil.
	 */
	static final String STRING_BUILDER_SET_LENGTH = "RONTOLISP::%CLOJURE-STRING-BUILDER-SET-LENGTH";

	/** A reader over a {@code java.io.StringReader}: a string input stream. */
	static final String STRING_READER = "RONTOLISP::%CLOJURE-STRING-READER";

	/** The stream test, an arm of {@code ClojureArms.Family.STREAM}. */
	static final String STREAM_P = "RONTOLISP::%CLOJURE-STREAM-P";

	/** A stream's toString. */
	static final String STREAM_STRING = "RONTOLISP::%CLOJURE-STREAM-STRING";

	/** A read of {@code *in*} as a value. */
	static final String STANDARD_INPUT_READ = "RONTOLISP::%CLOJURE-IN";

	/** A call of {@code clojure.java.io/reader}, the built-in namespace's var. */
	static final String JIO_READER = ClojureLowering.varSym(ClojureLowering.varKey("clojure.java.io", "reader")).name();

	/**
	 * The zero-argument {@code Throwable} methods an exception condition answers
	 * ({@link #instanceCallLoweredWithClass}); {@code toString} is
	 * {@link #valueToString}'s.
	 */
	static final Set<String> EXCEPTION_METHODS = Set.of("getMessage", "getLocalizedMessage", "getCause");

	/**
	 * The stack-trace methods of an exception, to the library function answering them
	 * ({@code clojure.lisp}): a condition has no frames, so they need no exception
	 * reader.
	 */
	static final Map<String, String> STACK_TRACE_METHODS = Map.of("printStackTrace",
			"RONTOLISP::%CLOJURE-PRINT-STACK-TRACE", "getStackTrace", "RONTOLISP::%CLOJURE-STACK-TRACE");

	/**
	 * A possible interop head: {@code (.} target method ...), {@code (.. ...)} chains,
	 * {@code (.method target ...)} and {@code (.-field target)} instance forms,
	 * {@code (Class. ...)} construction and {@code (Class/member ...)} statics. Null when
	 * the name is no interop shape, so the call keeps falling through.
	 */
	static @Nullable LispVal interopCall(ClojureLowering ctx, String name, List<LispVal> items) {
		if (name.equals(".")) {
			return dotForm(ctx, items);
		}
		if (name.equals("..")) {
			return dotDotOf(ctx, items);
		}
		if (name.startsWith(".")) {
			if (name.startsWith(".-")) {
				ClojureLowerUtil.isTrue(name.length() > 2, "a field read needs a field name: " + name);
				ClojureLowerUtil.isTrue(items.size() == 2, name + " takes a target");
				return fieldRead(ctx, ctx.lower(items.get(1)), name.substring(2));
			}
			ClojureLowerUtil.isTrue(name.length() > 1, "a method call needs a method name");
			ClojureLowerUtil.isTrue(items.size() >= 2, name + " takes a target and arguments");
			if (name.equals(".openStream")) {
				// a read of an http: or https: URL the program spells names fetch
				ctx.readsUrlOf(items.get(1));
			}
			return instanceCall(ctx, ctx.lower(items.get(1)), name.substring(1), items.subList(2, items.size()));
		}
		if (isConstructionHead(name)) {
			String base = name.substring(0, name.length() - 1);
			String type = ctx.typeKeyOf(base);
			if (type != null) {
				// (T. args...) constructs the record or deftype, like ->T
				return ctx.lower(ClojureLowerUtil.cons(new LispSymbol(constructorSpelling(ctx, type)),
						items.subList(1, items.size())));
			}
			String constructed = ClojureNamespaceLowering.resolveClass(ctx, name.substring(0, name.length() - 1));
			return hostConstruction(ctx, constructed, items.subList(1, items.size()), null);
		}
		return memberCall(ctx, name, items, null);
	}

	/**
	 * A qualified member in call position, {@code (Class/member args...)}: a static call,
	 * {@code Class/.method} an instance call on the first argument, {@code Class/new} the
	 * construction ({@code R/new} of a record or deftype its positional constructor).
	 * {@code tags} are the {@code ^[types]} param tags, naming the overload through the
	 * {@code java:} parameter-tag designator; null when untagged. Null when the name is
	 * no classlike slash form, so the call keeps falling through.
	 */
	static @Nullable LispVal memberCall(ClojureLowering ctx, String name, List<LispVal> items, @Nullable LispVal tags) {
		LispVal langStatic = items.size() == 1 && tags == null ? ClojureLangClasses.staticValue(ctx, name) : null;
		if (langStatic != null) {
			// (Class/FIELD) of a clojure.lang class the shipped namespace defines
			return langStatic;
		}
		QualifiedMember qualified = qualifiedMember(ctx, name);
		if (qualified == null) {
			return null;
		}
		List<LispVal> argDatums = items.subList(1, items.size());
		if (qualified.typeKey() != null) {
			// (R/new args...) constructs the record or deftype, like ->R
			return ctx
				.lower(ClojureLowerUtil.cons(new LispSymbol(constructorSpelling(ctx, qualified.typeKey())), argDatums));
		}
		String cls = qualified.cls();
		String member = qualified.member();
		@Nullable List<String> types = tags == null ? null : tagTypes(ctx, tags);
		if (member.equals("new")) {
			checkTagCount(types, argDatums.size(), "constructor", cls, name);
			return hostConstruction(ctx, cls, argDatums, types);
		}
		if (member.startsWith(".")) {
			ClojureLowerUtil.isTrue(!argDatums.isEmpty(), name + " takes a target and arguments");
			String method = member.substring(1);
			checkTagCount(types, argDatums.size() - 1, "method " + method, cls, name);
			return instanceCallLoweredWithClass(ctx, ctx.lower(argDatums.get(0)), cls, method,
					types != null ? designator(method, types)
							: hintedDesignator(ctx, method, argDatums.subList(1, argDatums.size())),
					ctx.lowers(items, 2));
		}
		checkTagCount(types, argDatums.size(), "method " + member, cls, name);
		if (argDatums.isEmpty() && types == null) {
			// no arguments: the zero-argument static method when the host
			// class has one, else the static field read (whose run-time
			// error names an unknown member or class, like before)
			return staticNoArg(ctx, cls, member);
		}
		return staticCall(ctx, cls, member,
				types != null ? designator(member, types) : hintedDesignator(ctx, member, argDatums),
				ctx.lowers(items, 1));
	}

	/**
	 * A {@code Class/member} spelling split and resolved: the class (null for a record or
	 * deftype, whose key {@code typeKey} is), and the member as written ({@code .method},
	 * {@code new} or a static name). Null when the head is no class, the member is empty
	 * or slashed, or a record or deftype names anything but {@code new}.
	 */
	static @Nullable QualifiedMember qualifiedMember(ClojureLowering ctx, String name) {
		int slash = name.indexOf('/');
		if (slash <= 0) {
			return null;
		}
		String head = name.substring(0, slash);
		String member = name.substring(slash + 1);
		if (member.isEmpty() || member.indexOf('/') >= 0 || member.equals(".")
				|| !ClojureNamespaceLowering.isClasslike(ctx, head)) {
			return null;
		}
		String type = ctx.typeKeyOf(head);
		if (type != null) {
			return member.equals("new") ? new QualifiedMember(head, member, type) : null;
		}
		return new QualifiedMember(ClojureNamespaceLowering.resolveClass(ctx, head), member, null);
	}

	/** A resolved {@code Class/member} spelling ({@link #qualifiedMember}). */
	record QualifiedMember(String cls, String member, @Nullable String typeKey) {
	}

	/**
	 * The {@code java:} designator of a member under param tags: the bare name untagged,
	 * else {@code name(T1,T2)} -- the parameter-tag spelling {@code java:call},
	 * {@code java:static} and {@code java:new} select an overload by.
	 */
	static String designator(String member, @Nullable List<String> types) {
		return types == null ? member : member + "(" + String.join(",", types) + ")";
	}

	/**
	 * The {@code java:} designator of an untagged member over its argument datums' type
	 * hints: the bare name when no argument carries a class hint ({@link #classHint}),
	 * else {@code name(^C1,_)} -- the hint spelling {@code java:call},
	 * {@code java:static} and {@code java:new} narrow the candidates by where the site's
	 * class is known before it runs (the oracle's compiler choosing by its arguments'
	 * {@code :tag}s; a site left to run time calls by reflection, which reads no hint, as
	 * the oracle's reflector reads none). Where no host is, the bare name: no member runs
	 * there.
	 */
	static String hintedDesignator(ClojureLowering ctx, String member, List<LispVal> argDatums) {
		if (!ctx.hostTarget) {
			return member;
		}
		List<String> hints = new ArrayList<>(argDatums.size());
		boolean hinted = false;
		for (LispVal datum : argDatums) {
			String hint = classHint(ctx, datum);
			hinted |= hint != null;
			hints.add(hint == null ? "_" : "^" + hint);
		}
		return hinted ? member + "(" + String.join(",", hints) + ")" : member;
	}

	/**
	 * The host class an argument datum's {@code :tag} names (the outermost reader
	 * metadata layer carrying one), else the hint of the local a bare name refers to
	 * ({@link ClojureLowering#localHint}): an imported, {@code java.lang} or dotted class
	 * name the program's classes hold. Null for none, a primitive or array tag
	 * ({@code ^long}, {@code ^bytes}, {@code ^"[B"}), a record or deftype, or a name no
	 * class answers.
	 */
	static @Nullable String classHint(ClojureLowering ctx, LispVal datum) {
		return datum instanceof LispSymbol local ? ctx.localHint(local.name()) : tagHint(ctx, datum);
	}

	/**
	 * The host class a datum's own {@code :tag} names ({@link #classHint}, reading no
	 * local), or null.
	 */
	static @Nullable String tagHint(ClojureLowering ctx, LispVal datum) {
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		while (parts != null && parts.size() == 3
				&& ClojureLowerUtil.isSymbolNamed(parts.get(0), ClojureLowerUtil.READER_META)) {
			LispVal tag = metaTag(parts.get(2));
			if (tag != null) {
				if (!(tag instanceof LispSymbol named) || named.name().startsWith(":") || named.name().indexOf('/') >= 0
						|| PRIMITIVE_TAGS.contains(named.name()) || ctx.typeKeyOf(named.name()) != null) {
					return null;
				}
				String cls = ClojureNamespaceLowering.resolveClass(ctx, named.name());
				return ClojureHostClasses.exists(cls) ? cls : null;
			}
			parts = ClojureLowerUtil.items(parts.get(1));
		}
		return null;
	}

	/** The tags naming a primitive or a primitive or {@code Object} array. */
	private static final Set<String> PRIMITIVE_TAGS = Set.of("boolean", "byte", "char", "short", "int", "long", "float",
			"double", "void", "booleans", "bytes", "chars", "shorts", "ints", "longs", "floats", "doubles", "objects");

	// The :tag of one reader metadata layer: a bare symbol or string, or a map's :tag
	// entry; null when the layer carries none.
	private static @Nullable LispVal metaTag(LispVal meta) {
		if (meta instanceof LispSymbol named && !named.name().startsWith(":") || meta instanceof LispString) {
			return meta;
		}
		List<LispVal> parts = ClojureLowerUtil.items(meta);
		if (parts == null || parts.isEmpty() || !ClojureLowerUtil.isSymbolNamed(parts.get(0), "%hash-map")) {
			return null;
		}
		for (int i = 1; i + 1 < parts.size(); i += 2) {
			if (ClojureLowerUtil.isSymbolNamed(parts.get(i), ":tag")) {
				return parts.get(i + 1);
			}
		}
		return null;
	}

	/**
	 * The {@code ^[types]} param tags of a datum: the first vector among its reader
	 * metadata layers, or null when it carries none.
	 */
	static @Nullable LispVal paramTags(LispVal datum) {
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		while (parts != null && parts.size() == 3
				&& ClojureLowerUtil.isSymbolNamed(parts.get(0), ClojureLowerUtil.READER_META)) {
			List<LispVal> meta = ClojureLowerUtil.items(parts.get(2));
			if (meta != null && !meta.isEmpty() && meta.get(0) == ClojureReader.VECTOR) {
				return parts.get(2);
			}
			parts = ClojureLowerUtil.items(parts.get(1));
		}
		return null;
	}

	/**
	 * The param tags as {@code java:} designator types: {@code _} any type, a primitive
	 * as itself, {@code ints}/{@code longs}/... and {@code objects} the primitive and
	 * {@code Object} arrays, {@code T/N} an {@code N}-dimensional array of {@code T}, any
	 * other name its class (dotted, imported or {@code java.lang}).
	 */
	static List<String> tagTypes(ClojureLowering ctx, LispVal tags) {
		List<LispVal> parts = ClojureLowerUtil.items(tags);
		if (parts == null || parts.isEmpty() || parts.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("param tags take a vector of class names, not " + tags.print());
		}
		List<String> types = new ArrayList<>();
		for (LispVal tag : parts.subList(1, parts.size())) {
			if (!(tag instanceof LispSymbol named) || named.name().startsWith(":")) {
				throw new LispReadException("param tags take class names, not " + tag.print());
			}
			String spelled = named.name();
			int dims = 0;
			int slash = spelled.lastIndexOf('/');
			if (slash > 0 && slash < spelled.length() - 1
					&& spelled.substring(slash + 1).chars().allMatch(Character::isDigit)) {
				dims = Integer.parseInt(spelled.substring(slash + 1));
				spelled = spelled.substring(0, slash);
			}
			String type = switch (spelled) {
				case "_", "boolean", "byte", "char", "short", "int", "long", "float", "double" -> spelled;
				case "booleans", "bytes", "chars", "shorts", "ints", "longs", "floats", "doubles" ->
					spelled.substring(0, spelled.length() - 1) + "[]";
				case "objects" -> "java.lang.Object[]";
				default -> ClojureNamespaceLowering.resolveClass(ctx, spelled);
			};
			types.add(type + "[]".repeat(dims));
		}
		return types;
	}

	/**
	 * Refuses a tagged call whose argument count is not the tag count, like the oracle's
	 * compile-time {@code expected N arguments, but received M}.
	 */
	private static void checkTagCount(@Nullable List<String> types, int argCount, String what, String cls,
			String spelling) {
		if (types != null && types.size() != argCount) {
			throw new LispReadException(spelling + ": invocation of " + what + " in class " + cls + " expected "
					+ types.size() + " arguments, but received " + argCount);
		}
	}

	/**
	 * A host construction over argument datums, shared by {@code (Class. ...)},
	 * {@code (new Class ...)} and {@code (Class/new ...)}: the reader wrappers and the
	 * zero-argument {@code java.io.StringWriter} as streams, a one-argument
	 * {@code java.lang.StringBuilder} or {@code java.lang.StringBuffer} as a stream the
	 * argument fills (untagged only; a tagged one keeps {@code java:new} under the
	 * param-tag designator), a one-argument {@code java.io.StringWriter} of a capacity as
	 * an empty stream (any other argument the oracle's {@code ClassCastException}, nil
	 * its {@code NullPointerException}), a Date, Timestamp or UUID as the value it is
	 * here ({@link ClojureTimeValueLowering#construction}), an untagged plain throwable
	 * as an exception ({@link #throwableConstruction}), anything else {@code java:new},
	 * under the param-tag designator when tagged.
	 */
	static LispVal hostConstruction(ClojureLowering ctx, String cls, List<LispVal> argDatums,
			@Nullable List<String> types) {
		if (types == null) {
			LispVal wrapped = readerWrapperConstruction(ctx, cls, argDatums);
			if (wrapped != null) {
				return wrapped;
			}
			LispVal printed = printWriterConstruction(ctx, cls, argDatums);
			if (printed != null) {
				return printed;
			}
		}
		LispVal stringStream = stringStreamConstruction(cls, argDatums.size());
		if (stringStream != null) {
			return stringStream;
		}
		if (!ctx.hostTarget && types == null) {
			LispVal hashMap = hashMapEmpty(cls, argDatums.size());
			if (hashMap != null) {
				return hashMap;
			}
			if (argDatums.isEmpty()) {
				LispVal random = randomConstruction(cls, null);
				if (random != null) {
					return random;
				}
			}
			LispVal object = objectToken(cls, argDatums.size());
			if (object != null) {
				return object;
			}
			LispVal dateBuilder = ClojureDateBuilderLowering.construction(cls, argDatums.size());
			if (dateBuilder != null) {
				return dateBuilder;
			}
		}
		if (argDatums.size() == 1 && types == null) {
			// untagged only: param tags name the host overload, which keeps java:new
			LispVal arg = ctx.lower(argDatums.get(0));
			LispVal builder = stringBuilderConstruction(cls, arg);
			if (builder != null) {
				return builder;
			}
			LispVal writer = stringWriterConstruction(cls, arg);
			if (writer != null) {
				return writer;
			}
			if (!ctx.hostTarget) {
				LispVal hashMap = hashMapCopy(ctx, cls, arg);
				if (hashMap != null) {
					return hashMap;
				}
				LispVal random = randomConstruction(cls, arg);
				if (random != null) {
					return random;
				}
			}
		}
		if (!ctx.hostTarget && types == null && argDatums.size() == 2) {
			LispVal hashMap = hashMapCapacity(cls, argDatums);
			if (hashMap != null) {
				return hashMap;
			}
		}
		List<LispVal> lowered = ctx.lowers(argDatums, 0);
		LispVal timeValue = ClojureTimeValueLowering.construction(cls, lowered, types);
		if (timeValue != null) {
			return timeValue;
		}
		if (types == null) {
			LispVal exception = throwableConstruction(ctx, cls, lowered);
			if (exception != null) {
				return exception;
			}
			LispVal own = clojureLangConstruction(cls, lowered);
			if (own != null) {
				return own;
			}
			LispVal io = ClojureIoLowering.construction(cls, lowered);
			if (io != null) {
				return io;
			}
		}
		List<LispVal> args = new ArrayList<>();
		args.add(LispString.literal(types != null ? designator(cls, types) : hintedDesignator(ctx, cls, argDatums)));
		args.addAll(lowered);
		return newCall(ctx, cls, types, args);
	}

	/**
	 * The {@code java:new} of a construction over its parts (the designator, then the
	 * arguments): a {@code java.lang.String} of one to four arguments under the
	 * byte-array family's alias, which decodes a byte array on every backend
	 * ({@link ClojureBytesLowering#stringConstruction}).
	 */
	private static LispVal newCall(ClojureLowering ctx, String cls, @Nullable List<String> types, List<LispVal> parts) {
		LispVal call = hostCall(ctx, JAVA_NEW, parts, 1);
		if (types == null && cls.equals("java.lang.String")) {
			LispVal string = ClojureBytesLowering.stringConstruction(ctx, cls, parts.size() - 1, call);
			if (string != null) {
				return string;
			}
		}
		return call;
	}

	/**
	 * A construction of a {@code clojure.lang} class a collection type's body builds,
	 * which no backend here has as a host class, over already-lowered arguments: a
	 * {@code MapEntry} of a key and a value is the two-member vector a map entry is here,
	 * a {@code SeqIterator} over a seq the library's iterator over it
	 * ({@link ClojureInterfaces#SEQ_ITERATOR}), the no-argument reader macros of a string
	 * and a regex literal a function of the oracle's four arguments reading the literal's
	 * body from a stream. Null for any other class or count.
	 */
	static @Nullable LispVal clojureLangConstruction(String cls, List<LispVal> args) {
		if (cls.equals("clojure.lang.MapEntry") && args.size() == 2) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), args.get(0), args.get(1));
		}
		if (cls.equals("clojure.lang.SeqIterator") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.SEQ_ITERATOR), args.get(0));
		}
		if (args.isEmpty() && cls.equals("clojure.lang.LispReader$StringReader")) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-LISPREADER-STRING"));
		}
		if (args.isEmpty() && cls.equals("clojure.lang.LispReader$RegexReader")) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-LISPREADER-REGEX"));
		}
		if (cls.equals("clojure.lang.Cons") && args.size() == 2) {
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CONS"), args.get(0), args.get(1));
		}
		return null;
	}

	/**
	 * A construction of a plain throwable class ({@link #plainThrowable}) as an exception
	 * condition of the program, so it runs on every backend: no argument, a message, a
	 * message or a cause (decided at run time when the class takes either), or a message
	 * and a cause, each only where the class has that public constructor -- a call the
	 * class has no constructor for keeps {@code java:new} and its host refusal. Null when
	 * the class is no plain throwable or the arity has no such constructor.
	 */
	static @Nullable LispVal throwableConstruction(ClojureLowering ctx, String cls, List<LispVal> args) {
		ClojureJavaClasses.JavaClass type = plainThrowable(cls);
		if (type == null) {
			// a throwable host object becomes an exception of its class once thrown
			List<String> hostChain = ClojureThrowables.chainOf(cls);
			if (hostChain != null) {
				ctx.recordChain(hostChain);
			}
			return null;
		}
		ctx.recordChain(ClojureThrowables.chainOf(type));
		LispVal chain = ClojureThrowables.quoted(ClojureThrowables.chainOf(type));
		LispVal call = switch (args.size()) {
			case 0 -> hasConstructor(type) ? ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.EXCEPTION_NEW),
					chain, ClojureLowering.NIL_CONST, ClojureLowering.NIL_CONST) : null;
			case 1 -> messageOrCauseConstruction(type, chain, args.get(0));
			case 2 -> onlyConstructorsAtArity(type, 2, List.of(STRING, ClojureThrowables.THROWABLE)) ? ClojureLowerUtil
				.list(new LispSymbol(ClojureStateLowering.EXCEPTION_NEW), chain, args.get(0), args.get(1)) : null;
			default -> null;
		};
		if (call != null) {
			ctx.usedExInfo = true;
			ctx.hostExceptionClasses.add(type.name());
		}
		return call;
	}

	/**
	 * {@code (Class. x)}: a run-time choice between the message and the cause when the
	 * class's one-argument constructors are exactly {@code (String)} and
	 * {@code (Throwable)}, the message alone when {@code (String)} is the only one, and a
	 * literal string the message wherever a one-argument constructor takes a string (the
	 * oracle resolves a literal at compile time: {@code (AssertionError. "m")} is its
	 * {@code (Object)} constructor). Anything else is null.
	 */
	private static @Nullable LispVal messageOrCauseConstruction(ClojureJavaClasses.JavaClass type, LispVal chain,
			LispVal arg) {
		if (onlyConstructorsAtArity(type, 1, List.of(STRING, ClojureThrowables.THROWABLE))) {
			return ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.EXCEPTION_NEW_1), chain, arg);
		}
		boolean literal = arg instanceof LispString;
		if (onlyConstructorsAtArity(type, 1, List.of(STRING)) || (literal && takesAString(type))) {
			return ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.EXCEPTION_NEW), chain, arg,
					ClojureLowering.NIL_CONST);
		}
		return null;
	}

	/**
	 * The class a throwable construction may build as an exception condition: a public,
	 * concrete {@code Throwable} that carries nothing beyond a message and a cause -- no
	 * public field, and no public method but {@code Throwable}'s and {@code Object}'s (an
	 * override counts as {@code Throwable}'s) -- so the condition answers every member a
	 * program can call on it. Null for any other class, or none.
	 */
	static ClojureJavaClasses.@Nullable JavaClass plainThrowable(String cls) {
		ClojureJavaClasses.JavaClass type = ClojureHostClasses.find(cls);
		if (type == null || !ClojureHostClasses.isAssignable(ClojureThrowables.THROWABLE, cls) || !type.isPublic()
				|| type.isAbstract()) {
			return null;
		}
		try {
			if (type.hasPublicFields()) {
				return null;
			}
			for (ClojureJavaClasses.Member method : type.methods()) {
				String owner = method.declaringClass();
				if (!owner.equals(ClojureThrowables.THROWABLE) && !owner.equals(OBJECT)
						&& !overridesThrowable(method)) {
					return null;
				}
			}
		}
		catch (LinkageError | SecurityException _) {
			// A class that cannot link is no plain throwable: the construction keeps
			// java:new, whose run-time error names the missing class
			// (.kb/clojure-frontend.md, "Classes compiled against `clojure.lang`").
			return null;
		}
		return type;
	}

	// Whether Throwable has a public method of the name and parameter types: what an
	// instance method overriding one of its own answers for it.
	private static boolean overridesThrowable(ClojureJavaClasses.Member method) {
		if (method.isStatic()) {
			return false;
		}
		ClojureJavaClasses.JavaClass throwable = ClojureHostClasses.find(ClojureThrowables.THROWABLE);
		if (throwable == null) {
			return false;
		}
		for (ClojureJavaClasses.Member declared : throwable.methods()) {
			if (declared.name().equals(method.name()) && declared.parameterTypes().equals(method.parameterTypes())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasConstructor(ClojureJavaClasses.JavaClass type, String... parameters) {
		for (ClojureJavaClasses.Member constructor : type.constructors()) {
			if (constructor.parameterTypes().equals(List.of(parameters))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the public constructors of {@code arity} parameters are exactly the given
	 * ones: each single parameter type of {@code shapes} at arity one, or the one
	 * parameter list {@code shapes} at arity two.
	 */
	private static boolean onlyConstructorsAtArity(ClojureJavaClasses.JavaClass type, int arity, List<String> shapes) {
		Set<List<String>> wanted = new HashSet<>();
		if (arity == 1) {
			for (String shape : shapes) {
				wanted.add(List.of(shape));
			}
		}
		else {
			wanted.add(shapes);
		}
		Set<List<String>> found = new HashSet<>();
		for (ClojureJavaClasses.Member constructor : type.constructors()) {
			if (constructor.parameterCount() == arity) {
				found.add(constructor.parameterTypes());
			}
		}
		return found.equals(wanted);
	}

	private static boolean takesAString(ClojureJavaClasses.JavaClass type) {
		for (ClojureJavaClasses.Member constructor : type.constructors()) {
			if (constructor.parameterCount() == 1
					&& ClojureHostClasses.isAssignable(constructor.parameterTypes().get(0), STRING)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code (. target method args...)}: a static call when the target symbol names a
	 * class (dotted, imported, {@code java.lang} or capitalized), an instance call
	 * otherwise. A {@code (method args...)} list spells the call, with no further
	 * arguments beside it.
	 */
	static LispVal dotForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, ". takes a target, a method and arguments");
		LispVal target = items.get(1);
		LispVal member = items.get(2);
		String method;
		List<LispVal> argDatums;
		List<LispVal> nested = ClojureLowerUtil.items(member);
		if (nested != null && !nested.isEmpty()) {
			ClojureLowerUtil.isTrue(items.size() == 3, ". with a call list takes no further arguments");
			if (!(nested.get(0) instanceof LispSymbol called)) {
				throw new LispReadException(". takes a method name, not " + nested.get(0).print());
			}
			method = called.name();
			argDatums = nested.subList(1, nested.size());
		}
		else {
			if (!(member instanceof LispSymbol called)) {
				throw new LispReadException(". takes a method name, not " + member.print());
			}
			method = called.name();
			argDatums = items.subList(3, items.size());
		}
		if (target instanceof LispSymbol named && ClojureNamespaceLowering.isClasslike(ctx, named.name())) {
			if (argDatums.isEmpty()) {
				// no arguments: the zero-argument static method when the host
				// class has one, else the static field read -- the same rule the
				// (Class/member) spelling follows, so (. Math PI) reads the field
				return staticNoArg(ctx, ClojureNamespaceLowering.resolveClass(ctx, named.name()), method);
			}
			List<LispVal> lowered = new ArrayList<>();
			for (LispVal arg : argDatums) {
				lowered.add(ctx.lower(arg));
			}
			return staticCall(ctx, ClojureNamespaceLowering.resolveClass(ctx, named.name()), method, lowered);
		}
		return instanceCall(ctx, ctx.lower(target), method, argDatums);
	}

	/**
	 * {@code (.. target (step args...) name...)}: the steps threaded left to right, each
	 * lowered as it goes so a step's declared return type carries the known class to the
	 * next one. The first step over a classlike target stays the static path, like
	 * {@code .}; the rest are instance calls.
	 */
	static LispVal dotDotOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, ".. takes a target and steps");
		LispVal target = items.get(1);
		if (target instanceof LispSymbol named && ClojureNamespaceLowering.isClasslike(ctx, named.name())) {
			if (items.size() == 2) {
				return ctx.lower(target);
			}
			String cls = ClojureNamespaceLowering.resolveClass(ctx, named.name());
			ClojureLowering.DotStep first = dotStep(items.get(2));
			List<LispVal> loweredFirst = new ArrayList<>();
			for (LispVal arg : first.args()) {
				loweredFirst.add(ctx.lower(arg));
			}
			LispVal cur = loweredFirst.isEmpty() ? staticNoArg(ctx, cls, first.method())
					: staticCall(ctx, cls, first.method(), loweredFirst);
			@Nullable String curClass = declaredStaticReturn(cls, first.method(), loweredFirst.size());
			for (int i = 3; i < items.size(); i++) {
				ClojureLowering.DotStep step = dotStep(items.get(i));
				List<LispVal> loweredArgs = new ArrayList<>();
				for (LispVal arg : step.args()) {
					loweredArgs.add(ctx.lower(arg));
				}
				cur = instanceCallLoweredWithClass(ctx, cur, curClass, step.method(), loweredArgs);
				curClass = declaredInstanceReturn(curClass, step.method(), loweredArgs.size());
			}
			return cur;
		}
		LispVal cur = ctx.lower(target);
		@Nullable String curClass = classOfLowered(ctx, cur);
		for (int i = 2; i < items.size(); i++) {
			ClojureLowering.DotStep step = dotStep(items.get(i));
			List<LispVal> loweredArgs = new ArrayList<>();
			for (LispVal arg : step.args()) {
				loweredArgs.add(ctx.lower(arg));
			}
			cur = instanceCallLoweredWithClass(ctx, cur, curClass, step.method(), loweredArgs);
			curClass = declaredInstanceReturn(curClass, step.method(), loweredArgs.size());
		}
		return cur;
	}

	/** Parses a {@code ..} step datum into its method name and argument datums. */
	static ClojureLowering.DotStep dotStep(LispVal step) {
		List<LispVal> parts = ClojureLowerUtil.items(step);
		if (parts != null && !parts.isEmpty()) {
			if (!(parts.get(0) instanceof LispSymbol called)) {
				throw new LispReadException(".. takes method names and call lists, not " + step.print());
			}
			return new ClojureLowering.DotStep(called.name(), parts.subList(1, parts.size()));
		}
		if (step instanceof LispSymbol bare) {
			return new ClojureLowering.DotStep(bare.name(), List.of());
		}
		throw new LispReadException(".. takes method names and call lists, not " + step.print());
	}

	/**
	 * Reads the host class once: whether {@code member} is a public static field, the
	 * sorted distinct fixed arities of its public static non-variadic methods, and
	 * whether a variadic one exists. An unloadable class answers all absent, so the call
	 * sites keep their old shape and the run-time error names the class.
	 */
	static ClojureLowering.StaticMember staticMember(String className, String member) {
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		if (found == null) {
			return new ClojureLowering.StaticMember(false, List.of(), false);
		}
		try {
			// a static field of the name; without one, the methods decide below
			ClojureJavaClasses.Field declared = found.field(member);
			boolean field = declared != null && declared.isStatic();
			Set<Integer> arities = new HashSet<>();
			boolean variadic = false;
			for (ClojureJavaClasses.Member method : found.methods()) {
				if (!method.name().equals(member) || !method.isStatic() || method.isSynthetic()) {
					continue;
				}
				if (method.isVarArgs()) {
					variadic = true;
				}
				else {
					arities.add(method.parameterCount());
				}
			}
			List<Integer> sorted = new ArrayList<>(arities);
			sorted.sort(Integer::compareTo);
			return new ClojureLowering.StaticMember(field, sorted, variadic);
		}
		catch (LinkageError | SecurityException _) {
			return new ClojureLowering.StaticMember(false, List.of(), false);
		}
	}

	/**
	 * A static call through {@code java:static}: a host {@code false} is the false object
	 * ({@link #hostCall}), like every predicate value.
	 */
	static LispVal staticCall(ClojureLowering ctx, String cls, String member, List<LispVal> args) {
		return staticCall(ctx, cls, member, member, args);
	}

	/**
	 * {@link #staticCall(ClojureLowering, String, String, List)} under a {@code java:}
	 * designator ({@link #designator}); the boolean rule still reads the bare member.
	 */
	static LispVal staticCall(ClojureLowering ctx, String cls, String member, String designator, List<LispVal> args) {
		LispVal timeValue = ClojureTimeValueLowering.staticCall(cls, member, args);
		if (timeValue != null) {
			// UUID/randomUUID, UUID/fromString and System/currentTimeMillis: the values
			// of this front end's own, on every backend
			return timeValue;
		}
		LispVal charset = ClojureCharsetLowering.forName(cls, member, args);
		if (charset != null) {
			// Charset/forName of a literal: the charset value of this front end's own
			return charset;
		}
		if (cls.equals("java.lang.System") && member.equals("exit") && args.size() == 1) {
			// ends the process on every backend, wasm included, like uiop:quit: the
			// status the host sees is the low byte, as the oracle's is on a POSIX host
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("%host-exit"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("logand"), args.get(0), new LispInteger(255)));
		}
		if (cls.equals("clojure.lang.AFn") && member.equals("applyToHelper") && args.size() == 2) {
			// the applyTo an IFn body delegates to: the function called on the members of
			// the argument seq by their count, like the oracle's, on every backend
			// (clojure.lang is no host class here)
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CALL"), args.get(0),
					ClojureSeqLowering.seqAllForm(ctx, args.get(1)));
		}
		if (!ctx.hostTarget && cls.equals("java.lang.String") && member.equals("valueOf") && args.size() == 1) {
			// where no host is, the value's toString (camel-snake-kebab's
			// (String/valueOf ch)); nil the oracle's reflector failure. The host's own
			// answers a value's Java face elsewhere
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-STRING-VALUE-OF"), args.get(0));
		}
		if (!ctx.hostTarget && cls.equals("java.lang.System") && member.equals("getProperty")) {
			// System/getProperty of a literal key where no host is (wasm): no
			// properties exist there, so the one-argument call is nil and the
			// two-argument call its default, like a host without the property
			// (malli.registry's mode and type at load). Nil is the oracle's
			// NullPointerException; a computed key stays the host call, which warns
			// and refuses when it runs there.
			if (args.size() == 1 && args.get(0) instanceof LispString) {
				return ClojureLowering.NIL_CONST;
			}
			if (args.size() == 1 && args.get(0) instanceof LispNil) {
				return ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER, LispString.literal("key can't be null"));
			}
			if (args.size() == 2 && args.get(0) instanceof LispString) {
				return args.get(1);
			}
		}
		if (!ctx.hostTarget && designator.equals(member)) {
			// where no host is, the pure number statics data.json's read path calls
			// answer portably (a param-tagged call names its host overload and
			// stays the host's); the host's own answer them elsewhere
			LispVal portable = ClojurePortableStaticsLowering.staticCall(cls, member, args);
			if (portable != null) {
				return portable;
			}
			// where no host is, a date formatter or zone static is its marker,
			// so malli's formatter defs load; the host's object elsewhere
			LispVal dateStatic = ClojureDateBuilderLowering.staticCall(cls, member, args);
			if (dateStatic != null) {
				return dateStatic;
			}
		}
		LispVal pattern = cls.equals("java.util.regex.Pattern") ? patternStatic(member, args) : null;
		if (pattern != null) {
			// the regex runtime's own pattern, on every backend
			// (camel-snake-kebab's (-> p Pattern/quote Pattern/compile))
			return pattern;
		}
		if (cls.equals("clojure.lang.MapEntry") && member.equals("create") && args.size() == 2) {
			// a map entry is the two-member vector here
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), args.get(0), args.get(1));
		}
		if (cls.equals("clojure.lang.LazilyPersistentVector") && member.equals("createOwning") && args.size() == 1) {
			// an owned array is already the vector here (object-array answers one),
			// so taking ownership is vec, like the oracle's :bb branch (malli's
			// -vmap and -eager-entry-parser)
			return ClojureCollectionLowering.vecForm(ctx, args.get(0));
		}
		if (cls.equals("clojure.lang.PersistentArrayMap") && member.equals("createWithCheck") && args.size() == 1) {
			// the duplicate-checking map build here (malli's -eager-entry-parser)
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CREATE-WITH-CHECK"), args.get(0));
		}
		if (cls.equals("clojure.lang.RT") && member.equals("iter") && args.size() == 1) {
			// an Iterable's own iterator, else the iterator over the seq
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ITER"), args.get(0));
		}
		List<LispVal> call = new ArrayList<>();
		call.add(LispString.literal(cls));
		call.add(LispString.literal(designator));
		call.addAll(args);
		return hostCall(ctx, JAVA_STATIC, call, 2, answersBytes(cls, designator, args.size(), true));
	}

	/**
	 * A {@code java.util.regex.Pattern} static the regex runtime answers, or null:
	 * {@code compile} of one string is {@code re-pattern}'s pattern, {@code quote} the
	 * {@code \Q...\E} source Java's spells, {@code matches} the whole-string match as a
	 * boolean. The flags overload of {@code compile} stays the host's.
	 */
	private static @Nullable LispVal patternStatic(String member, List<LispVal> args) {
		return switch (member) {
			case "compile" -> args.size() == 1
					? ClojureLowerUtil.list(new LispSymbol(ClojureStringLowering.RE_PATTERN), args.get(0)) : null;
			case "quote" -> args.size() == 1
					? ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-PATTERN-QUOTE"), args.get(0)) : null;
			case "matches" -> args.size() == 2 ? ClojureLowerUtil
				.list(new LispSymbol("RONTOLISP::%CLOJURE-PATTERN-MATCHES"), args.get(0), args.get(1)) : null;
			default -> null;
		};
	}

	/**
	 * {@code (Class/member)} or {@code (. Class member)} with no arguments: the
	 * zero-argument static method when the host class has one (a static call through
	 * {@code java:static}), else the static field read through {@code java:field} (whose
	 * run-time error names an unknown member or class, like before). The method wins a
	 * field of the same name, like the oracle's unified resolution; a host {@code false}
	 * is the false object, like every predicate value.
	 */
	static LispVal staticNoArg(ClojureLowering ctx, String cls, String member) {
		LispVal langStatic = ClojureLangClasses.staticValue(ctx, cls, member);
		if (langStatic != null) {
			return langStatic;
		}
		LispVal timeValue = ClojureTimeValueLowering.staticCall(cls, member, List.of());
		if (timeValue != null) {
			return timeValue;
		}
		LispVal charset = ClojureCharsetLowering.field(cls, member);
		if (charset != null) {
			return charset;
		}
		LispVal locale = ClojureLocaleLowering.field(cls, member);
		if (locale != null) {
			// Locale/US (and ROOT, ENGLISH): the value the case-mapping verbs take
			return locale;
		}
		if (!ctx.hostTarget) {
			// where no host is, DateTimeFormatter/ISO_INSTANT is the portable
			// value data.json's defaults read; the host's own object elsewhere
			LispVal formatter = ClojureDateFormatterLowering.field(cls, member);
			if (formatter != null) {
				return formatter;
			}
			// where no host is, a boxed-integer limit is itself, so malli's
			// +max-size+ def loads; the host's field read elsewhere
			LispVal limit = ClojureNumericLimitsLowering.field(cls, member);
			if (limit != null) {
				return limit;
			}
			// where no host is, a ChronoField constant is its marker, which
			// the builder calls take as an evaluated argument; the host's
			// field elsewhere
			LispVal chrono = ClojureDateBuilderLowering.field(cls, member);
			if (chrono != null) {
				return chrono;
			}
		}
		if (staticMember(cls, member).arities().contains(0)) {
			return hostCall(ctx, JAVA_STATIC, List.of(LispString.literal(cls), LispString.literal(member)), 2,
					answersBytes(cls, member, 0, true));
		}
		return fieldCall(ctx, LispString.literal(cls), member, fieldCarriesBytes(cls, member));
	}

	/**
	 * A {@code Class/member} name in value position: the static field read when the host
	 * class has that field, else a member-as-value lambda dispatching by argument count
	 * over the static call (so {@code (every? Character/isWhitespace s)} runs). A member
	 * with only variadic overloads is refused by name (no rest-spread reaches
	 * {@code java:static}); an unknown class or member reads the field, whose run-time
	 * error names what is missing. {@code Class/.method} is a lambda taking the target
	 * first, {@code Class/new} one constructing ({@link #memberValue}). Null when the
	 * name is no classlike slash form, so the call keeps falling through to the
	 * unknown-name refusal.
	 */
	static @Nullable LispVal interopValue(ClojureLowering ctx, String name) {
		return memberValue(ctx, name, null);
	}

	/**
	 * {@link #interopValue} under optional {@code ^[types]} param tags: tagged, the
	 * lambda takes exactly the tagged count (plus the target of an instance method) and
	 * calls the one overload the designator names, like the oracle's tagged value.
	 * Untagged {@code Class/.method} and {@code Class/new} dispatch per fixed arity of
	 * the public instance methods or constructors, and a name with none is refused, like
	 * the oracle's {@code no matches found}.
	 */
	static @Nullable LispVal memberValue(ClojureLowering ctx, String name, @Nullable LispVal tags) {
		LispVal langStatic = tags == null ? ClojureLangClasses.staticValue(ctx, name) : null;
		if (langStatic != null) {
			// a static field of a clojure.lang class the shipped namespace defines
			return langStatic;
		}
		QualifiedMember qualified = qualifiedMember(ctx, name);
		if (qualified == null) {
			return null;
		}
		if (qualified.typeKey() != null) {
			// R/new is the record or deftype's positional constructor, like ->R
			return ctx.lower(new LispSymbol(constructorSpelling(ctx, qualified.typeKey())));
		}
		String cls = qualified.cls();
		String member = qualified.member();
		@Nullable List<String> types = tags == null ? null : tagTypes(ctx, tags);
		if (member.equals("new")) {
			List<Integer> arities = types != null ? List.of(types.size()) : constructorArities(cls, name);
			return arityLambda(ctx, arities, name, args -> hostConstructionLowered(ctx, cls, args, types));
		}
		if (member.startsWith(".")) {
			String method = member.substring(1);
			List<Integer> arities = types != null ? List.of(types.size() + 1) : instanceArities(cls, method, name);
			return arityLambda(ctx, arities, name, args -> instanceCallLoweredWithClass(ctx, args.get(0), cls, method,
					designator(method, types), args.subList(1, args.size())));
		}
		if (types != null) {
			return arityLambda(ctx, List.of(types.size()), name,
					args -> staticCall(ctx, cls, member, designator(member, types), args));
		}
		LispVal charset = ClojureCharsetLowering.field(cls, member);
		if (charset != null) {
			return charset;
		}
		LispVal locale = ClojureLocaleLowering.field(cls, member);
		if (locale != null) {
			return locale;
		}
		if (!ctx.hostTarget) {
			// where no host is, DateTimeFormatter/ISO_INSTANT is the portable
			// value data.json's defaults read; the host's own object elsewhere
			LispVal formatter = ClojureDateFormatterLowering.field(cls, member);
			if (formatter != null) {
				return formatter;
			}
			// where no host is, a boxed-integer limit is itself, so malli's
			// +max-size+ def loads; the host's field read elsewhere
			LispVal limit = ClojureNumericLimitsLowering.field(cls, member);
			if (limit != null) {
				return limit;
			}
			// where no host is, a ChronoField constant is its marker, which
			// the builder calls take as an evaluated argument; the host's
			// field elsewhere
			LispVal chrono = ClojureDateBuilderLowering.field(cls, member);
			if (chrono != null) {
				return chrono;
			}
		}
		ClojureLowering.StaticMember seen = staticMember(cls, member);
		if (seen.field()) {
			return fieldCall(ctx, LispString.literal(cls), member, fieldCarriesBytes(cls, member));
		}
		if (!seen.arities().isEmpty()) {
			return arityLambda(ctx, seen.arities(), name, args -> staticCall(ctx, cls, member, args));
		}
		if (seen.variadic()) {
			throw new LispReadException(name + " is variadic and has no value form");
		}
		return fieldCall(ctx, LispString.literal(cls), member, fieldCarriesBytes(cls, member));
	}

	/**
	 * The sorted distinct fixed arities of a class's public instance methods of that
	 * name, each counting the target. Refuses a name with only variadic ones (no
	 * rest-spread reaches {@code java:call}) or with none, or an unloadable class.
	 */
	static List<Integer> instanceArities(String className, String method, String spelling) {
		Set<Integer> arities = new HashSet<>();
		boolean variadic = false;
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		if (found == null) {
			throw new LispReadException(spelling + ": no class " + className);
		}
		try {
			for (ClojureJavaClasses.Member candidate : found.methods()) {
				if (!candidate.name().equals(method) || candidate.isStatic() || candidate.isSynthetic()) {
					continue;
				}
				if (candidate.isVarArgs()) {
					variadic = true;
				}
				else {
					arities.add(candidate.parameterCount() + 1);
				}
			}
		}
		catch (LinkageError | SecurityException _) {
			throw new LispReadException(spelling + ": no class " + className);
		}
		return sortedArities(arities, variadic, spelling,
				"no matches found for instance method " + method + " in class " + className);
	}

	/**
	 * The sorted distinct fixed arities of a class's public constructors, refused like
	 * {@link #instanceArities}.
	 */
	static List<Integer> constructorArities(String className, String spelling) {
		Set<Integer> arities = new HashSet<>();
		boolean variadic = false;
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		if (found == null) {
			throw new LispReadException(spelling + ": no class " + className);
		}
		try {
			for (ClojureJavaClasses.Member candidate : found.constructors()) {
				if (candidate.isVarArgs()) {
					variadic = true;
				}
				else {
					arities.add(candidate.parameterCount());
				}
			}
		}
		catch (LinkageError | SecurityException _) {
			throw new LispReadException(spelling + ": no class " + className);
		}
		return sortedArities(arities, variadic, spelling, "no matches found for constructor in class " + className);
	}

	private static List<Integer> sortedArities(Set<Integer> arities, boolean variadic, String spelling, String none) {
		if (arities.isEmpty()) {
			throw new LispReadException(spelling + (variadic ? " is variadic and has no value form" : ": " + none));
		}
		List<Integer> sorted = new ArrayList<>(arities);
		sorted.sort(Integer::compareTo);
		return sorted;
	}

	/**
	 * {@link #hostConstruction} over already-lowered arguments: the zero-argument
	 * {@code java.io.StringWriter} a string output stream, an untagged one-argument
	 * {@code java.lang.StringBuilder} or {@code java.lang.StringBuffer} a stream the
	 * argument fills, an untagged one-argument {@code java.io.StringWriter} of a capacity
	 * an empty stream, a Date, Timestamp or UUID the value it is here, an untagged plain
	 * throwable an exception, anything else {@code java:new}.
	 */
	static LispVal hostConstructionLowered(ClojureLowering ctx, String cls, List<LispVal> args,
			@Nullable List<String> types) {
		LispVal stringStream = stringStreamConstruction(cls, args.size());
		if (stringStream != null) {
			return stringStream;
		}
		if (!ctx.hostTarget && types == null) {
			LispVal empty = hashMapEmpty(cls, args.size());
			if (empty != null) {
				return empty;
			}
			if (args.isEmpty()) {
				LispVal random = randomConstruction(cls, null);
				if (random != null) {
					return random;
				}
			}
		}
		if (args.size() == 1 && types == null) {
			// untagged only: param tags name the host overload, which keeps java:new
			LispVal builder = stringBuilderConstruction(cls, args.get(0));
			if (builder != null) {
				return builder;
			}
			LispVal writer = stringWriterConstruction(cls, args.get(0));
			if (writer != null) {
				return writer;
			}
			if (!ctx.hostTarget) {
				LispVal hashMap = hashMapCopy(ctx, cls, args.get(0));
				if (hashMap != null) {
					return hashMap;
				}
				LispVal random = randomConstruction(cls, args.get(0));
				if (random != null) {
					return random;
				}
			}
		}
		LispVal timeValue = ClojureTimeValueLowering.construction(cls, args, types);
		if (timeValue != null) {
			return timeValue;
		}
		if (types == null) {
			LispVal exception = throwableConstruction(ctx, cls, args);
			if (exception != null) {
				return exception;
			}
			LispVal own = clojureLangConstruction(cls, args);
			if (own != null) {
				return own;
			}
			LispVal io = ClojureIoLowering.construction(cls, args);
			if (io != null) {
				return io;
			}
		}
		List<LispVal> call = new ArrayList<>();
		call.add(LispString.literal(designator(cls, types)));
		call.addAll(args);
		return newCall(ctx, cls, types, call);
	}

	/**
	 * A bare class name in value position ({@code String}, an imported class, a dotted
	 * name): the class object, the same {@code Class.forName} answer the oracle's class
	 * literal prints and compares by; a number's class ({@code Long}, {@code Double}) the
	 * keyword {@code class} answers for its values. Null when the name is no loadable
	 * class (a typo keeps the unknown-name refusal at compile time) or names a record or
	 * deftype.
	 */
	static @Nullable LispVal classValue(ClojureLowering ctx, String name) {
		if (name.indexOf('/') >= 0 || !ClojureNamespaceLowering.isClasslike(ctx, name) || ctx.typeKeyOf(name) != null) {
			return null;
		}
		String cls = ClojureNamespaceLowering.resolveClass(ctx, name);
		if (!ClojureHostClasses.exists(cls)) {
			return null;
		}
		if (ClojureClassBases.NUMBER_SUPERS.containsKey(cls)) {
			// a number's class is the keyword class answers for its values, on every
			// backend, so (= Long (class 1)) holds like the oracle's
			return ClojureCollectionLowering.keywordForm(cls);
		}
		if (!ctx.hostTarget) {
			// where no host is, a class literal is its (:C%CLASS name)
			// designator, so a class-keyed map (malli's class-schemas, read at
			// load into the default registry) builds -- never a java:static,
			// which wasm refuses. Two literals of one class are equal, like
			// two forName answers; anything needing the object itself
			// (instance? of the value, class?) reads the designator as no
			// host class. The host's object elsewhere.
			return ClojureCoreLowering.classDesignator(cls);
		}
		return ClojureLowerUtil.cons(JAVA_STATIC,
				List.of(LispString.literal("java.lang.Class"), LispString.literal("forName"), LispString.literal(cls)));
	}

	/**
	 * The member-as-value lambda: one {@code &rest} parameter dispatched per known fixed
	 * arity onto the arm {@code call} builds over that many argument forms (for a static
	 * member the run-time overload selection picks among same-arity overloads), any other
	 * count the wrong-argument-count error, like a multi-arity {@code defn} dispatch. The
	 * arms answer booleans {@code T}-or-false, like every predicate value, so
	 * {@code (map Character/isWhitespace ...)} prints {@code (true false)}.
	 */
	static LispVal arityLambda(ClojureLowering ctx, List<Integer> arities, String spelling,
			java.util.function.Function<List<LispVal>, LispVal> call) {
		LispSymbol args = ctx.freshTemp();
		LispSymbol count = ctx.freshTemp();
		List<LispVal> arms = new ArrayList<>();
		for (int arity : arities) {
			List<LispVal> argForms = new ArrayList<>();
			for (int p = 0; p < arity; p++) {
				argForms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("NTH"), new LispInteger(p), args));
			}
			arms.add(ClojureLowerUtil.list(
					ClojureLowerUtil.list(ClojureLowerUtil.sym("="), count, new LispInteger(arity)),
					call.apply(argForms)));
		}
		arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("wrong number of arguments passed to: " + spelling))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(count,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), args)))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms)));
	}

	/** {@code (new Class args...)}: construction through {@code java:new}. */
	static LispVal newOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "new takes a class and arguments");
		if (!(items.get(1) instanceof LispSymbol named)) {
			throw new LispReadException("new takes a class name, not " + items.get(1).print());
		}
		String type = ctx.typeKeyOf(named.name());
		if (type != null) {
			// (new T args...) constructs the record or deftype, like ->T
			return ctx.lower(ClojureLowerUtil.cons(new LispSymbol(constructorSpelling(ctx, type)),
					items.subList(2, items.size())));
		}
		String cls = ClojureNamespaceLowering.resolveClass(ctx, named.name());
		return hostConstruction(ctx, cls, items.subList(2, items.size()), null);
	}

	/**
	 * A zero-argument {@code java.io.StringWriter}, {@code java.lang.StringBuilder} or
	 * {@code java.lang.StringBuffer} construction (dotted or imported, resolved): a fresh
	 * string output stream ({@code %clojure-string-writer}, a stream producer), so every
	 * backend runs it -- wasm included, which never sees the refused {@code java:new}.
	 * Anything else (an initial-capacity or initial-text argument, another class) is
	 * null, so the call keeps its {@code java:new} shape.
	 */
	static @Nullable LispVal stringStreamConstruction(String cls, int argCount) {
		if (argCount == 0 && (cls.equals(STRING_WRITER_CLASS) || cls.equals(STRING_BUILDER_CLASS)
				|| cls.equals(STRING_BUFFER_CLASS))) {
			return ClojureLowerUtil.list(new LispSymbol(STRING_WRITER));
		}
		return null;
	}

	/**
	 * A one-argument {@code java.lang.StringBuilder} or {@code java.lang.StringBuffer}
	 * construction (dotted or imported, resolved) over the already-lowered argument: a
	 * fresh string output stream the argument fills at run time
	 * ({@code %clojure-string-builder-1}, a stream producer), so every backend runs it --
	 * wasm included, which never sees the refused {@code java:new}. Only untagged
	 * constructions reach it (the callers keep a tagged one, whose param tags name the
	 * host overload, on {@code java:new}). Null for any other class, so the call keeps
	 * its {@code java:new} shape.
	 */
	static @Nullable LispVal stringBuilderConstruction(String cls, LispVal arg) {
		if (cls.equals(STRING_BUILDER_CLASS) || cls.equals(STRING_BUFFER_CLASS)) {
			return ClojureLowerUtil.list(new LispSymbol(STRING_BUILDER_1), LispString.literal(cls), arg);
		}
		return null;
	}

	/**
	 * A one-argument {@code java.io.StringWriter} construction (dotted or imported,
	 * resolved) over the already-lowered argument: an empty string output stream when the
	 * argument is a capacity, the oracle's {@code ClassCastException} to {@code Number}
	 * (nil its {@code NullPointerException}) otherwise ({@code %clojure-string-writer-1},
	 * a stream producer), so every backend runs it -- wasm included, which never sees the
	 * refused {@code java:new}. Only untagged constructions reach it (the callers keep a
	 * tagged one, whose param tags name the host overload, on {@code java:new}). A
	 * construction of any other count keeps its {@code java:new} shape. Null for any
	 * other class, so the call keeps its {@code java:new} shape.
	 */
	static @Nullable LispVal stringWriterConstruction(String cls, LispVal arg) {
		if (cls.equals(STRING_WRITER_CLASS)) {
			return ClojureLowerUtil.list(new LispSymbol(STRING_WRITER_1), arg);
		}
		return null;
	}

	/**
	 * A zero-argument {@code java.util.HashMap} construction (dotted or imported,
	 * resolved) where the host is not (wasm): the empty map, the same table a {@code {}}
	 * literal builds ({@link ClojureCollectionLowering#mapBuild}), so every backend runs
	 * it -- where the host is the construction keeps its {@code java:new}, whose mutable
	 * object the host tests pin. The callers try it only off a host target; anything else
	 * (another count, another class) is null, so the call keeps its {@code java:new}
	 * shape.
	 */
	static @Nullable LispVal hashMapEmpty(String cls, int argCount) {
		if (argCount == 0 && HASH_MAP_CLASS.equals(cls)) {
			return ClojureCollectionLowering.mapBuild(List.of());
		}
		return null;
	}

	/**
	 * A zero- or one-argument {@code java.util.Random} construction (dotted or imported,
	 * resolved) where the host is not (wasm): the portable 48-bit LCG generator
	 * ({@link #RANDOM_NEW} over the lowered seed, or over {@link #RANDOM_ENTROPY} with no
	 * argument), so data.generators' {@code *rnd*} root loads -- never a
	 * {@code java:new}, which wasm refuses. A tagged construction names the host overload
	 * and keeps {@code java:new}, as does any other count; where the host is the
	 * construction keeps its {@code java:new}, whose host object the host tests pin. The
	 * callers try it only untagged and off a host target. Null for any other class, so
	 * the call keeps its {@code java:new} shape.
	 * @param cls the resolved class name
	 * @param arg the already-lowered seed, or null for the zero-argument construction
	 * @return the portable generator, or null
	 */
	static @Nullable LispVal randomConstruction(String cls, @Nullable LispVal arg) {
		if (!RANDOM_CLASS.equals(cls)) {
			return null;
		}
		LispVal seed = arg != null ? arg : ClojureLowerUtil.list(new LispSymbol(RANDOM_ENTROPY));
		return ClojureLowerUtil.list(new LispSymbol(RANDOM_NEW), seed);
	}

	/**
	 * A zero-argument {@code java.lang.Object} construction (dotted or imported,
	 * resolved) where the host is not (wasm): a fresh uninterned symbol, so every backend
	 * runs it -- dynaload's {@code dynalock} builds one at load, and {@code locking2}
	 * only needs the lock to evaluate (on wasm {@code monitor-enter}/{@code monitor-exit}
	 * answer nil over the evaluated object). A fresh symbol per evaluation keeps the
	 * oracle's identity: two constructions are never {@code =} or {@code identical?},
	 * like two host objects. Where the host is the construction keeps its
	 * {@code java:new}, whose object the host tests pin. The callers try it only off a
	 * host target; anything else (another count, another class) is null, so the call
	 * keeps its {@code java:new} shape.
	 */
	static @Nullable LispVal objectToken(String cls, int argCount) {
		if (argCount == 0 && "java.lang.Object".equals(cls)) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("gensym"));
		}
		return null;
	}

	/**
	 * A one-argument {@code java.util.HashMap} construction (dotted or imported,
	 * resolved) over the already-lowered argument where the host is not (wasm): the map
	 * itself, which the argument evaluates to once -- reitit's {@code (HashMap. ^Map m)}
	 * names a map, and a map here is immutable, so the oracle's copy shares its entries
	 * either way. Nil is the oracle's {@code NullPointerException} before anything else
	 * runs; a non-map argument (a capacity, which the oracle builds an empty mutable map
	 * for) answers itself, a documented deviation no portable program reaches.
	 * {@code .put} on the value refuses as the oracle's
	 * {@code UnsupportedOperationException} ({@code .kb/clojure-frontend.md}, "HashMap
	 * construction"). The callers try it only untagged and off a host target (a tagged
	 * construction names the host overload and keeps {@code java:new}). Null for any
	 * other class, so the call keeps its {@code java:new} shape.
	 */
	static @Nullable LispVal hashMapCopy(ClojureLowering ctx, String cls, LispVal arg) {
		if (!HASH_MAP_CLASS.equals(cls)) {
			return null;
		}
		LispSymbol temp = ctx.freshTemp();
		LispVal nullCheck = ClojureLowerUtil.list(ClojureLowerUtil.sym("IF"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("NULL"), temp),
				ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER,
						LispString.literal("NullPointerException: java.util.HashMap of nil")));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("LET"),
				ClojureLowerUtil.list(ClojureLowerUtil.list(temp, arg)), nullCheck, temp);
	}

	/**
	 * A two-argument {@code java.util.HashMap} construction (dotted or imported,
	 * resolved) of two number datums where the host is not (wasm): the empty map, the
	 * same table a {@code {}} literal builds
	 * ({@link ClojureCollectionLowering#mapBuild}), like the empty mutable map the oracle
	 * builds for a capacity and a load factor -- malli's {@code fast-registry} builds
	 * {@code (HashMap. 1024 0.25)} at load, then fills it with {@code .putAll}. The
	 * datums are number literals, so dropping them evaluates nothing away; any other pair
	 * (and a tagged construction, which names the host overload) keeps its
	 * {@code java:new}. Where the host is the construction keeps its {@code java:new},
	 * whose mutable object the host tests pin. Null for any other class, so the call
	 * keeps its {@code java:new} shape.
	 */
	static @Nullable LispVal hashMapCapacity(String cls, List<LispVal> argDatums) {
		if (!HASH_MAP_CLASS.equals(cls)) {
			return null;
		}
		for (LispVal datum : argDatums) {
			if (!(datum instanceof LispInteger || datum instanceof LispBigInteger || datum instanceof LispDouble
					|| datum instanceof LispRatio)) {
				return null;
			}
		}
		return ClojureCollectionLowering.mapBuild(List.of());
	}

	/**
	 * The host reader classes a construction of which over a Common Lisp character input
	 * stream answers the stream itself: such a stream already peeks one character, which
	 * is all {@code read} needs, so {@code (java.io.PushbackReader. (reader path))} reads
	 * on every backend.
	 */
	static final Set<String> READER_WRAPPERS = Set.of("java.io.PushbackReader", "java.io.BufferedReader");

	/** The reader decoding a byte stream: {@code (InputStreamReader. in [charset])}. */
	private static final String INPUT_STREAM_READER = "java.io.InputStreamReader";

	/**
	 * A {@code java.io.PushbackReader}/{@code java.io.BufferedReader} construction (an
	 * optional buffer size behind the reader): over a {@code java.io.StringReader}
	 * construction a string input stream of its text, on every backend; over a Common
	 * Lisp stream (a {@code clojure.java.io/reader}, {@code *in*}, a nested wrapper) that
	 * stream -- decided at run time unless the argument lowers to one; over anything else
	 * the host construction, like before. A {@code java.io.InputStreamReader}
	 * construction over a byte stream {@code clojure.java.io} made (a Ring request
	 * {@code :body} among them) decodes it in the charset given; over anything else it is
	 * the host construction. Null for any other class or count, so the call keeps its
	 * {@code java:new} shape.
	 * @param ctx the hub
	 * @param cls the resolved class name
	 * @param args the argument datums
	 * @return the lowered construction, or null
	 */
	static @Nullable LispVal readerWrapperConstruction(ClojureLowering ctx, String cls, List<LispVal> args) {
		if (cls.equals(INPUT_STREAM_READER) && !args.isEmpty() && args.size() <= 2) {
			return inputStreamReaderConstruction(ctx, args);
		}
		if (!READER_WRAPPERS.contains(cls) || args.isEmpty() || args.size() > 2) {
			return null;
		}
		LispVal text = stringReaderText(ctx, args.get(0));
		LispVal reader = text != null ? ClojureLowerUtil.list(new LispSymbol(STRING_READER), ctx.lower(text))
				: ctx.lower(args.get(0));
		if (args.size() == 1 && isStreamForm(reader)) {
			return reader;
		}
		LispSymbol source = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(source, reader));
		List<LispVal> host = new ArrayList<>();
		host.add(LispString.literal(cls));
		host.add(source);
		if (args.size() == 2) {
			LispSymbol size = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(size, ctx.lower(args.get(1))));
			host.add(size);
		}
		LispVal body = isStreamForm(reader) ? source
				: ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("streamp"), source), source,
						ClojureLowerUtil.cons(JAVA_NEW, host));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), body);
	}

	/**
	 * A {@code java.io.PrintWriter} construction (an autoflush flag behind the writer):
	 * over a stream the stream itself, which {@code .append} writes and {@code .flush}
	 * flushes, like the oracle's writer over one; over anything else the host
	 * construction. The flag is evaluated for its effects and dropped: a stream here
	 * flushes at the end either way. Decided at run time unless the argument lowers to
	 * one of the output producers, like the reader wrappers'.
	 */
	static @Nullable LispVal printWriterConstruction(ClojureLowering ctx, String cls, List<LispVal> args) {
		if (!cls.equals("java.io.PrintWriter") || args.isEmpty() || args.size() > 2) {
			return null;
		}
		LispVal writer = ctx.lower(args.get(0));
		LispSymbol source = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(source, writer));
		List<LispVal> host = new ArrayList<>();
		host.add(LispString.literal(cls));
		host.add(source);
		if (args.size() == 2) {
			LispSymbol flush = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(flush, ctx.lower(args.get(1))));
			host.add(flush);
		}
		LispVal body = isOutputStreamForm(writer) ? source
				: ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("streamp"), source), source,
						ClojureLowerUtil.cons(JAVA_NEW, host));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), body);
	}

	/**
	 * Whether a lowered form surely answers a Common Lisp character output stream: a read
	 * of {@code *out*} or a {@code java.io.StringWriter} construction, or a binding form
	 * ending in one.
	 */
	static boolean isOutputStreamForm(LispVal lowered) {
		List<LispVal> items = ClojureLowerUtil.items(lowered);
		if (items == null || items.isEmpty()) {
			return false;
		}
		if (ClojureLowerUtil.isSymbolNamed(items.get(0), "RONTOLISP::%CLOJURE-OUT")
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), STRING_WRITER)) {
			return true;
		}
		return items.size() == 3 && ClojureLowerUtil.isSymbolNamed(items.get(0), "LET*")
				&& isOutputStreamForm(items.get(2));
	}

	/**
	 * {@code (InputStreamReader. in [charset])}: a byte stream {@code clojure.java.io}
	 * made decoded in the charset given (an arm a program making none sheds), anything
	 * else the host construction.
	 */
	private static LispVal inputStreamReaderConstruction(ClojureLowering ctx, List<LispVal> args) {
		LispSymbol source = ctx.freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(source, ctx.lower(args.get(0))));
		List<LispVal> host = new ArrayList<>();
		host.add(LispString.literal(INPUT_STREAM_READER));
		host.add(source);
		LispVal charset = ClojureLowering.NIL_CONST;
		if (args.size() == 2) {
			LispSymbol named = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(named, ctx.lower(args.get(1))));
			host.add(named);
			charset = named;
		}
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(ClojureIoLowering.IO_P), source),
				ClojureLowerUtil.list(new LispSymbol(ClojureIoLowering.DECODING_READER), source, charset),
				ClojureLowerUtil.cons(JAVA_NEW, host));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), body);
	}

	/**
	 * Whether a lowered form surely answers a Common Lisp character input stream: a
	 * {@code clojure.java.io/reader}, a string input stream over a
	 * {@code java.io.StringReader}, a read of {@code *in*}, or a binding form ending in
	 * one (a nested reader wrapper).
	 */
	static boolean isStreamForm(LispVal lowered) {
		List<LispVal> items = ClojureLowerUtil.items(lowered);
		if (items == null || items.isEmpty()) {
			return false;
		}
		if (ClojureLowerUtil.isSymbolNamed(items.get(0), ClojureNamespaceLowering.READER)
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), STRING_READER)
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), STANDARD_INPUT_READ)
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), JIO_READER)) {
			return true;
		}
		return items.size() == 3 && ClojureLowerUtil.isSymbolNamed(items.get(0), "LET*") && isStreamForm(items.get(2));
	}

	/**
	 * The text datum of a one-argument {@code java.io.StringReader} construction
	 * ({@code (StringReader. text)} dotted or imported, {@code (StringReader/new text)}
	 * or {@code (new StringReader text)}), or null for anything else.
	 */
	static @Nullable LispVal stringReaderText(ClojureLowering ctx, LispVal datum) {
		List<LispVal> items = ClojureLowerUtil.items(ClojureLowerUtil.stripMeta(datum));
		if (items == null || items.size() < 2 || !(items.get(0) instanceof LispSymbol head)) {
			return null;
		}
		String spelled;
		if (head.name().equals("new") && items.size() == 3 && items.get(1) instanceof LispSymbol named) {
			spelled = named.name();
		}
		else if (head.name().endsWith(".") && head.name().length() > 1 && items.size() == 2) {
			spelled = head.name().substring(0, head.name().length() - 1);
		}
		else if (head.name().endsWith("/new") && head.name().length() > 4 && items.size() == 2) {
			spelled = head.name().substring(0, head.name().length() - 4);
		}
		else {
			return null;
		}
		if (!isClassSpelling(spelled) || ctx.typeKeyOf(spelled) != null
				|| !ClojureNamespaceLowering.resolveClass(ctx, spelled).equals("java.io.StringReader")) {
			return null;
		}
		return items.get(items.size() - 1);
	}

	/**
	 * How the current namespace names a record or deftype's positional constructor:
	 * {@code ->T} for its own, {@code ns/->T} for another namespace's.
	 */
	static String constructorSpelling(ClojureLowering ctx, String typeKey) {
		int slash = typeKey.indexOf('/');
		String ns = typeKey.substring(0, slash);
		String ctor = "->" + typeKey.substring(slash + 1);
		return ns.equals(ctx.currentNs) ? ctor : ns + "/" + ctor;
	}

	/**
	 * {@code (make-array Class dim...)}: a general array over the dimensions -- the class
	 * spells the element type and is ignored, every array here is general (the book's
	 * {@code interop.clj} {@code painstakingly-create-array} shape) but one dimension of
	 * {@code Byte/TYPE}, a byte array ({@link ClojureBytesLowering}). One dimension is
	 * the scalar, several the dimension list, like the oracle's separate-argument shape;
	 * only the Clojure spellings are new, the array itself compiles on all four backends.
	 * {@code aget}, {@code aset} and {@code alength} are {@link ClojureBytesLowering}'s.
	 */
	static LispVal makeArrayOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "make-array takes a class and dimensions");
		if (!(items.get(1) instanceof LispSymbol cls)) {
			throw new LispReadException("make-array takes a class name, not " + items.get(1).print());
		}
		List<LispVal> dims = ctx.lowers(items, 2);
		if (dims.size() == 1 && ClojureBytesLowering.namesByteType(ctx, cls.name())) {
			return ClojureBytesLowering.byteArrayOfSize(dims.get(0));
		}
		LispVal shape = dims.size() == 1 ? dims.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), dims);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("make-array"), shape);
	}

	/**
	 * The host class name a host class object answers, or nil: what tells the byte class
	 * ({@code byte}) from any other where a host is, a nil-body arm where none is
	 * ({@code clojure.lisp}).
	 */
	static final String HOST_CLASS_NAME = "RONTOLISP::%CLOJURE-HOST-CLASS-NAME";

	/**
	 * {@code make-array} as a value: over a class and dimensions -- of one dimension the
	 * scalar, of several the dimension list, like the call. Of one dimension of the byte
	 * class, a byte array, like the call: the class arrives evaluated, and only a host
	 * class object still names the primitive byte (its host class name), so anywhere else
	 * -- a designator, which names the boxed class -- the general array answers, like the
	 * call of a boxed class. Anything shorter signals, like a call's arity refusal.
	 * @param ctx the hub
	 * @return the value form
	 */
	static LispVal makeArrayValue(ClojureLowering ctx) {
		LispSymbol cls = new LispSymbol(ClojureLowering.mangle("make-array-class"));
		LispSymbol dims = new LispSymbol(ClojureLowering.mangle("make-array-dims"));
		LispVal arity = ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("make-array takes a class and dimensions"));
		LispVal hostByte = ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"),
				ClojureLowerUtil.list(new LispSymbol(HOST_CLASS_NAME), cls), LispString.literal("byte"));
		LispVal single = ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), dims));
		LispVal bytes = ClojureLowerUtil.list(new LispSymbol(ClojureBytesLowering.BYTE_ARRAY),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), dims));
		LispVal shape = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), single,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), dims), dims);
		LispVal built = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), single, hostByte), bytes,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("make-array"), shape));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(cls, ClojureLowering.AMPERSAND_REST, dims)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), dims), arity, built));
	}

	/**
	 * {@code (.-field target)}: a record or deftype answers its field table's entry (a
	 * missing or mutable field signals in the oracle's words); anything else takes the
	 * host field path, like before -- where the host is not (wasm), the oracle's refusal,
	 * since no value there has a public instance field.
	 */
	static LispVal fieldRead(ClojureLowering ctx, LispVal target, String field) {
		LispSymbol one = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispVal table = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isReifyForm(one),
				ClojureLowering.NIL_CONST, ClojureProtocolLowering.typedTableOf(one));
		LispVal read = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(got,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
								ClojureCollectionLowering.keywordForm(field), table, miss)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), got, miss),
						ClojureValueMethodLowering.refusal(one, field, true, List.of()), got));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, target),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureProtocolLowering.isTypedForm(one), read,
						ctx.hostTarget ? fieldCall(ctx, one, field, true)
								: ClojureValueMethodLowering.refusal(one, field, true, List.of())));
	}

	/**
	 * {@code (memfn name arg...)}: a function of a target and the named arguments calling
	 * the method on it -- the oracle's expansion, so {@code ((memfn toUpperCase) "hi")}
	 * answers {@code "HI"}. String receivers take the mapped core operation, like any
	 * other instance call.
	 */
	static LispVal memfnOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "memfn takes a method name and argument names");
		String method = ClojureLowerUtil.plainName(items.get(1), "memfn");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		Set<String> seen = new HashSet<>();
		List<LispVal> params = new ArrayList<>();
		LispSymbol recv = ctx.freshTemp();
		params.add(recv);
		List<LispVal> argForms = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			String pname = ClojureLowerUtil.plainName(items.get(i), "memfn");
			ClojureLowerUtil.isTrue(seen.add(pname), "memfn argument names must be distinct: " + pname);
			scope.put(pname, ClojureLowering.Kind.VARIABLE);
			params.add(ctx.localSym(pname));
			argForms.add(ctx.localSym(pname));
		}
		return ctx.inScope(scope, () -> ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(params), instanceCallLowered(ctx, recv, method, argForms)));
	}

	/**
	 * {@code (proxy [interface...] [] (method [params...] body...)...)}: one object
	 * implementing every interface through {@code java:proxy} with a name-dispatching
	 * lambda, so a method name several interfaces declare runs the one body. A
	 * superclass, constructor arguments, an {@code Object} method
	 * ({@code toString}/{@code equals}/{@code hashCode}) and multi-arity methods are
	 * refused by name; the methods take the Java arguments only (no {@code this}, which
	 * has no binding to close over). Interpreter and JVM only, like all interop.
	 */
	static LispVal proxyOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "proxy takes a class vector, an argument vector and methods");
		List<LispVal> classes = ClojureLowerUtil.items(items.get(1));
		if (classes == null || classes.isEmpty() || classes.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("proxy takes a class vector, not " + items.get(1).print());
		}
		if (classes.size() < 2) {
			throw new LispReadException("proxy takes at least one interface");
		}
		List<LispVal> javaProxy = new ArrayList<>();
		for (LispVal className : classes.subList(1, classes.size())) {
			if (!(className instanceof LispSymbol symbol)) {
				throw new LispReadException("proxy takes interface names, not " + className.print());
			}
			String iface = ClojureNamespaceLowering.resolveClass(ctx, symbol.name());
			if (isHostClass(iface)) {
				if (className == classes.get(1)) {
					return proxyClassOf(ctx, items, classes, iface);
				}
				throw new LispReadException(
						"proxy takes a single superclass and interfaces: " + iface + " is a class, not an interface");
			}
			javaProxy.add(LispString.literal(iface));
		}
		List<LispVal> argv = ClojureLowerUtil.items(items.get(2));
		if (argv == null || argv.size() != 1) {
			throw new LispReadException("proxy constructor arguments are not supported yet: " + items.get(2).print());
		}
		LispSymbol all = ctx.freshTemp();
		LispSymbol got = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispVal miss = ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"), ClojureLowerUtil.quoted("string"),
						LispString.literal("no proxy method: "), got));
		LispVal dispatch = miss;
		for (int i = items.size() - 1; i >= 3; i--) {
			List<LispVal> meth = ClojureLowerUtil.items(items.get(i));
			if (meth == null || meth.size() < 2 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			List<LispVal> params = ClojureLowerUtil.items(meth.get(1));
			if (params == null || params.isEmpty() || params.get(0) != ClojureReader.VECTOR) {
				throw new LispReadException("a proxy method takes a parameter vector, not " + meth.get(1).print());
			}
			if (isObjectMethod(methodName, params.size() - 1)) {
				// java:proxy keeps Object's three: the body would never run.
				throw new LispReadException("proxy cannot override " + methodName
						+ " yet: a proxy keeps Object's equals, hashCode and toString");
			}
			Map<String, ClojureLowering.Kind> scope = new HashMap<>();
			Set<String> seen = new HashSet<>();
			List<LispVal> fnParams = new ArrayList<>();
			for (int j = 1; j < params.size(); j++) {
				String pname = ClojureLowerUtil.plainName(params.get(j), "proxy");
				ClojureLowerUtil.isTrue(seen.add(pname), "proxy parameter names must be distinct: " + pname);
				scope.put(pname, ClojureLowering.Kind.VARIABLE);
				fnParams.add(ctx.localSym(pname));
			}
			Map<String, ClojureLowering.Kind> use = new HashMap<>(scope);
			LispVal run = ctx
				.inScope(use,
						() -> ClojureLowerUtil.list(
								ClojureLowerUtil.sym("apply"), ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
										ClojureLowerUtil.list(fnParams), ctx.bodyOf(meth.subList(2, meth.size()))),
								rest));
			LispVal test = ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"), got, LispString.literal(methodName));
			dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), test, run, dispatch);
		}
		LispVal callable = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, all)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(List.of(
							ClojureLowerUtil.list(got, ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), all)),
							ClojureLowerUtil.list(rest, ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), all)))),
							dispatch));
		javaProxy.add(callable);
		// the body is handed Java's false as the false object
		javaProxy.add(JAVA_FALSE_MARKER);
		proxyThrough(ctx, javaProxy, PROXY_CALL);
		return ClojureLowerUtil.cons(JAVA_PROXY, javaProxy);
	}

	/**
	 * Ends a {@code java:proxy} / {@code java:subclass} where the host is so that Java
	 * calls the body through {@link #PROXY_CALL} / {@link #SUBCLASS_CALL}, as the
	 * oracle's proxy reads Java's arguments and answers it: each argument a Clojure value
	 * -- a {@code byte[]}, which the form's {@code :octets} hands it as an octet vector,
	 * a byte array -- and an answer where the method answers an object the value's own
	 * object (a byte array the {@code byte[]}, a vector the oracle's {@code List}, a map
	 * its {@code Map}, a keyword one Java hands back as the keyword), where it answers a
	 * primitive unboxed as the oracle's proxy class unboxes it, and where it answers
	 * nothing as it is, so a void one drops a lazy seq unrealized.
	 */
	private static void proxyThrough(ClojureLowering ctx, List<LispVal> form, String call) {
		if (ctx.hostTarget) {
			form.add(OCTETS_MARKER);
			form.addAll(through(call));
		}
	}

	// Object's equals(Object), hashCode() and toString(), which java:proxy never routes
	// to
	// the callable.
	private static boolean isObjectMethod(String name, int arity) {
		return switch (name) {
			case "toString", "hashCode" -> arity == 0;
			case "equals" -> arity == 1;
			default -> false;
		};
	}

	// Whether the name loads, here, as a final class: a proxy superclass must be
	// extensible. A name that does not load is left to the run-time error.
	private static boolean isFinalClass(String className) {
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		return found != null && !found.isInterface() && found.isFinal();
	}

	// Whether the name loads, here, as a class that is not an interface: a proxy
	// superclass. A name that does not load is left to the run-time error.
	private static boolean isHostClass(String className) {
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		return found != null && !found.isInterface();
	}

	/**
	 * {@code (proxy [superclass interface...] [args...] (method [params...] body...)...)}:
	 * one object extending the superclass through {@code java:subclass} with a
	 * name-dispatching lambda taking the object first, so a name several interfaces
	 * declare runs the one body, as the oracle's proxy does. Each body sees {@code this}
	 * (the proxy object) and calls the superclass implementation through
	 * {@code (proxy-super method args...)}. A method left out is inherited when the class
	 * chain implements it, else refused with the method's name when it is called;
	 * {@code toString}/{@code equals}/{@code hashCode} run their bodies, like the oracle.
	 * Interpreter and JVM only, like all interop.
	 */
	static LispVal proxyClassOf(ClojureLowering ctx, List<LispVal> items, List<LispVal> classes, String superclass) {
		if (isFinalClass(superclass)) {
			throw new LispReadException("proxy cannot extend final class " + superclass);
		}
		if (!ctx.hostTarget && superclass.equals("java.lang.ThreadLocal")) {
			// where no host is, a ThreadLocal proxy is the portable
			// single-threaded cell, so the namespace defining it loads;
			// constructor arguments stay the host's java:subclass
			LispVal cell = ClojurePortableThreadLocalLowering.cellOf(ctx, items);
			if (cell != null) {
				return cell;
			}
		}
		List<LispVal> javaSubclass = new ArrayList<>();
		javaSubclass.add(LispString.literal(superclass));
		List<LispVal> ifaceNames = new ArrayList<>();
		for (LispVal className : classes.subList(2, classes.size())) {
			if (!(className instanceof LispSymbol symbol)) {
				throw new LispReadException("proxy takes interface names, not " + className.print());
			}
			String iface = ClojureNamespaceLowering.resolveClass(ctx, symbol.name());
			if (isHostClass(iface)) {
				throw new LispReadException(
						"proxy takes a single superclass and interfaces: " + iface + " is a class, not an interface");
			}
			ifaceNames.add(LispString.literal(iface));
		}
		javaSubclass.add(quotedList(ifaceNames));
		List<LispVal> argv = ClojureLowerUtil.items(items.get(2));
		if (argv == null || argv.isEmpty() || argv.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException("proxy takes an argument vector, not " + items.get(2).print());
		}
		List<LispVal> ctorArgs = new ArrayList<>();
		for (LispVal arg : argv.subList(1, argv.size())) {
			ctorArgs.add(hostArgument(ctx, ctx.lower(arg)));
		}
		List<String> methodNames = new ArrayList<>();
		for (int i = 3; i < items.size(); i++) {
			List<LispVal> meth = ClojureLowerUtil.items(items.get(i));
			if (meth == null || meth.size() < 2 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			if (methodNames.contains(methodName)) {
				throw new LispReadException("proxy defines method " + methodName + " twice");
			}
			methodNames.add(methodName);
		}
		LispSymbol thisSym = ctx.localSym("this");
		LispSymbol got = ctx.freshTemp();
		LispSymbol rest = ctx.freshTemp();
		LispVal miss = ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"), ClojureLowerUtil.quoted("string"),
						LispString.literal("no proxy method: "), got));
		LispVal dispatch = miss;
		for (int i = items.size() - 1; i >= 3; i--) {
			List<LispVal> meth = ClojureLowerUtil.items(items.get(i));
			if (meth == null || meth.size() < 2 || !(meth.get(0) instanceof LispSymbol)) {
				throw new LispReadException("a proxy method names a method, a parameter vector and a body");
			}
			String methodName = ((LispSymbol) meth.get(0)).name();
			List<LispVal> params = ClojureLowerUtil.items(meth.get(1));
			if (params == null || params.isEmpty() || params.get(0) != ClojureReader.VECTOR) {
				throw new LispReadException("a proxy method takes a parameter vector, not " + meth.get(1).print());
			}
			Map<String, ClojureLowering.Kind> scope = new HashMap<>();
			Set<String> seen = new HashSet<>();
			scope.put("this", ClojureLowering.Kind.VARIABLE);
			List<LispVal> fnParams = new ArrayList<>();
			fnParams.add(thisSym);
			for (int j = 1; j < params.size(); j++) {
				String pname = ClojureLowerUtil.plainName(params.get(j), "proxy");
				ClojureLowerUtil.isTrue(seen.add(pname), "proxy parameter names must be distinct: " + pname);
				scope.put(pname, ClojureLowering.Kind.VARIABLE);
				fnParams.add(ctx.localSym(pname));
			}
			Map<String, ClojureLowering.Kind> use = new HashMap<>(scope);
			ctx.proxyMethods.push(new ClojureLowering.ProxyMethod(thisSym));
			LispVal run;
			try {
				run = ctx.inScope(use,
						() -> ClojureLowerUtil.list(
								ClojureLowerUtil.sym("apply"), ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
										ClojureLowerUtil.list(fnParams), ctx.bodyOf(meth.subList(2, meth.size()))),
								thisSym, rest));
			}
			finally {
				ctx.proxyMethods.pop();
			}
			LispVal test = ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"), got, LispString.literal(methodName));
			dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), test, run, dispatch);
		}
		List<LispVal> methodLiterals = new ArrayList<>();
		for (String name : methodNames) {
			methodLiterals.add(LispString.literal(name));
		}
		javaSubclass.add(quotedList(methodLiterals));
		javaSubclass.addAll(ctorArgs);
		LispVal callable = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(thisSym, got, ClojureLowering.AMPERSAND_REST, rest)), dispatch);
		javaSubclass.add(callable);
		if (!allLiteral(ctorArgs)) {
			// a fn constructor argument implements its interface by its arguments
			javaSubclass.add(new LispSymbol(LispNames.JAVA_FUNCTIONAL_MARKER));
		}
		// the body, and a fn constructor argument, is handed Java's false as the false
		// object
		javaSubclass.add(JAVA_FALSE_MARKER);
		proxyThrough(ctx, javaSubclass, SUBCLASS_CALL);
		return ClojureLowerUtil.cons(JAVA_SUBCLASS, javaSubclass);
	}

	// A quoted list of literals, as java:subclass takes its interface and method names.
	private static LispVal quotedList(List<LispVal> names) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(names));
	}

	/**
	 * {@code (proxy-super method args...)} inside a proxy method body: the superclass
	 * implementation on {@code this}, through the generated {@code super} accessor of the
	 * method's arity. Outside a proxy method body it is refused by name.
	 */
	static LispVal proxySuperOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowering.ProxyMethod method = ctx.proxyMethods.peekLast();
		if (method == null) {
			throw new LispReadException("proxy-super outside a proxy method");
		}
		ClojureLowerUtil.isTrue(items.size() >= 2, "proxy-super takes a method and arguments");
		if (!(items.get(1) instanceof LispSymbol target)) {
			throw new LispReadException("proxy-super takes a method name, not " + items.get(1).print());
		}
		List<LispVal> args = new ArrayList<>();
		args.add(method.self);
		// Mirrors compiler/JavaImplementations.superAccessor: one accessor per (name,
		// arity), so the lowering needs no types to spell it.
		args.add(LispString.literal("super$" + target.name() + "$" + (items.size() - 2)));
		for (LispVal arg : items.subList(2, items.size())) {
			args.add(ctx.lower(arg));
		}
		return hostCall(ctx, JAVA_CALL, args, 2, true);
	}

	/**
	 * An instance call: the receiver runs once, behind a temporary; a string receiver
	 * answers the mapped core operation, which runs on every backend, and so does a
	 * collection, keyword, symbol, ratio or atom ({@link ClojureValueMethodLowering});
	 * anything else goes to {@code java:call} directly, which calls a string, number or
	 * character as its {@code String}, box or {@code Character}.
	 */
	static LispVal instanceCall(ClojureLowering ctx, LispVal receiver, String method, List<LispVal> argDatums) {
		List<LispVal> args = new ArrayList<>();
		for (LispVal arg : argDatums) {
			args.add(ctx.lower(arg));
		}
		return instanceCallLoweredWithClass(ctx, receiver, null, method, hintedDesignator(ctx, method, argDatums),
				args);
	}

	/**
	 * An instance call over already-lowered forms: the receiver runs once, behind a
	 * temporary; a string receiver answers the mapped core operation, anything else goes
	 * to {@code java:call} directly, whose host {@code false} is the false object
	 * ({@link #hostCall}). The receiver's class, when known -- a construction literal, a
	 * {@code let}/{@code if-let}/{@code when-let} local bound to one, or a {@code ..}
	 * step's declared return -- decides the string and stream rules.
	 */
	static LispVal instanceCallLowered(ClojureLowering ctx, LispVal receiver, String method, List<LispVal> args) {
		return instanceCallLoweredWithClass(ctx, receiver, null, method, args);
	}

	/**
	 * An instance call with an explicit known receiver class (a {@code ..} step's
	 * declared return): null to read it off the receiver instead, like
	 * {@link #instanceCallLowered}.
	 */
	static LispVal instanceCallLoweredWithClass(ClojureLowering ctx, LispVal receiver, @Nullable String knownClass,
			String method, List<LispVal> args) {
		return instanceCallLoweredWithClass(ctx, receiver, knownClass, method, method, args);
	}

	/**
	 * {@link #instanceCallLoweredWithClass(ClojureLowering, LispVal, String, String, List)}
	 * with the {@code java:call} under a designator ({@link #designator}); the string,
	 * stream and boolean rules still read the bare method.
	 */
	static LispVal instanceCallLoweredWithClass(ClojureLowering ctx, LispVal receiver, @Nullable String knownClass,
			String method, String designator, List<LispVal> args) {
		LispSymbol recv = ctx.freshTemp();
		List<LispVal> direct = new ArrayList<>();
		direct.add(recv);
		direct.add(LispString.literal(designator));
		direct.addAll(args);
		String cls = knownClass;
		if (cls == null) {
			cls = constructedClass(receiver);
			if (cls == null && receiver instanceof LispSymbol ref) {
				cls = hostClassOf(ctx, ref);
			}
		}
		LispVal call = hostCall(ctx, JAVA_CALL, direct, 2, answersBytes(cls, designator, args.size(), false));
		if (cls != null && ClojureTimeValueLowering.CLASSES.contains(cls)) {
			// a Date, Timestamp or UUID may be a value made here: the value arms take it
			cls = null;
		}
		if (args.isEmpty() && EXCEPTION_METHODS.contains(method) && (cls == null || plainThrowable(cls) != null)) {
			// a caught runtime error, an ex-info and a throwable construction are
			// conditions: the library answers from the exception, and calls the host
			// method on anything else
			ctx.usedExInfo = true;
			return ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.EXCEPTION_METHOD), receiver,
					LispString.literal(method));
		}
		if (args.isEmpty() && STACK_TRACE_METHODS.containsKey(method) && (cls == null || plainThrowable(cls) != null)) {
			// a condition answers its toString line and no frames; anything else calls
			// the host method
			return ClojureLowerUtil.list(new LispSymbol(STACK_TRACE_METHODS.get(method)), receiver);
		}
		if (method.equals("toString") && args.isEmpty()) {
			call = valueToString(ctx, recv);
		}
		boolean hashCode = method.equals("hashCode") && args.isEmpty() && cls == null;
		if (hashCode) {
			call = valueHashCode(recv);
		}
		if (cls == null && !ctx.hostTarget && args.isEmpty()
				&& (method.equals("isInfinite") || method.equals("isNaN"))) {
			// where no host is, .isInfinite/.isNaN of a double answer what
			// infinite?/NaN? answer (data.json's float writers); anything else
			// keeps the host call
			call = ClojurePortableStaticsLowering.doublePredicateArm(ctx, method, recv, call);
		}
		LispVal stream = streamMethod(ctx, method, recv, args);
		boolean typed = cls != null || receiver instanceof LispString;
		// where the host is not (wasm) the string arm is known ahead of the host arm,
		// which no string then reaches
		LispVal mapped = ctx.hostTarget ? null : stringMethod(ctx, method, recv, args, typed);
		if (cls == null && !ctx.hostTarget && !(method.equals("toString") && args.isEmpty()) && !hashCode) {
			call = hostlessCall(recv, method, args, call, mapped != null, stream != null);
		}
		if (stream != null) {
			call = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("streamp"), recv), stream, call);
		}
		if (cls == null && !(method.equals("toString") && args.isEmpty()) && !hashCode) {
			// a collection, keyword, symbol or ratio has no host object: its common
			// methods answer through the core verbs, any other is refused by name
			call = ClojureValueMethodLowering.valueArm(ctx, method, designator, recv, args, call);
		}
		if ((cls == null || RANDOM_CLASS.equals(cls)) && !ctx.hostTarget && RANDOM_METHODS.containsKey(method)
				&& RANDOM_METHODS.get(method).contains(args.size())) {
			// where no host is, a portable generator answers the draw a
			// java.util.Random of that seed answers; anything else keeps what the
			// call lowered to (the host call, or the oracle's refusal where no
			// hostless class has the method)
			call = randomArm(method, recv, args, call);
		}
		if (!ctx.hostTarget && ClojurePortableThreadLocalLowering.THREAD_LOCAL_METHODS.containsKey(method)
				&& ClojurePortableThreadLocalLowering.THREAD_LOCAL_METHODS.get(method).contains(args.size())) {
			// where no host is, a portable ThreadLocal cell answers its member;
			// anything else keeps what the call lowered to (the host call, or
			// the oracle's refusal where no hostless class has the method)
			call = ClojurePortableThreadLocalLowering.arm(method, recv, args, call);
		}
		if (method.equals("getClass") && args.isEmpty()) {
			call = ClojureDispatchLowering.getClassForm(ctx, recv, cls, call);
		}
		if (cls == null || ClojureIoLowering.CLASSES.containsKey(cls)) {
			// a File, a URL, a URI, a byte stream or a stream clojure.java.io made
			// answers its own methods: an arm a program making none sheds
			call = ClojureIoLowering.methodArm(ctx, method, designator, recv, args, call);
		}
		if (ctx.hostTarget) {
			mapped = stringMethod(ctx, method, recv, args, typed);
		}
		LispVal out = mapped == null ? call : ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), recv), mapped, call);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(recv, receiver))), out);
	}

	/**
	 * An instance call of a portable {@code java.util.Random} member where the host is
	 * not (wasm): a portable generator answers its worker, anything else what the call
	 * lowered to. The caller checks the method and the count.
	 * @param method the method name
	 * @param recv the bound receiver
	 * @param args the lowered arguments
	 * @param otherwise the form for any other receiver
	 * @return the gated draw
	 */
	static LispVal randomArm(String method, LispSymbol recv, List<LispVal> args, LispVal otherwise) {
		String worker = switch (method) {
			case "nextInt" -> args.size() == 1 ? RANDOM_NEXT_INT_BOUND : RANDOM_NEXT_INT;
			case "nextLong" -> RANDOM_NEXT_LONG;
			case "nextFloat" -> RANDOM_NEXT_FLOAT;
			case "nextDouble" -> RANDOM_NEXT_DOUBLE;
			case "nextBoolean" -> RANDOM_NEXT_BOOLEAN;
			case "nextGaussian" -> RANDOM_NEXT_GAUSSIAN;
			case "setSeed" -> RANDOM_SET_SEED;
			default -> throw new IllegalStateException("no portable draw: " + method);
		};
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(worker));
		call.add(recv);
		call.addAll(args);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(new LispSymbol(RANDOM_P), recv),
				ClojureLowerUtil.list(call), otherwise);
	}

	/**
	 * {@code toString} over an already-bound receiver that is no string: a stream answers
	 * its toString ({@code %clojure-stream-string}: a string output stream's text so far
	 * without clearing it, any other stream its host class name; the test is an arm of
	 * {@code ClojureArms.Family.STREAM}, and an exact tag test, since {@code streamp}
	 * answers true for {@code t} and, on the JVM, for a host object whose {@code equals}
	 * answers true); a value of a Lisp kind -- number, character, symbol (keywords and
	 * booleans included), cons (lists, keywords, records, lazy seqs), array, table,
	 * function, condition (an exception's report is its {@code toString}) -- answers its
	 * {@code str} spelling, the oracle's {@code toString}, on every backend; nil signals,
	 * like the oracle's {@code NullPointerException}; anything else is a host object and
	 * keeps the {@code java:call}. No predicate here answers true for a host object (a
	 * host collection is no Lisp array or table on the JVM either), so the host path is
	 * exactly what it was. Where the host is not (wasm) nothing is a host object, so
	 * every value past the stream takes the {@code str} spelling.
	 */
	static LispVal valueToString(ClojureLowering ctx, LispSymbol recv) {
		List<LispVal> direct = new ArrayList<>();
		direct.add(recv);
		direct.add(LispString.literal("toString"));
		LispVal call = ClojureLowerUtil.cons(JAVA_CALL, direct);
		LispVal lispValue = ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("symbolp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("arrayp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("typep"), recv,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.sym("condition"))));
		LispVal str = ClojureLowerUtil.list(ClojureLowering.CLOJURE_STR_OF, recv, LispString.literal("nil"),
				ClojureLowering.NIL_CONST);
		LispVal nil = ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), recv), ClojureRefusals
			.refusal(ClojureRefusals.NULL_POINTER, LispString.literal("NullPointerException: toString of nil")));
		LispVal stream = ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(STREAM_P), recv),
				ClojureLowerUtil.list(new LispSymbol(STREAM_STRING), recv));
		if (!ctx.hostTarget) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"), nil, stream,
					ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, str));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"), nil, stream, ClojureLowerUtil.list(lispValue, str),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, call));
	}

	/**
	 * The classes a receiver of no known class may be an object of where the host is not
	 * (wasm), past the string and the stream: nothing makes a host object there, so what
	 * reaches an instance call's host arm is a number, a character, a boolean or an
	 * exception, and {@code Object}'s methods are every value's. A native image without a
	 * JDK's class files answers these from its own reflection, so its build registers
	 * every one ({@code .kb/clojure-frontend.md}, "Host classes").
	 */
	static final List<String> HOSTLESS_RECEIVERS = List.of(OBJECT, "java.lang.Long", "java.lang.Double",
			"java.lang.Character", "java.lang.Boolean", ClojureThrowables.THROWABLE);

	/**
	 * The JDK classes of the static calls the lowering leaves to the host, mapped to the
	 * members of them macro-time evaluation invokes while a program lowers --
	 * {@code System/getProperty} where the host is ({@link #staticCall}),
	 * {@code Class/forName} of a class literal ({@link #classValue}), over
	 * {@code java.util.regex.Pattern} in the corpus, which the entry holds in the image
	 * for the lookup -- so a macro body calling one runs in the binary as on the jar. The
	 * flagged overload of {@code Pattern/compile} ({@link #patternStatic}) stays the
	 * host's the same way. What the image merely resolves it leaves to run time
	 * ({@code compiler.JavaSiteResolver}, which never fails a program for a class it
	 * cannot inspect), so a resolved-only class needs no entry. A native image without a
	 * JDK's class files answers these from its own reflection, so its build registers
	 * every one ({@code .kb/clojure-frontend.md}, "Host classes").
	 */
	static final Map<String, List<String>> HOST_STATIC_CLASSES = Map.of("java.lang.Class", List.of("forName"),
			"java.lang.System", List.of("getProperty"), "java.util.regex.Pattern", List.of());

	/**
	 * An instance call's host arm for a receiver of no known class where the host is not
	 * (wasm): the {@code java:call} -- a call-time error there, which the compile warns
	 * of -- when a class such a receiver may be an object of has the method at the count,
	 * since the program's call then needs the host; otherwise the oracle's own refusal,
	 * since no value there has the method.
	 * @param recv the bound receiver
	 * @param method the method name
	 * @param args the lowered arguments
	 * @param call the {@code java:call}
	 * @param strings whether a string answers ahead of the arm (the string arm)
	 * @param streams whether a stream answers ahead of the arm (the stream arm)
	 * @return the arm
	 */
	static LispVal hostlessCall(LispSymbol recv, String method, List<LispVal> args, LispVal call, boolean strings,
			boolean streams) {
		List<String> classes = new ArrayList<>(HOSTLESS_RECEIVERS);
		if (!strings) {
			classes.add(STRING);
		}
		if (!streams) {
			classes.add("java.io.Writer");
			classes.add("java.io.Reader");
		}
		return ClojureValueMethodLowering.declares(classes, method, args.size()) ? call
				: ClojureValueMethodLowering.refusal(recv, method, true, args);
	}

	/**
	 * {@code hashCode} over an already-bound receiver of no known class: the oracle's
	 * {@code hashCode} of any value ({@code %clojure-java-hash}: a type's own override, a
	 * record's map hash, a collection's or a number's, a host object's through its host
	 * arm) on every backend; nil signals, like the oracle's {@code NullPointerException}.
	 */
	static LispVal valueHashCode(LispSymbol recv) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), recv),
				ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER,
						LispString.literal("Cannot invoke \"Object.getClass()\" because \"target\" is null")),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-JAVA-HASH"), recv));
	}

	/**
	 * The class a lowered receiver constructs, when it is a construction literal: a
	 * {@code java:new} over a literal class name, or a {@code proxy} of one type -- a
	 * {@code java:proxy} of a single interface, a {@code java:subclass} of a superclass
	 * and no interface -- whose object has exactly that type's methods. No user form
	 * lowers to those heads (anything else spelling them is an unknown name), so the
	 * class is read off the call site with no scope analysis.
	 */
	static @Nullable String constructedClass(LispVal receiver) {
		if (!(receiver instanceof LispCons cell) || !(cell.cdr() instanceof LispCons rest)
				|| !(rest.car() instanceof LispString cls)) {
			return null;
		}
		if (ClojureLowerUtil.isSymbolNamed(cell.car(), "JAVA:NEW")
				|| ClojureLowerUtil.isSymbolNamed(cell.car(), ClojureBytesLowering.STRING_NEW)) {
			// a param-tagged construction names the class before its parameter types
			int tagged = cls.value().indexOf('(');
			return tagged < 0 ? cls.value() : cls.value().substring(0, tagged);
		}
		if (ClojureLowerUtil.isSymbolNamed(cell.car(), "JAVA:PROXY") && rest.cdr() instanceof LispCons callable
				&& !(callable.car() instanceof LispString) && callable.cdr() instanceof LispNil) {
			return cls.value();
		}
		if (ClojureLowerUtil.isSymbolNamed(cell.car(), "JAVA:SUBCLASS") && rest.cdr() instanceof LispCons interfaces
				&& interfaces.car() instanceof LispCons quoted && ClojureLowerUtil.isSymbolNamed(quoted.car(), "QUOTE")
				&& quoted.cdr() instanceof LispCons quotedList && quotedList.car() instanceof LispNil) {
			return cls.value();
		}
		return null;
	}

	/**
	 * A {@code let}-bound name's host class, or null when no visible binding holds one: a
	 * binding above the recording depth shadows it, like any other scope rule, and
	 * anything else was never recorded.
	 */
	static @Nullable String hostClassOf(ClojureLowering ctx, LispSymbol ref) {
		ClojureLowering.HostClass held = ctx.hostClasses.get(ref.name());
		if (held == null) {
			return null;
		}
		for (int i = held.depth(); i < ctx.scopes.size(); i++) {
			if (ctx.scopes.get(i).containsKey(held.name())) {
				return null;
			}
		}
		return held.fqn();
	}

	/**
	 * A lowered receiver's known host class, when it is one: a construction literal or a
	 * recorded local. A {@code ..} step's {@code let} wrapper is never one -- the chain
	 * threads the declared return instead.
	 */
	static @Nullable String classOfLowered(ClojureLowering ctx, LispVal receiver) {
		String cls = constructedClass(receiver);
		if (cls == null && receiver instanceof LispSymbol ref) {
			cls = hostClassOf(ctx, ref);
		}
		return cls;
	}

	/**
	 * A {@code ..} step's receiver class for the next step: the single declared return
	 * type shared by every non-variadic overload at that arity, or null when there is
	 * none, several disagree, or it is no host class (a primitive, void, an array, or an
	 * unloadable class). Primitives stay unknown: a boolean answer is already a Lisp
	 * value, so no further host call wraps it.
	 */
	static @Nullable String declaredInstanceReturn(@Nullable String className, String member, int arity) {
		if (className == null) {
			return null;
		}
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		if (found == null) {
			return null;
		}
		try {
			SingleReturn single = singleReturn(found, member, arity, false);
			return single.seen() ? single.name() : null;
		}
		catch (LinkageError | SecurityException _) {
			return null;
		}
	}

	/**
	 * The same single-return rule for a {@code ..} chain's static first step: the
	 * zero-argument static method's return when one exists, else the static field's type,
	 * else null. Anything else keeps the chain unknown, like an instance step with no
	 * single return.
	 */
	static @Nullable String declaredStaticReturn(String className, String member, int arity) {
		ClojureJavaClasses.JavaClass found = ClojureHostClasses.find(className);
		if (found == null) {
			return null;
		}
		try {
			SingleReturn single = singleReturn(found, member, arity, true);
			if (single.seen() || arity != 0) {
				return single.name();
			}
			// no method: the static field's type, or unknown, like an unknown member
			ClojureJavaClasses.Field field = found.field(member);
			if (field == null || !field.isStatic()) {
				return null;
			}
			String type = field.type();
			return ClojureHostClasses.isPrimitive(type) || ClojureHostClasses.isArray(type) ? null : type;
		}
		catch (LinkageError | SecurityException _) {
			return null;
		}
	}

	/**
	 * What the non-variadic overloads of a name at a count declare they return: whether
	 * there is one, and the single return type's name they share -- null when they
	 * disagree or one returns a primitive, void or an array.
	 */
	private record SingleReturn(boolean seen, @Nullable String name) {
	}

	private static SingleReturn singleReturn(ClojureJavaClasses.JavaClass found, String member, int arity,
			boolean statics) {
		Set<String> returns = new HashSet<>();
		boolean seen = false;
		for (ClojureJavaClasses.Member method : found.methods()) {
			if (!method.name().equals(member) || statics && !method.isStatic() || method.isSynthetic()
					|| method.isVarArgs() || method.parameterCount() != arity) {
				continue;
			}
			seen = true;
			String ret = method.returnType();
			if (ClojureHostClasses.isPrimitive(ret) || ClojureHostClasses.isArray(ret)) {
				return new SingleReturn(true, null);
			}
			returns.add(ret);
		}
		return new SingleReturn(seen, seen && returns.size() == 1 ? returns.iterator().next() : null);
	}

	/**
	 * A stream method over an already-bound receiver: {@code write} prints through
	 * {@code princ} (strings bare, characters as glyphs), {@code flush} finishes the
	 * output, {@code readLine} reads through {@code read-line} (nil past the end, like
	 * the oracle), {@code read} answers one character's code ({@code -1} past the end),
	 * both refusing a closed stream as the oracle's {@code IOException}, and
	 * {@code close} closes the stream, so {@code with-open} over a
	 * {@code clojure.java.io/reader} (an {@code open} file stream) runs on every backend
	 * without reaching {@code java:call}. {@code append} writes any value's {@code str}
	 * (nil is {@code "null"}), answering the stream, like {@code StringBuilder.append};
	 * {@code length}, {@code charAt} and {@code setLength} read a string output stream's
	 * text so far (a {@code StringBuilder} here, and a {@code StringWriter}, the same
	 * object), refusing any other stream in the oracle's words. {@code toString} is
	 * {@link #valueToString}'s. Null when the method maps to nothing, so the call goes to
	 * {@code java:call}.
	 */
	static @Nullable LispVal streamMethod(ClojureLowering ctx, String method, LispSymbol recv, List<LispVal> args) {
		if (method.equals("write") && args.size() == 1) {
			// nil signals, like the oracle's NullPointerException out of Writer.write; an
			// int is the character of that code, Writer.write(int)
			LispSymbol value = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(ClojureLowerUtil.list(value, args.get(0))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("cond"),
							ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), value),
									ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER,
											LispString.literal("NullPointerException: write takes a value, not nil"))),
							ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("integerp"), value),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("write-char"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("code-char"),
													ClojureLowerUtil.list(ClojureLowerUtil.sym("logand"), value,
															new LispInteger(65535))),
											recv),
									ClojureLowering.NIL_CONST),
							ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
									ClojureLowerUtil.list(ClojureLowerUtil.sym("princ"), value, recv),
									ClojureLowering.NIL_CONST)));
		}
		if (method.equals("write") && args.size() == 3) {
			// Writer.write(String, off, len): the part of the string
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("write-string"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"), args.get(0), args.get(1),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), args.get(1), args.get(2))),
							recv),
					ClojureLowering.NIL_CONST);
		}
		if (method.equals("newLine") && args.isEmpty()) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("terpri"), recv), ClojureLowering.NIL_CONST);
		}
		if (method.equals("append") && args.size() == 1) {
			// StringBuilder.append (and Writer.append): any value's str (nil is
			// "null"), the stream answered
			LispSymbol value = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(ClojureLowerUtil.list(value, args.get(0))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("write-string"),
							ClojureLowerUtil.list(ClojureLowering.CLOJURE_STR_OF, value, LispString.literal("null"),
									ClojureLowering.NIL_CONST),
							recv),
					recv);
		}
		if (method.equals("append") && args.size() == 3) {
			// Writer.append(CharSequence, start, end) (data.json's write-string
			// slow path): the subsequence (nil spelling "null", like the host),
			// the stream answered
			return ClojureLowerUtil
				.list(ClojureLowerUtil.sym("progn"),
						ClojureLowerUtil
							.list(ClojureLowerUtil.sym("write-string"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("subseq"),
											ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), args.get(0),
													LispString.literal("null")),
											args.get(1), args.get(2)),
									recv),
						recv);
		}
		if (method.equals("length") && args.isEmpty()) {
			// StringBuilder.length: the text so far (a StringWriter's too, the same
			// object here); any other stream the oracle's refusal
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(new LispSymbol(STRING_OUTPUT_P), recv),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("length"),
							ClojureLowerUtil.list(new LispSymbol(STREAM_STRING), recv)),
					ClojureValueMethodLowering.refusal(recv, method, true, List.of()));
		}
		if (method.equals("charAt") && args.size() == 1) {
			// StringBuilder.charAt: the character at the index of the text so far,
			// refusing outside it like a string's
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(new LispSymbol(STRING_OUTPUT_P), recv),
					ClojureLowerUtil.list(new LispSymbol(ClojureRefusals.CHAR_AT),
							ClojureLowerUtil.list(new LispSymbol(STREAM_STRING), recv),
							ClojureStringLowering.bound(args.get(0))),
					ClojureValueMethodLowering.refusal(recv, method, true, args));
		}
		if (method.equals("setLength") && args.size() == 1) {
			// StringBuilder.setLength: the text cut or null-padded to the length,
			// answering nil like the oracle's void method
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(new LispSymbol(STRING_OUTPUT_P), recv),
					ClojureLowerUtil.list(new LispSymbol(STRING_BUILDER_SET_LENGTH), recv,
							ClojureStringLowering.bound(args.get(0))),
					ClojureValueMethodLowering.refusal(recv, method, true, args));
		}
		if (method.equals("flush") && args.isEmpty()) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("finish-output"), recv);
		}
		if (method.equals("readLine") && args.isEmpty()) {
			// nil past the end, like the oracle (a Java reader takes the java:call path)
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("read-line"), ClojureStringLowering.openReader(recv),
					ClojureLowering.NIL_CONST, ClojureLowering.NIL_CONST);
		}
		if (method.equals("close") && args.isEmpty()) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("close"), recv);
		}
		if (method.equals("read") && args.isEmpty()) {
			// one character's code, -1 past the end, like Reader.read
			LispSymbol c = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(ClojureLowerUtil.list(c,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("read-char"), ClojureStringLowering.openReader(recv),
							ClojureLowering.NIL_CONST, ClojureLowering.NIL_CONST))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), c,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("char-code"), c), new LispInteger(-1)));
		}
		return null;
	}

	/**
	 * A {@code String} instance method over an already-bound string receiver: the core
	 * operation answering what the oracle answers (a missing {@code indexOf} is
	 * {@code -1}, like the oracle, not the {@code nil} {@code clojure.string} favors).
	 * Null when the method maps to nothing, so the call goes to {@code java:call}.
	 */
	static @Nullable LispVal stringMethod(ClojureLowering ctx, String method, LispVal recv, List<LispVal> args,
			boolean typed) {
		return switch (method) {
			case "toUpperCase" -> switch (args.size()) {
				case 0 -> ClojureStringLowering.caseMapped(recv, true);
				// the mapping is locale-independent (but for tr/az/lt, which nothing
				// here models), so a Locale argument is evaluated for its effects and
				// ignored
				case 1 -> ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), args.get(0),
						ClojureStringLowering.caseMapped(recv, true));
				default -> null;
			};
			case "toLowerCase" -> switch (args.size()) {
				case 0 -> ClojureStringLowering.caseMapped(recv, false);
				case 1 -> ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), args.get(0),
						ClojureStringLowering.caseMapped(recv, false));
				default -> null;
			};
			case "trim" -> args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-trim"),
					ClojureStringLowering.javaTrimBag(), recv) : null;
			case "strip" -> args.isEmpty()
					? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-trim"), ClojureStringLowering.trimBag(), recv)
					: null;
			case "stripLeading" -> args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-left-trim"),
					ClojureStringLowering.trimBag(), recv) : null;
			case "stripTrailing" -> args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("string-right-trim"),
					ClojureStringLowering.trimBag(), recv) : null;
			case "length" -> args.isEmpty() ? ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), recv) : null;
			case "isEmpty" -> args.isEmpty() ? ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("zerop"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), recv))) : null;
			case "isBlank" -> args.isEmpty() ? ctx.booleanAnswer(ClojureStringLowering.blankForm(recv)) : null;
			case "toString" -> args.isEmpty() ? recv : null;
			// .intern of a string is the string (no interning is observable here but
			// identical?)
			case "intern" -> args.isEmpty() ? recv : null;
			case "getClass" ->
				args.isEmpty() ? ClojureLowerUtil.cons(JAVA_STATIC, List.of(LispString.literal("java.lang.Class"),
						LispString.literal("forName"), LispString.literal("java.lang.String"))) : null;
			case "substring" -> switch (args.size()) {
				case 1 -> ClojureLowerUtil.list(
						new LispSymbol(typed ? ClojureRefusals.SUBS : ClojureRefusals.SUBS_BY_REFLECTION), recv,
						ClojureStringLowering.bound(args.get(0)));
				case 2 -> ClojureLowerUtil.list(
						new LispSymbol(typed ? ClojureRefusals.SUBS : ClojureRefusals.SUBS_BY_REFLECTION), recv,
						ClojureStringLowering.bound(args.get(0)), ClojureStringLowering.bound(args.get(1)));
				default -> null;
			};
			// .subSequence of two indices is the substring of the same range, like
			// the oracle's String, whose subSequence answers one
			case "subSequence" ->
				args.size() == 2
						? ClojureLowerUtil.list(
								new LispSymbol(typed ? ClojureRefusals.SUBS : ClojureRefusals.SUBS_BY_REFLECTION), recv,
								ClojureStringLowering.bound(args.get(0)), ClojureStringLowering.bound(args.get(1)))
						: null;
			// .getChars copies the range into the char array, which is a vector of
			// characters here, so an aset loop writes it on every backend
			case "getChars" -> args.size() == 4 ? getCharsForm(ctx, recv, args) : null;
			case "charAt" -> args.size() == 1 ? ClojureLowerUtil.list(
					new LispSymbol(typed ? ClojureRefusals.CHAR_AT : ClojureRefusals.CHAR_AT_BY_REFLECTION), recv,
					ClojureStringLowering.bound(args.get(0))) : null;
			case "equals" -> args.size() == 1
					? ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("string="), recv, args.get(0)))
					: null;
			case "equalsIgnoreCase" -> args.size() == 1
					? ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("string-equal"), recv, args.get(0)))
					: null;
			// a CharSequence argument is read as its characters (a view a program
			// storing no such row folds)
			case "contains" -> args.size() == 1 ? ctx.booleanAnswer(
					ClojureStringLowering.affixForm("includes?", recv, ClojureStringLowering.textOf(args.get(0))))
					: null;
			case "startsWith" -> args.size() == 1
					? ctx.booleanAnswer(ClojureStringLowering.affixForm("starts-with?", recv, args.get(0))) : null;
			case "endsWith" -> args.size() == 1
					? ctx.booleanAnswer(ClojureStringLowering.affixForm("ends-with?", recv, args.get(0))) : null;
			case "indexOf" -> stringIndexForm(ctx, recv, args, false);
			case "lastIndexOf" -> stringIndexForm(ctx, recv, args, true);
			case "replace" -> args.size() == 2 ? ClojureStringLowering.replaceForm(ctx, recv,
					ClojureStringLowering.textOf(args.get(0)), ClojureStringLowering.textOf(args.get(1)), false) : null;
			case "replaceFirst" ->
				args.size() == 2 ? ClojureStringLowering.replaceForm(ctx, recv, args.get(0), args.get(1), true) : null;
			case "split" -> switch (args.size()) {
				case 1 -> ClojureStringLowering.splitForm(ctx, recv, args.get(0), ClojureLowering.NIL_CONST);
				case 2 -> ClojureStringLowering.splitForm(ctx, recv, args.get(0), args.get(1));
				default -> null;
			};
			case "concat" -> args.size() == 1 ? ClojureLowerUtil.list(ClojureLowerUtil.sym("concatenate"),
					ClojureLowerUtil.quoted("string"), recv, args.get(0)) : null;
			case "getBytes" -> ClojureBytesLowering.getBytes(recv, args);
			case "repeat" -> args.size() == 1 ? ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("concatenate")),
					ClojureLowerUtil.quoted("string"), ClojureLowerUtil.list(ClojureLowerUtil.sym("make-list"),
							args.get(0), ClojureLowerUtil.sym(":initial-element"), recv))
					: null;
			default -> null;
		};
	}

	/**
	 * {@code String.getChars} over an already-bound string receiver: the characters of
	 * the range copied into the array, answering nil, like the oracle's void. The bounds
	 * the oracle checks are the loop's own.
	 */
	static LispVal getCharsForm(ClojureLowering ctx, LispVal recv, List<LispVal> args) {
		LispSymbol from = ctx.freshTemp();
		LispSymbol to = ctx.freshTemp();
		LispSymbol dst = ctx.freshTemp();
		LispSymbol at = ctx.freshTemp();
		LispSymbol i = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(from, ClojureStringLowering.bound(args.get(0))),
						ClojureLowerUtil.list(to, ClojureStringLowering.bound(args.get(1))),
						ClojureLowerUtil.list(dst, args.get(2)),
						ClojureLowerUtil.list(at, ClojureStringLowering.bound(args.get(3))))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("dotimes"),
						ClojureLowerUtil.list(i, ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), to, from)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"), dst,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), at, i)),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("char"), recv,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), from, i)))),
				ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code indexOf} / {@code lastIndexOf} over an already-bound string: the index, or
	 * {@code -1} when missing, like the oracle. A needle that is no string literal goes
	 * through {@code %clojure-index-needle}, so an int searches its character, the
	 * {@code indexOf(int)} overload (honeysql's {@code (.indexOf s (int ch))}).
	 */
	static @Nullable LispVal stringIndexForm(ClojureLowering ctx, LispVal recv, List<LispVal> args, boolean last) {
		if (args.isEmpty() || args.size() > 2) {
			return null;
		}
		LispVal needle = args.get(0) instanceof LispString ? args.get(0)
				: ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-INDEX-NEEDLE"), args.get(0));
		LispVal search = switch (args.size()) {
			case 1 -> last ? ClojureStringLowering.lastIndexForm(recv, needle, null)
					: ClojureLowerUtil.list(ClojureLowerUtil.sym("search"), needle, recv);
			case 2 -> last ? ClojureStringLowering.lastIndexForm(recv, needle, args.get(1))
					: ClojureStringLowering.searchFrom(needle, recv, args.get(1));
			default -> null;
		};
		if (search == null) {
			return null;
		}
		LispSymbol at = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(at, search))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), at), new LispInteger(-1), at));
	}

	/**
	 * Whether a call head of this name lowers as a construction: {@code X.} with a
	 * class-spelling {@code X}, the shape {@code interopCall} builds a host construction
	 * (or a record/deftype constructor) for. Any other trailing-dot name falls through to
	 * the macro and var tables, so a program macro of that name (shcloj4's {@code ?.})
	 * wins at call sites -- and may be defined, since a refused definition would be the
	 * only thing stopping it.
	 */
	static boolean isConstructionHead(String name) {
		return name.endsWith(".") && name.length() > 1 && isClassSpelling(name.substring(0, name.length() - 1));
	}

	/** Whether the spelling can name a class: a dotted name over identifier parts. */
	static boolean isClassSpelling(String spelling) {
		if (spelling.isEmpty() || !Character.isJavaIdentifierStart(spelling.charAt(0))) {
			return false;
		}
		for (int i = 1; i < spelling.length(); i++) {
			char c = spelling.charAt(i);
			if (!Character.isJavaIdentifierPart(c) && c != '.') {
				return false;
			}
		}
		return true;
	}

}
