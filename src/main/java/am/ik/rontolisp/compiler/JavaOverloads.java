package am.ik.rontolisp.compiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * THE {@code java:} overload and conversion rule: the per-kind rule table
 * ({@link KindRule}, {@link #rule}), the conversion cost of an argument kind
 * ({@link #kindCost}) or a callback answer ({@link #returnedCost}) to a parameter type,
 * the scalar and checked argument conversions ({@link #convertLong},
 * {@link #convertCheckedLong}, ...), the selection of the cheapest overload
 * ({@link #select}, {@link #selectRanked}), and the parameter tags that narrow the
 * candidates ({@link #parseMember}). The interpreter ({@code eval.JavaInterop}), the site
 * resolver ({@link JavaSiteResolver}) and the direct-site emitter
 * ({@code codegen.jvm.JvmJavaDirectSites}) read this class directly; the compiled
 * program's standalone bridge ({@code codegen.jvm.JavaBridgeTemplate}) reads the
 * serialized {@link #ruleTable()}, pinned to this one by
 * {@code JavaBridgeTemplateParityTest}.
 */
public final class JavaOverloads {

	/** The ideal target: {@code int} for an integer, {@code String} for a string. */
	public static final int COST_EXACT = 0;

	/** A lossless widening: {@code long} for an integer, a subclass to its superclass. */
	public static final int COST_WIDEN = 1;

	/** Representable but less ideal: {@code double} for an integer. */
	public static final int COST_CONVERT = 2;

	/** Narrowing or lossy: {@code short} for an integer, {@code char} for a string. */
	public static final int COST_NARROW = 4;

	/** A boxed, {@code Object}, {@code Number} or other supertype target. */
	public static final int COST_BOXED = 6;

	/**
	 * A Lisp callable where {@code java.util.concurrent.Callable} is expected: before
	 * every other interface, as a function answers a value and {@code Callable} is the
	 * interface that keeps it -- so {@code ExecutorService.submit} of a function takes
	 * {@code submit(Callable)}, which the oracle's reflector picks for every executor but
	 * {@code ScheduledThreadPoolExecutor} (its {@code getMethods()} order).
	 */
	public static final int COST_FUNCTION_CALLABLE = 7;

	/** A Lisp callable adapted to a functional interface (one abstract method). */
	public static final int COST_PROXY = 8;

	/**
	 * A Lisp callable where {@code java.lang.Object} is expected: after every interface
	 * the function implements (a functional one, as a Java lambda's target, and
	 * {@code Runnable}, {@code Callable} or {@code Comparator}, which Clojure's fn is
	 * itself), before any other interface -- Clojure's fn is an {@code Object} and no
	 * {@code CharSequence}, so {@code StringBuilder.append(Object)} takes it.
	 */
	public static final int COST_FUNCTION_OBJECT = 9;

	/**
	 * A Lisp callable adapted to an interface of several abstract methods, or none: after
	 * every functional one, as only a functional interface takes a Java lambda
	 * ({@link JavaInterfaceMethods#isFunctionalInterface}), and after {@code Object}
	 * ({@link #COST_FUNCTION_OBJECT}).
	 */
	public static final int COST_PROXY_NOT_FUNCTIONAL = 10;

	/** The flat penalty for packing a varargs tail: a fixed-arity overload wins. */
	public static final int COST_VARARGS = 10;

	/**
	 * A {@code java:view} {@code List} converted to an array of its items (plus their
	 * costs as the component type): after every way to pass the view itself -- even
	 * packed into a varargs array, {@link #COST_VARARGS} plus {@link #COST_WIDEN} -- so a
	 * collection that is one object to Java is unpacked only where nothing takes it
	 * whole, such as a Java array a call answered (a list here) handed back to an array
	 * parameter.
	 */
	public static final int COST_VIEW_ARRAY = 12;

	/** This argument cannot become this type. */
	public static final int NO_MATCH = -1;

	/**
	 * The cost of a {@code java:view} of shape {@code :bytes} for a parameter type: the
	 * {@code byte[]} it is to Java, which fits only {@code byte[]} itself and the types
	 * every array is ({@code Object}, {@code Cloneable}, {@code Serializable}). The
	 * interpreter, the direct sites and the bridge (its own copy) cost it by this.
	 * @param target the parameter type
	 * @return {@link #COST_EXACT} for {@code byte[]}, {@link #COST_WIDEN} for a
	 * supertype, else {@link #NO_MATCH}
	 */
	public static int bytesViewCost(JavaType target) {
		return switch (target.name()) {
			case "[B" -> COST_EXACT;
			case "java.lang.Object", "java.lang.Cloneable", "java.io.Serializable" -> COST_WIDEN;
			default -> NO_MATCH;
		};
	}

	/**
	 * Whether a value of a parameter type may be or hold a {@code byte[]}: a type a
	 * {@code byte[]} fits ({@link #bytesViewCost}), or an array of one. Only such a
	 * parameter is handed an octet vector at {@code :octets}.
	 * @param type the parameter type
	 * @return whether the value may carry a {@code byte[]}
	 */
	public static boolean carriesBytes(JavaType type) {
		JavaType component = type.componentType();
		return bytesViewCost(type) != NO_MATCH || component != null && carriesBytes(component);
	}

	/** The class a bignum is, and a fixnum may become. */
	private static final String BIG_INTEGER = "java.math.BigInteger";

	/**
	 * The non-array classes the compiled Lisp representation uses for scalar values (an
	 * integer's {@code Long}, a float's {@code Double}, a bignum's {@code BigInteger}, a
	 * string's or symbol's {@code String}): none of them is a wrapped host object
	 * ({@code JvmJavaDirectSites._jhost}, {@code JavaBridgeTemplate.isJavaObject}).
	 */
	public static final List<String> COMPILED_SCALAR_CLASSES = List.of("java.lang.Long", "java.lang.Double",
			BIG_INTEGER, "java.lang.String");

	/**
	 * One {@link JavaKind.Lisp}'s row in the resolution and conversion rule table: its
	 * token, the parameter types it matches at {@link #COST_EXACT}, {@link #COST_WIDEN}
	 * and {@link #COST_NARROW}, the reference types whose supertypes it matches at
	 * {@link #COST_BOXED}, the extra return-type targets a callback's answer accepts
	 * ({@link #returnedCost}), whether a value of it is a {@code java:call} receiver and
	 * the class it converts to when the kind fixes it ({@link #receiverClassName}), and
	 * the box class a checked conversion's refusal names ({@link #checkedBoxName}).
	 */
	public static final class KindRule {

		private final String token;

		private final @Nullable String exact;

		private final List<String> widen;

		private final List<String> narrow;

		private final List<String> boxes;

		private final Map<String, Integer> returnedExtras;

		private final boolean receiver;

		private final @Nullable String receiverClass;

		private final String checkedBoxName;

		private KindRule(Builder builder) {
			this.token = builder.token;
			this.exact = builder.exact;
			this.widen = List.copyOf(builder.widen);
			this.narrow = List.copyOf(builder.narrow);
			this.boxes = List.copyOf(builder.boxes);
			this.returnedExtras = Collections.unmodifiableMap(new LinkedHashMap<>(builder.returnedExtras));
			this.receiver = builder.receiver;
			this.receiverClass = builder.receiverClass;
			if (builder.checkedBoxName != null) {
				this.checkedBoxName = builder.checkedBoxName;
			}
			else if (!this.boxes.isEmpty()) {
				this.checkedBoxName = this.boxes.get(0);
			}
			else {
				this.checkedBoxName = "null";
			}
		}

		/**
		 * @return the kind's bridge token ({@code "integer"}, {@code "string/1"}, ...)
		 */
		public String token() {
			return this.token;
		}

		/**
		 * @return the parameter type matching at {@link #COST_EXACT}, or {@code null}
		 */
		public @Nullable String exact() {
			return this.exact;
		}

		/**
		 * @return the parameter types matching at {@link #COST_WIDEN}
		 */
		public List<String> widen() {
			return this.widen;
		}

		/**
		 * @return the parameter types matching at {@link #COST_NARROW}
		 */
		public List<String> narrow() {
			return this.narrow;
		}

		/**
		 * @return the reference types whose supertypes match at {@link #COST_BOXED}
		 */
		public List<String> boxes() {
			return this.boxes;
		}

		/**
		 * @return the extra return-type targets and costs for {@link #returnedCost}
		 */
		public Map<String, Integer> returnedExtras() {
			return this.returnedExtras;
		}

		/**
		 * @return whether a value of this kind is a {@code java:call} receiver
		 */
		public boolean receiver() {
			return this.receiver;
		}

		/**
		 * @return the fixed class a receiver of this kind converts to, or {@code null}
		 */
		public @Nullable String receiverClass() {
			return this.receiverClass;
		}

		/**
		 * @return the box class a checked conversion's refusal names
		 */
		public String checkedBoxName() {
			return this.checkedBoxName;
		}

		private static final class Builder {

			private final String token;

			private @Nullable String exact;

			private List<String> widen = List.of();

			private List<String> narrow = List.of();

			private List<String> boxes = List.of();

			private final Map<String, Integer> returnedExtras = new LinkedHashMap<>();

			private boolean receiver;

			private @Nullable String receiverClass;

			private @Nullable String checkedBoxName;

			private Builder(String token) {
				this.token = token;
			}

			private Builder exact(String type) {
				this.exact = type;
				return this;
			}

			private Builder widen(String... types) {
				this.widen = List.of(types);
				return this;
			}

			private Builder narrow(String... types) {
				this.narrow = List.of(types);
				return this;
			}

			private Builder boxes(String... types) {
				this.boxes = List.of(types);
				return this;
			}

			private Builder returned(int cost, String... types) {
				for (String type : types) {
					this.returnedExtras.put(type, cost);
				}
				return this;
			}

			private Builder receiver(String className) {
				this.receiver = true;
				this.receiverClass = className;
				return this;
			}

			private Builder variableReceiver() {
				this.receiver = true;
				return this;
			}

			private Builder checkedBox(String className) {
				this.checkedBoxName = className;
				return this;
			}

			private KindRule build() {
				return new KindRule(this);
			}

		}

	}

	// THE rule table, indexed by JavaKind.Lisp.ordinal().
	private static final KindRule[] RULES = buildRules();

	private static KindRule[] buildRules() {
		KindRule[] rules = new KindRule[JavaKind.Lisp.values().length];
		rules[JavaKind.Lisp.NIL.ordinal()] = new KindRule.Builder("nil").returned(COST_EXACT, "boolean")
			.checkedBox("null")
			.build();
		rules[JavaKind.Lisp.T.ordinal()] = new KindRule.Builder("t").exact("boolean")
			.widen("java.lang.Boolean")
			.boxes("java.lang.Boolean")
			.receiver("java.lang.Boolean")
			.build();
		rules[JavaKind.Lisp.FALSE.ordinal()] = new KindRule.Builder("false").exact("boolean")
			.widen("java.lang.Boolean")
			.boxes("java.lang.Boolean")
			.receiver("java.lang.Boolean")
			.build();
		rules[JavaKind.Lisp.INTEGER.ordinal()] = new KindRule.Builder("integer").exact("long")
			.widen("int", "java.lang.Long")
			.boxes("java.lang.Long", "java.lang.Integer")
			.returned(COST_CONVERT, "double", "java.lang.Double", "float", "java.lang.Float", BIG_INTEGER)
			.returned(COST_NARROW, "short", "java.lang.Short", "byte", "java.lang.Byte")
			.variableReceiver()
			.build();
		rules[JavaKind.Lisp.BIGNUM.ordinal()] = new KindRule.Builder("bignum").exact(BIG_INTEGER)
			.boxes(BIG_INTEGER)
			.receiver(BIG_INTEGER)
			.build();
		rules[JavaKind.Lisp.FLOAT.ordinal()] = new KindRule.Builder("float").exact("double")
			.widen("java.lang.Double")
			.narrow("float", "java.lang.Float")
			.boxes("java.lang.Double")
			.returned(COST_NARROW, "float", "java.lang.Float")
			.receiver("java.lang.Double")
			.build();
		rules[JavaKind.Lisp.STRING_1.ordinal()] = new KindRule.Builder("string/1").exact("java.lang.String")
			.boxes("java.lang.String")
			.returned(COST_NARROW, "char", "java.lang.Character")
			.receiver("java.lang.String")
			.build();
		rules[JavaKind.Lisp.STRING.ordinal()] = new KindRule.Builder("string").exact("java.lang.String")
			.boxes("java.lang.String")
			.receiver("java.lang.String")
			.build();
		rules[JavaKind.Lisp.CHAR.ordinal()] = new KindRule.Builder("char").exact("char")
			.widen("java.lang.Character")
			.boxes("java.lang.Character")
			.returned(COST_WIDEN, "int", "java.lang.Integer")
			.receiver("java.lang.Character")
			.build();
		rules[JavaKind.Lisp.SUPPLEMENTARY_CHAR.ordinal()] = new KindRule.Builder("char/supplementary").exact("long")
			.widen("int", "java.lang.Long")
			.boxes("java.lang.Long", "java.lang.Integer")
			.receiver("java.lang.Integer")
			.build();
		rules[JavaKind.Lisp.FUNCTION.ordinal()] = new KindRule.Builder("function")
			.checkedBox(JavaImplementation.FUNCTION_CLASS)
			.build();
		return rules;
	}

	/**
	 * The rule row for a Lisp kind.
	 * @param kind the kind
	 * @return its rule
	 */
	public static KindRule rule(JavaKind.Lisp kind) {
		return RULES[kind.ordinal()];
	}

	/**
	 * Serializes the per-kind rule table into the compact line-per-kind format
	 * {@code codegen.jvm.JavaBridgeTemplate} reads:
	 * {@code token|exact|widen|narrow|boxes|returnedExtras|receiver}.
	 * @return the serialized rule table
	 */
	public static String ruleTable() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < RULES.length; i++) {
			if (i > 0) {
				sb.append('\n');
			}
			KindRule r = RULES[i];
			sb.append(r.token()).append('|');
			if (r.exact() != null) {
				sb.append(r.exact());
			}
			sb.append('|').append(String.join(",", r.widen()));
			sb.append('|').append(String.join(",", r.narrow()));
			sb.append('|').append(String.join(",", r.boxes())).append('|');
			boolean first = true;
			for (Map.Entry<String, Integer> entry : r.returnedExtras().entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(entry.getKey()).append('=').append(entry.getValue());
			}
			sb.append('|').append(r.receiver() ? '1' : '0');
		}
		return sb.toString();
	}

	/**
	 * The conversion cost of a value a {@code java:reify} / {@code java:proxy} function
	 * answers for the method's return type: what the value converts to however Java takes
	 * it, as {@code java:} always converted answers (the oracle's proxy class unboxes a
	 * number unchecked, never refusing one). A fixnum narrows to a short, a byte, a
	 * float, a double or a BigInteger; a float to a float; a one-character string to a
	 * char; a character to an int; nil to a boolean -- beyond what {@link #kindCost}
	 * matches for arguments. Anything else costs as for arguments.
	 * @param kind the answer kind
	 * @param target the return type
	 * @param lookup where the boxed types and {@code String} are found
	 * @return the cost, or {@link #NO_MATCH}
	 */
	public static int returnedCost(JavaKind kind, JavaType target, JavaClassLookup lookup) {
		if (kind instanceof JavaKind.Lisp lisp) {
			Integer extra = rule(lisp).returnedExtras().get(target.name());
			if (extra != null) {
				return extra;
			}
		}
		return kindCost(kind, target, lookup);
	}

	/**
	 * The class every value is: a function converts to it
	 * ({@link #COST_FUNCTION_OBJECT}).
	 */
	public static final String OBJECT = "java.lang.Object";

	/** The interface a function prefers ({@link #COST_FUNCTION_CALLABLE}). */
	public static final String CALLABLE = "java.util.concurrent.Callable";

	/** A tag type that matches any parameter type. */
	public static final String WILDCARD = "_";

	/** The prefix of a designator entry that hints an argument's class. */
	public static final String HINT = "^";

	/**
	 * What the oracle's reflector refuses a call with (clj 1.12.6, measured 2026-10-13):
	 * a static call names no class, an instance call the receiver's.
	 * @param name the method name
	 * @param argc the argument count
	 * @param contextClass the receiver's class for an instance call, {@code null} for a
	 * static one
	 * @return e.g. {@code No matching method min found taking 2 args}
	 */
	public static String noMatchingMethod(String name, int argc, @Nullable String contextClass) {
		return "No matching method " + name + " found taking " + argc + " args"
				+ (contextClass != null ? " for " + contextClass : "");
	}

	/**
	 * What the oracle's reflector refuses a construction with (clj 1.12.6, measured
	 * 2026-10-13).
	 * @param className the class
	 * @return e.g. {@code No matching ctor found for class java.math.BigDecimal}
	 */
	public static String noMatchingCtor(String className) {
		return "No matching ctor found for class " + className;
	}

	private JavaOverloads() {
	}

	/**
	 * An overload {@link #select} chose.
	 *
	 * @param executable the method or constructor
	 * @param packed whether the trailing arguments are packed into its varargs array
	 */
	public record Overload(JavaExecutable executable, boolean packed) {

		/**
		 * @return whether the other overload is this one: the same member, packed the
		 * same way
		 */
		public boolean sameAs(Overload other) {
			return this.packed == other.packed && sameMember(this.executable, other.executable);
		}

	}

	/** The cost of passing argument {@code index} where {@code target} is expected. */
	@FunctionalInterface
	public interface ArgumentCost {

		/**
		 * @param index the argument index
		 * @param target the parameter type
		 * @return the conversion cost, or {@link #NO_MATCH}
		 */
		int cost(int index, JavaType target);

	}

	/**
	 * A member designator: the name, and the parameter types a tag fixes or the classes
	 * hints give the arguments.
	 *
	 * @param name the method name, or for {@code java:new} the class name
	 * @param tag the tag's types in {@link Class#getName()} spelling ({@link #WILDCARD}
	 * for {@code _}), or {@code null} for an untagged designator
	 * @param hints the argument classes the hints name ({@link #WILDCARD} for an argument
	 * with none), or {@code null} for a designator with no hint ({@link #filterByHints})
	 */
	public record Member(String name, @Nullable List<String> tag, @Nullable List<String> hints) {

		/**
		 * A designator with no hint.
		 * @param name the method name, or for {@code java:new} the class name
		 * @param tag the tag's types, or {@code null} for an untagged designator
		 */
		public Member(String name, @Nullable List<String> tag) {
			this(name, tag, null);
		}

		/**
		 * @return the designator in the spelling {@link #parseMember} reads back
		 */
		public String designator() {
			List<String> types = this.tag;
			if (types != null) {
				return this.name + "(" + String.join(",", types) + ")";
			}
			List<String> classes = this.hints;
			if (classes == null) {
				return this.name;
			}
			List<String> spelled = new ArrayList<>();
			for (String hint : classes) {
				spelled.add(WILDCARD.equals(hint) ? hint : HINT + hint);
			}
			return this.name + "(" + String.join(",", spelled) + ")";
		}

	}

	/**
	 * Picks the overload whose arguments convert at the lowest total cost. Ties are
	 * broken by the parameter-type signature, so the choice never depends on the
	 * (unspecified) order the candidates come in; among covariant variants of one
	 * parameter list the most specific return type wins (a bridge method is never chosen
	 * over the method it bridges to). A varargs executable is tried both as-is (the last
	 * argument supplying the array) and with the trailing arguments packed, at
	 * {@link #COST_VARARGS} extra. A pure function of the candidates and the costs: over
	 * {@link #kindCost} of argument kinds it reads no argument value.
	 * @param candidates the methods or constructors
	 * @param argc the argument count
	 * @param cost the cost of each argument against a parameter type
	 * @return the chosen overload, or {@code null} when none accepts the arguments
	 */
	public static @Nullable Overload select(List<? extends JavaExecutable> candidates, int argc, ArgumentCost cost) {
		Overload best = null;
		int bestCost = 0;
		for (JavaExecutable e : candidates) {
			List<? extends JavaType> params = e.parameterTypes();
			int fixedCost = fixedArityCost(params, argc, cost);
			if (fixedCost != NO_MATCH) {
				Overload fixed = new Overload(e, false);
				if (best == null || beats(fixed, fixedCost, best, bestCost)) {
					best = fixed;
					bestCost = fixedCost;
				}
			}
			if (e.isVarArgs()) {
				int packedCost = varargsCost(params, argc, cost);
				if (packedCost != NO_MATCH) {
					Overload packed = new Overload(e, true);
					if (best == null || beats(packed, packedCost, best, bestCost)) {
						best = packed;
						bestCost = packedCost;
					}
				}
			}
		}
		return best;
	}

	/**
	 * The one fixed-arity member of the argument count, or {@code null} when the
	 * candidates hold none or several applicable shapes for it: a varargs executable of
	 * the count applies packed as well as fixed, so it never stands alone. What a lone
	 * candidate is called with converts with the oracle's checked casts, whatever the
	 * cost table says.
	 */
	public static @Nullable Overload singleFixed(List<? extends JavaExecutable> candidates, int argc) {
		@Nullable Overload found = null;
		for (JavaExecutable candidate : candidates) {
			List<? extends JavaType> params = candidate.parameterTypes();
			if (params.size() == argc && !candidate.isVarArgs()) {
				if (found != null) {
					return null;
				}
				found = new Overload(candidate, false);
			}
			else if (candidate.isVarArgs() && argc >= params.size() - 1
					&& params.get(params.size() - 1).componentType() != null) {
				// A packed tail applies too: the call is some shape's, not one
				// member's.
				return null;
			}
		}
		return found;
	}

	/**
	 * Every overload {@link #select} could choose among these candidates for {@code argc}
	 * arguments -- each executable as-is when its arity matches, a varargs one packed too
	 * -- in the order {@link #select} breaks a cost tie by: of the cheapest, the first
	 * wins ({@link #selectRanked}). A site whose argument kinds are known only when it
	 * runs chooses over this list, which a compiled program carries as its dispatch
	 * ({@code codegen.jvm.JvmJavaDirectSites}).
	 * @param candidates the methods or constructors
	 * @param argc the argument count
	 * @return the overloads, the tie winner first
	 */
	public static List<Overload> ranked(List<? extends JavaExecutable> candidates, int argc) {
		List<Overload> ranked = new ArrayList<>();
		for (JavaExecutable e : candidates) {
			List<? extends JavaType> params = e.parameterTypes();
			if (params.size() == argc) {
				insertRanked(ranked, new Overload(e, false));
			}
			if (e.isVarArgs() && argc >= params.size() - 1 && params.get(params.size() - 1).componentType() != null) {
				insertRanked(ranked, new Overload(e, true));
			}
		}
		return List.copyOf(ranked);
	}

	// Stable insertion by the tie rule: an overload goes before every one it beats at
	// equal cost.
	private static void insertRanked(List<Overload> ranked, Overload overload) {
		int at = ranked.size();
		while (at > 0 && beats(overload, 0, ranked.get(at - 1), 0)) {
			at--;
		}
		ranked.add(at, overload);
	}

	/**
	 * The cheapest overload of a {@link #ranked} list, the first on a tie: what
	 * {@link #select} chooses over the same candidates.
	 * @param ranked the overloads, in {@link #ranked} order
	 * @param argc the argument count
	 * @param cost the cost of each argument against a parameter type
	 * @return the chosen overload, or {@code null} when none accepts the arguments
	 */
	public static @Nullable Overload selectRanked(List<Overload> ranked, int argc, ArgumentCost cost) {
		Overload best = null;
		int bestCost = 0;
		for (Overload overload : ranked) {
			int c = overloadCost(overload, argc, cost);
			if (c != NO_MATCH && (best == null || c < bestCost)) {
				best = overload;
				bestCost = c;
			}
		}
		return best;
	}

	/**
	 * What passing {@code argc} arguments to an overload costs: the sum of their costs,
	 * plus {@link #COST_VARARGS} when the tail is packed.
	 * @param overload the overload
	 * @param argc the argument count
	 * @param cost the cost of each argument against a parameter type
	 * @return the total, or {@link #NO_MATCH}
	 */
	public static int overloadCost(Overload overload, int argc, ArgumentCost cost) {
		List<? extends JavaType> params = overload.executable().parameterTypes();
		return overload.packed() ? varargsCost(params, argc, cost) : fixedArityCost(params, argc, cost);
	}

	/**
	 * The type argument {@code index} is converted to: its parameter, or the component
	 * type of a packed varargs array.
	 * @param overload the overload
	 * @param index the argument index
	 * @return the parameter type
	 */
	public static JavaType parameterAt(Overload overload, int index) {
		List<? extends JavaType> params = overload.executable().parameterTypes();
		int last = params.size() - 1;
		if (overload.packed() && index >= last) {
			return java.util.Objects.requireNonNull(params.get(last).componentType());
		}
		return params.get(index);
	}

	private static boolean beats(Overload a, int costA, Overload b, int costB) {
		if (costA != costB) {
			return costA < costB;
		}
		int bySignature = signatureOf(a).compareTo(signatureOf(b));
		if (bySignature != 0) {
			return bySignature < 0;
		}
		// One parameter list, several return types: the covariant override over the
		// bridge that erases it (StringBuilder.append(String) returning StringBuilder
		// over the AbstractStringBuilder one).
		JavaType ra = a.executable().returnType();
		JavaType rb = b.executable().returnType();
		if (ra != rb) {
			if (rb.isAssignableFrom(ra)) {
				return true;
			}
			if (ra.isAssignableFrom(rb)) {
				return false;
			}
		}
		return tieKey(a.executable()).compareTo(tieKey(b.executable())) < 0;
	}

	private static String tieKey(JavaExecutable e) {
		return e.declaringClass().name() + ':' + e.returnType().name();
	}

	private static int fixedArityCost(List<? extends JavaType> params, int argc, ArgumentCost cost) {
		if (params.size() != argc) {
			return NO_MATCH;
		}
		int total = 0;
		for (int i = 0; i < argc; i++) {
			int c = cost.cost(i, params.get(i));
			if (c == NO_MATCH) {
				return NO_MATCH;
			}
			total += c;
		}
		return total;
	}

	private static int varargsCost(List<? extends JavaType> params, int argc, ArgumentCost cost) {
		int fixed = params.size() - 1;
		if (argc < fixed) {
			return NO_MATCH;
		}
		int total = COST_VARARGS;
		for (int i = 0; i < fixed; i++) {
			int c = cost.cost(i, params.get(i));
			if (c == NO_MATCH) {
				return NO_MATCH;
			}
			total += c;
		}
		JavaType component = params.get(fixed).componentType();
		if (component == null) {
			return NO_MATCH;
		}
		for (int i = fixed; i < argc; i++) {
			int c = cost.cost(i, component);
			if (c == NO_MATCH) {
				return NO_MATCH;
			}
			total += c;
		}
		return total;
	}

	// The tie-break key: the parameter-type names, with a "*" that keeps a packed varargs
	// interpretation distinct from the same method's fixed-arity one. Built only on a
	// cost tie.
	private static String signatureOf(Overload overload) {
		StringBuilder sb = new StringBuilder();
		for (JavaType p : overload.executable().parameterTypes()) {
			sb.append(p.name()).append(',');
		}
		return overload.packed() ? sb.append('*').toString() : sb.toString();
	}

	/**
	 * Whether two executables are the same member: one declaring class, name and
	 * parameter list (and return type -- a covariant variant is another member).
	 * @param a one executable
	 * @param b the other
	 * @return true when they are the same member
	 */
	public static boolean sameMember(JavaExecutable a, JavaExecutable b) {
		if (a == b) {
			return true;
		}
		if (!a.name().equals(b.name()) || !a.declaringClass().name().equals(b.declaringClass().name())
				|| !a.returnType().name().equals(b.returnType().name())) {
			return false;
		}
		List<? extends JavaType> pa = a.parameterTypes();
		List<? extends JavaType> pb = b.parameterTypes();
		if (pa.size() != pb.size()) {
			return false;
		}
		for (int i = 0; i < pa.size(); i++) {
			if (!pa.get(i).name().equals(pb.get(i).name())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a Lisp value of this kind is a {@code java:call} receiver: every kind but
	 * {@code nil} (Java's {@code null}) and a function (no object of its own). Such a
	 * value is called as the object it converts to for an {@code Object} parameter, so
	 * {@code (java:call "abc" "codePointAt" 0)} calls {@code String.codePointAt} --
	 * {@link #receiverClassName} names the class.
	 * @param kind the receiver's kind
	 * @return whether a value of it is called as a Java object
	 */
	public static boolean isReceiverKind(JavaKind.Lisp kind) {
		return rule(kind).receiver();
	}

	/**
	 * The class of the object a {@code java:call} receiver of this kind is called as
	 * ({@link #isReceiverKind}), when the kind fixes it: a string a {@code String}, a
	 * float a {@code Double}, a BMP character a {@code Character}, a supplementary one
	 * the {@code Integer} of its code point, a bignum a {@code BigInteger}, {@code t}
	 * {@code Boolean.TRUE} and {@code |false|} {@code Boolean.FALSE} -- what each
	 * converts to for an {@code Object} parameter. An integer becomes the narrowest box
	 * that holds it, {@code Integer} or {@code Long}, so its class is the value's, not
	 * the kind's.
	 * @param kind the receiver's kind
	 * @return the class name, or {@code null} for an integer and a kind that is no
	 * receiver
	 */
	public static @Nullable String receiverClassName(JavaKind.Lisp kind) {
		return rule(kind).receiverClass();
	}

	/**
	 * The box class a checked conversion's refusal names for a value of {@code kind}.
	 * @param kind the argument kind
	 * @return the box class name ({@code "null"} for {@code nil})
	 */
	public static String checkedBoxName(JavaKind.Lisp kind) {
		return rule(kind).checkedBoxName();
	}

	/**
	 * The conversion cost of a value of {@code kind} where {@code target} is expected --
	 * THE cost table, read from {@link #rule(JavaKind.Lisp)}. Pure: it reads no value, so
	 * selection over kinds needs none.
	 * <p>
	 * The table is the oracle's reflector's (clj 1.12.6,
	 * {@code clojure.lang.Reflector#paramArgTypeMatch}, measured 2026-10-13): a fixnum
	 * matches {@code long} exactly and {@code int} by narrowing, but no floating type, no
	 * {@code char} and no {@code BigInteger}; a float matches {@code double} alone; a
	 * one-character string no {@code char}; a character no {@code int}; {@code nil} no
	 * primitive. A call with a single candidate of its arity skips this table
	 * ({@code JavaSiteResolver} resolves it, converting with the oracle's checked casts),
	 * as the oracle's compiler calls one directly.
	 * @param kind the argument kind
	 * @param target the parameter type
	 * @param lookup where the boxed types and {@code String} are found
	 * @return the cost, or {@link #NO_MATCH}
	 */
	public static int kindCost(JavaKind kind, JavaType target, JavaClassLookup lookup) {
		if (kind instanceof JavaType host) { // a host object's exact class
			if (!target.isAssignableFrom(host)) {
				return NO_MATCH;
			}
			return target == host ? COST_EXACT : COST_WIDEN;
		}
		JavaKind.Lisp lisp = (JavaKind.Lisp) kind;
		String name = target.name();
		if (lisp == JavaKind.Lisp.NIL) {
			// nil carries no type, so any reference target ties; no primitive takes it,
			// not even boolean (the oracle's compiler unboxes it to a
			// NullPointerException).
			return target.isPrimitive() ? NO_MATCH : COST_BOXED;
		}
		if (lisp == JavaKind.Lisp.FUNCTION) {
			// A Lisp callable passed where an interface is expected is auto-wrapped in a
			// proxy -- a functional interface first, as a Java lambda; where an Object is
			// expected it is the object a function is
			// (JavaImplementations.functionObject).
			return OBJECT.equals(name) ? COST_FUNCTION_OBJECT
					: CALLABLE.equals(name) ? COST_FUNCTION_CALLABLE
							: !target.isInterface() ? NO_MATCH : JavaInterfaceMethods.isFunctionalInterface(target)
									? COST_PROXY : COST_PROXY_NOT_FUNCTIONAL;
		}
		KindRule r = rule(lisp);
		if (name.equals(r.exact())) {
			return COST_EXACT;
		}
		if (r.widen().contains(name)) {
			return COST_WIDEN;
		}
		if (r.narrow().contains(name)) {
			return COST_NARROW;
		}
		for (String box : r.boxes()) {
			if (assignableFrom(target, box, lookup)) {
				return COST_BOXED;
			}
		}
		return NO_MATCH;
	}

	private static boolean assignableFrom(JavaType target, String className, JavaClassLookup lookup) {
		if (target.isPrimitive()) {
			return false;
		}
		JavaType type = lookup.find(className);
		return type != null && target.isAssignableFrom(type);
	}

	// --- shared scalar and checked conversions ---

	/**
	 * Boxes a fixnum to the narrowest Java integer box that holds it ({@code Integer} or
	 * {@code Long}), as Common Lisp fixnums and {@code java:call} receivers do.
	 * @param v the integer value
	 * @return an {@link Integer} when it fits, else a {@link Long}
	 */
	public static Object narrowestBox(long v) {
		return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? (Object) (int) v : (Object) v;
	}

	/**
	 * Converts a fixnum to a parameter type {@link #kindCost} or {@link #returnedCost}
	 * accepted.
	 * @param v the integer value
	 * @param target the target parameter class
	 * @return the converted Java value
	 */
	public static Object convertLong(long v, Class<?> target) {
		if (target == int.class || target == Integer.class) {
			return (int) v;
		}
		if (target == long.class || target == Long.class) {
			return v;
		}
		if (target == double.class || target == Double.class) {
			return (double) v;
		}
		if (target == float.class || target == Float.class) {
			return (float) v;
		}
		if (target == short.class || target == Short.class) {
			return (short) v;
		}
		if (target == byte.class || target == Byte.class) {
			return (byte) v;
		}
		if (target == BigInteger.class) {
			return BigInteger.valueOf(v);
		}
		return narrowestBox(v);
	}

	/**
	 * Converts a double to a parameter type {@link #kindCost} or {@link #returnedCost}
	 * accepted.
	 * @param v the double value
	 * @param target the target parameter class
	 * @return the converted Java value
	 */
	public static Object convertDouble(double v, Class<?> target) {
		return target == float.class || target == Float.class ? (Object) (float) v : (Object) v;
	}

	/**
	 * Converts a string to a parameter type {@link #kindCost} or {@link #returnedCost}
	 * accepted.
	 * @param s the string value
	 * @param target the target parameter class
	 * @return the string, or its first {@code char} when a {@code char} or
	 * {@code Character} is expected
	 */
	public static Object convertString(String s, Class<?> target) {
		return target.isAssignableFrom(String.class) ? s : (Object) s.charAt(0);
	}

	/**
	 * Converts a character code point to a parameter type {@link #kindCost} or
	 * {@link #returnedCost} accepted.
	 * @param codePoint the character's Unicode code point
	 * @param target the target parameter class
	 * @return the {@link Character} for a BMP character where one fits, else the code
	 * point converted as a fixnum ({@link #convertLong})
	 */
	public static Object convertChar(int codePoint, Class<?> target) {
		return Character.isBmpCodePoint(codePoint)
				&& (target == char.class || target == Character.class || target.isAssignableFrom(Character.class))
						? (Object) (char) codePoint : convertLong(codePoint, target);
	}

	/**
	 * The class a checked conversion casts to for a parameter type: the box for a
	 * primitive (as the oracle's {@code emitUnboxArg} casts a primitive target through
	 * its box), the target's own name otherwise.
	 * @param target the parameter type name
	 * @return the target class name in the {@link ClassCastException}
	 */
	public static String checkedCastTargetName(String target) {
		return switch (target) {
			case "boolean" -> "java.lang.Boolean";
			case "char" -> "java.lang.Character";
			case "byte", "short", "int", "long", "float", "double" -> "java.lang.Number";
			default -> target;
		};
	}

	/**
	 * The unboxing method a checked conversion of {@code nil} to a primitive parameter
	 * names in its {@link NullPointerException}, or {@code null} when the target is not a
	 * primitive.
	 * @param target the parameter type name
	 * @return the unboxing method descriptor in the oracle's words, or {@code null}
	 */
	public static @Nullable String checkedNullMethod(String target) {
		return switch (target) {
			case "boolean" -> "java.lang.Boolean.booleanValue()";
			case "char", "int" -> "java.lang.Character.charValue()";
			case "byte", "short", "long", "float", "double" -> "java.lang.Number.doubleValue()";
			default -> null;
		};
	}

	/**
	 * The {@link ClassCastException} of a checked conversion, in the oracle's words
	 * without its module tail.
	 * @param from the source class name
	 * @param to the target class name
	 * @return the exception
	 */
	public static ClassCastException castFailure(String from, String to) {
		return new ClassCastException("class " + from + " cannot be cast to class " + to);
	}

	/**
	 * The {@link NullPointerException} of a checked conversion unboxing {@code nil}, in
	 * the oracle's words (its compiler's local is named {@code x}).
	 * @param method the unboxing method ({@link #checkedNullMethod})
	 * @return the exception
	 */
	public static NullPointerException nullUnboxing(String method) {
		return new NullPointerException("Cannot invoke \"" + method + "\" because \"x\" is null");
	}

	/**
	 * The {@link IllegalArgumentException} of a checked conversion past a primitive
	 * range, in the oracle's words.
	 * @param name the primitive type name
	 * @param value the out-of-range value
	 * @return the exception
	 */
	public static IllegalArgumentException outOfRange(String name, Object value) {
		return new IllegalArgumentException("Value out of range for " + name + ": " + value);
	}

	/**
	 * Converts {@code nil} for a resolved site's parameter with the oracle's checked
	 * casts: {@code null} for a reference, {@link #nullUnboxing} for a primitive.
	 * @param target the parameter class
	 * @return {@code null}
	 */
	public static @Nullable Object convertCheckedNil(Class<?> target) {
		String method = checkedNullMethod(target.getName());
		if (method != null) {
			throw nullUnboxing(method);
		}
		return null;
	}

	/**
	 * Converts a boolean ({@code t} or {@code |false|}) for a resolved site's parameter
	 * with the oracle's checked casts.
	 * @param boxed {@link Boolean#TRUE} or {@link Boolean#FALSE}
	 * @param target the parameter class
	 * @return the converted value
	 */
	public static Object convertCheckedBoolean(Boolean boxed, Class<?> target) {
		if (target == boolean.class) {
			return boxed.booleanValue();
		}
		if (target.isInstance(boxed)) {
			return boxed;
		}
		throw castFailure("java.lang.Boolean", checkedCastTargetName(target.getName()));
	}

	/**
	 * Converts a fixnum for a resolved site's parameter with the oracle's checked casts.
	 * @param v the integer value
	 * @param target the parameter class
	 * @return the converted value
	 */
	public static Object convertCheckedLong(long v, Class<?> target) {
		if (target == long.class || target == Long.class) {
			return v;
		}
		if (target == int.class) {
			return Math.toIntExact(v);
		}
		if (target == short.class) {
			if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
				throw outOfRange("short", v);
			}
			return (short) v;
		}
		if (target == byte.class) {
			if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
				throw outOfRange("byte", v);
			}
			return (byte) v;
		}
		if (target == float.class) {
			return (float) v;
		}
		if (target == double.class) {
			return (double) v;
		}
		Long boxed = v;
		if (!target.isPrimitive() && target.isInstance(boxed)) {
			return narrowestBox(v);
		}
		throw castFailure("java.lang.Long", checkedCastTargetName(target.getName()));
	}

	/**
	 * Converts a bignum for a resolved site's parameter with the oracle's checked casts.
	 * @param v the bignum value
	 * @param target the parameter class
	 * @return the converted value
	 */
	public static Object convertCheckedBigInteger(BigInteger v, Class<?> target) {
		if (target.isInstance(v)) {
			return v;
		}
		if (target == double.class) {
			return v.doubleValue();
		}
		if (target == float.class) {
			double d = v.doubleValue();
			if (d < -Float.MAX_VALUE || d > Float.MAX_VALUE) {
				throw outOfRange("float", v);
			}
			return (float) d;
		}
		if (target == long.class) {
			return convertCheckedBigIntegerLong(v);
		}
		if (target == int.class) {
			return Math.toIntExact(convertCheckedBigIntegerLong(v));
		}
		if (target == short.class || target == byte.class) {
			return convertCheckedLong(convertCheckedBigIntegerLong(v), target);
		}
		throw castFailure(BIG_INTEGER, checkedCastTargetName(target.getName()));
	}

	private static long convertCheckedBigIntegerLong(BigInteger v) {
		if (v.bitLength() < 64) {
			return v.longValue();
		}
		throw outOfRange("long", v);
	}

	/**
	 * Converts a double for a resolved site's parameter with the oracle's checked casts.
	 * @param v the double value
	 * @param target the parameter class
	 * @return the converted value
	 */
	public static Object convertCheckedDouble(double v, Class<?> target) {
		if (target == double.class) {
			return v;
		}
		if (target == float.class) {
			if (v < -Float.MAX_VALUE || v > Float.MAX_VALUE) {
				throw outOfRange("float", v);
			}
			return (float) v;
		}
		if (target == long.class) {
			return convertCheckedDoubleLong(v);
		}
		if (target == int.class) {
			return Math.toIntExact(convertCheckedDoubleLong(v));
		}
		if (target == short.class || target == byte.class) {
			return convertCheckedLong(convertCheckedDoubleLong(v), target);
		}
		Double boxed = v;
		if (!target.isPrimitive() && target.isInstance(boxed)) {
			return boxed;
		}
		throw castFailure("java.lang.Double", checkedCastTargetName(target.getName()));
	}

	private static long convertCheckedDoubleLong(double v) {
		if (v < Long.MIN_VALUE || v > Long.MAX_VALUE) {
			throw outOfRange("long", v);
		}
		return (long) v;
	}

	/**
	 * Converts a string for a resolved site's parameter with the oracle's checked casts.
	 * @param text the string value
	 * @param target the parameter class
	 * @return the string
	 */
	public static Object convertCheckedString(String text, Class<?> target) {
		if (target.isInstance(text)) {
			return text;
		}
		throw castFailure("java.lang.String", checkedCastTargetName(target.getName()));
	}

	/**
	 * Converts a character code point for a resolved site's parameter with the oracle's
	 * checked casts.
	 * @param codePoint the character's Unicode code point
	 * @param target the parameter class
	 * @return the converted value
	 */
	public static Object convertCheckedChar(int codePoint, Class<?> target) {
		if (Character.isBmpCodePoint(codePoint)) {
			Character boxed = (char) codePoint;
			if (target == char.class || target.isInstance(boxed)) {
				return boxed;
			}
			throw castFailure("java.lang.Character", checkedCastTargetName(target.getName()));
		}
		return convertCheckedLong(codePoint, target);
	}

	/**
	 * Converts a non-view host object for a resolved site's parameter with the oracle's
	 * checked casts.
	 * @param ref the host object
	 * @param target the parameter class
	 * @return the converted value
	 */
	public static Object convertCheckedHost(Object ref, Class<?> target) {
		if (target.isInstance(ref)) {
			return ref;
		}
		if (ref instanceof BigInteger big) {
			return convertCheckedBigInteger(big, numericTarget(target, ref));
		}
		if (ref instanceof Long || ref instanceof Integer || ref instanceof Short || ref instanceof Byte) {
			return convertCheckedLong(((Number) ref).longValue(), numericTarget(target, ref));
		}
		if (ref instanceof Number number) {
			return convertCheckedDouble(number.doubleValue(), numericTarget(target, ref));
		}
		if (ref instanceof Boolean boxed && target == boolean.class) {
			return boxed.booleanValue();
		}
		if (ref instanceof Character boxed && target == char.class) {
			return boxed.charValue();
		}
		throw castFailure(ref.getClass().getName(), checkedCastTargetName(target.getName()));
	}

	private static Class<?> numericTarget(Class<?> target, Object ref) {
		if (target.isPrimitive() && target != boolean.class && target != char.class && target != void.class) {
			return target;
		}
		throw castFailure(ref.getClass().getName(), checkedCastTargetName(target.getName()));
	}

	/**
	 * The message of a {@code java:} site's refusal when a class cannot link because of a
	 * missing dependency class.
	 * @param className the site's class name
	 * @param ex the linkage failure
	 * @return e.g. {@code Cannot link class C: missing class M}
	 */
	public static String unlinkableMessage(String className, NoClassDefFoundError ex) {
		String detail = ex.getMessage();
		String missing = detail != null ? detail.replace('/', '.') : String.valueOf(ex);
		return "Cannot link class " + className + ": missing class " + missing;
	}

	/**
	 * The missing class an {@link ExceptionInInitializerError}'s cause chain holds, or
	 * {@code null}.
	 * @param ex the initializer failure
	 * @return the {@link NoClassDefFoundError} in its cause chain, or {@code null}
	 */
	public static @Nullable NoClassDefFoundError unlinkableCause(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			if (current instanceof NoClassDefFoundError missing) {
				return missing;
			}
			current = current.getCause();
		}
		return null;
	}

	/**
	 * Splits a member designator into its name and its parameter tag: {@code "max"},
	 * {@code "max(long,long)"}, {@code "max(long,_)"}, and for {@code java:new}
	 * {@code "java.lang.StringBuilder(int)"}. A tag type is a primitive, a binary class
	 * name ({@code java.util.Map$Entry}), a name without a package, which means
	 * {@code java.lang} ({@code String}), any of those followed by {@code []} per array
	 * dimension or by {@code ...}, a {@link Class#getName()} array spelling ({@code [I}),
	 * or {@code _}, which matches any type. A tag whose entries are {@code ^C} (and
	 * {@code _}) is no tag but the argument hints ({@code "submit(^java.lang.Runnable)"},
	 * {@link #filterByHints}): each {@code C} a binary class name.
	 * @param designator the designator as written
	 * @return the name and the tag, its types in {@link Class#getName()} spelling, or the
	 * hints
	 * @throws IllegalArgumentException when the tag is malformed
	 */
	public static Member parseMember(String designator) {
		int open = designator.indexOf('(');
		if (open < 0) {
			if (designator.indexOf(')') >= 0) {
				throw new IllegalArgumentException("malformed parameter tag in \"" + designator + "\"");
			}
			return new Member(designator, null);
		}
		if (!designator.endsWith(")") || open == 0 || designator.indexOf('(', open + 1) >= 0
				|| designator.indexOf(')') != designator.length() - 1) {
			throw new IllegalArgumentException("malformed parameter tag in \"" + designator + "\"");
		}
		String name = designator.substring(0, open).strip();
		String inside = designator.substring(open + 1, designator.length() - 1).strip();
		List<String> types = new ArrayList<>();
		List<String> hints = new ArrayList<>();
		boolean tagged = false;
		if (!inside.isEmpty()) {
			for (String part : inside.split(",", -1)) {
				String type = part.strip();
				if (type.isEmpty()) {
					throw new IllegalArgumentException("malformed parameter tag in \"" + designator + "\"");
				}
				if (type.startsWith(HINT)) {
					String hint = type.substring(HINT.length()).strip();
					if (hint.isEmpty() || WILDCARD.equals(hint)) {
						throw new IllegalArgumentException("malformed parameter tag in \"" + designator + "\"");
					}
					hints.add(hint);
					continue;
				}
				String tagType = tagTypeName(type);
				tagged |= !WILDCARD.equals(tagType);
				types.add(tagType);
				hints.add(WILDCARD);
			}
		}
		if (types.size() == hints.size()) {
			return new Member(name, List.copyOf(types));
		}
		if (tagged) {
			// a hint designator mixes no tag type in: each entry a hint or `_`
			throw new IllegalArgumentException("malformed parameter tag in \"" + designator + "\"");
		}
		return new Member(name, null, List.copyOf(hints));
	}

	/**
	 * A tag type in {@link Class#getName()} spelling.
	 * @param type the type as written in a tag
	 * @return the canonical spelling
	 */
	static String tagTypeName(String type) {
		if (WILDCARD.equals(type) || type.startsWith("[")) {
			return type;
		}
		int dimensions = 0;
		String base = type;
		while (true) {
			if (base.endsWith("[]")) {
				base = base.substring(0, base.length() - 2).strip();
				dimensions++;
			}
			else if (base.endsWith("...")) {
				base = base.substring(0, base.length() - 3).strip();
				dimensions++;
			}
			else {
				break;
			}
		}
		String descriptor = primitiveDescriptor(base);
		if (descriptor == null && base.indexOf('.') < 0) {
			base = "java.lang." + base;
		}
		if (dimensions == 0) {
			return base;
		}
		return "[".repeat(dimensions) + (descriptor != null ? descriptor : "L" + base + ";");
	}

	private static @Nullable String primitiveDescriptor(String name) {
		return switch (name) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			default -> null;
		};
	}

	/**
	 * The static methods among the candidates: what {@code java:static} chooses from. An
	 * instance method of the name is no candidate -- called without a receiver it could
	 * only fail -- so {@code (java:static "java.lang.String" "length")} finds no method,
	 * and a static overload is never passed over for an instance one.
	 * @param candidates the methods of a name
	 * @return the static ones, in the same order
	 */
	public static <E extends JavaExecutable> List<E> staticMethods(List<E> candidates) {
		List<E> statics = new ArrayList<>();
		for (E candidate : candidates) {
			if (candidate.isStatic()) {
				statics.add(candidate);
			}
		}
		return statics.size() == candidates.size() ? candidates : statics;
	}

	/**
	 * The candidates a parameter tag leaves: one parameter per tag type, each the type
	 * the tag names or anything for {@link #WILDCARD}.
	 * @param candidates the methods or constructors
	 * @param tag the tag types in {@link Class#getName()} spelling, or {@code null}
	 * @return the candidates that match
	 */
	public static <E extends JavaExecutable> List<E> filterByTag(List<E> candidates, @Nullable List<String> tag) {
		if (tag == null) {
			return candidates;
		}
		List<E> matching = new ArrayList<>();
		for (E candidate : candidates) {
			List<? extends JavaType> params = candidate.parameterTypes();
			if (params.size() != tag.size()) {
				continue;
			}
			boolean all = true;
			for (int i = 0; i < params.size(); i++) {
				String type = tag.get(i);
				if (!WILDCARD.equals(type) && !type.equals(params.get(i).name())) {
					all = false;
					break;
				}
			}
			if (all) {
				matching.add(candidate);
			}
		}
		return matching;
	}

	/**
	 * The candidates the argument hints leave -- the oracle's compiler choosing a member
	 * by the classes its arguments' {@code :tag}s name: a candidate taking one parameter
	 * per hint, each hinted reference parameter one the hint's class is assignable to. A
	 * primitive parameter is never dropped (the oracle's {@code paramArgTypeMatch} takes
	 * a box's hint there; the argument's kind decides the rest), and a class the lookup
	 * does not find hints nothing. When no candidate is left, every candidate: the oracle
	 * then calls by reflection, which reads no hint.
	 * @param candidates the methods or constructors
	 * @param hints the hinted classes ({@link #WILDCARD} for an argument with none), or
	 * {@code null}
	 * @param lookup the lookup the hinted classes are found in
	 * @return the candidates that match, or all of them when none does
	 */
	public static <E extends JavaExecutable> List<E> filterByHints(List<E> candidates, @Nullable List<String> hints,
			JavaClassLookup lookup) {
		if (hints == null) {
			return candidates;
		}
		List<@Nullable JavaType> classes = new ArrayList<>();
		for (String hint : hints) {
			classes.add(WILDCARD.equals(hint) ? null : lookup.find(hint));
		}
		List<E> matching = new ArrayList<>();
		for (E candidate : candidates) {
			List<? extends JavaType> params = candidate.parameterTypes();
			if (params.size() != classes.size()) {
				continue;
			}
			boolean all = true;
			for (int i = 0; i < params.size(); i++) {
				JavaType hint = classes.get(i);
				JavaType param = params.get(i);
				if (hint != null && !param.isPrimitive() && !param.isAssignableFrom(hint)) {
					all = false;
					break;
				}
			}
			if (all) {
				matching.add(candidate);
			}
		}
		return matching.isEmpty() ? candidates : matching;
	}

	/**
	 * The designator that names exactly one member: its name and every parameter type.
	 * @param executable the method or constructor
	 * @param name the name to write ({@code java:new} writes its class name)
	 * @return the fully tagged designator
	 */
	public static String fullDesignator(JavaExecutable executable, String name) {
		List<String> types = new ArrayList<>();
		for (JavaType p : executable.parameterTypes()) {
			types.add(p.name());
		}
		return new Member(name, types).designator();
	}

}
