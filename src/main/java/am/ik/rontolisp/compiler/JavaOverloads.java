package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * THE {@code java:} overload rule: the conversion cost of an argument kind to a parameter
 * type ({@link #kindCost}), the selection of the cheapest overload ({@link #select}), and
 * the parameter tags that narrow the candidates ({@link #parseMember}). The interpreter
 * ({@code eval.JavaInterop}) selects through this class at run time and the site resolver
 * ({@link JavaSiteResolver}) at compile time, so a call chooses the same member on every
 * backend. The compiled program's bridge ({@code codegen.jvm.JavaBridgeTemplate}) carries
 * a hand copy -- it must stand alone in the output -- which
 * {@code JavaBridgeTemplateParityTest} pins to this one; change the two together.
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

	/** A Lisp callable adapted to an interface by a proxy. */
	public static final int COST_PROXY = 8;

	/** The flat penalty for packing a varargs tail: a fixed-arity overload wins. */
	public static final int COST_VARARGS = 10;

	/** This argument cannot become this type. */
	public static final int NO_MATCH = -1;

	/** A tag type that matches any parameter type. */
	public static final String WILDCARD = "_";

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
	 * A member designator: the name, and the parameter types a tag fixes.
	 *
	 * @param name the method name, or for {@code java:new} the class name
	 * @param tag the tag's types in {@link Class#getName()} spelling ({@link #WILDCARD}
	 * for {@code _}), or {@code null} for an untagged designator
	 */
	public record Member(String name, @Nullable List<String> tag) {

		/**
		 * @return the designator in the spelling {@link #parseMember} reads back
		 */
		public String designator() {
			List<String> types = this.tag;
			return types == null ? this.name : this.name + "(" + String.join(",", types) + ")";
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
	 * The conversion cost of a value of {@code kind} where {@code target} is expected --
	 * THE cost table. Pure: it reads no value, so selection over kinds needs none.
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
		String name = target.name();
		return switch ((JavaKind.Lisp) kind) {
			case NIL -> {
				if ("boolean".equals(name)) {
					yield COST_EXACT;
				}
				// nil carries no type, so any reference target ties
				yield target.isPrimitive() ? NO_MATCH : COST_BOXED;
			}
			case T -> {
				if ("boolean".equals(name)) {
					yield COST_EXACT;
				}
				if ("java.lang.Boolean".equals(name)) {
					yield COST_WIDEN;
				}
				yield assignableFrom(target, "java.lang.Boolean", lookup) ? COST_BOXED : NO_MATCH;
			}
			case INTEGER -> integerCost(target, name, lookup);
			case FLOAT -> floatCost(target, name, lookup);
			case STRING, STRING_1 -> {
				if (assignableFrom(target, "java.lang.String", lookup)) {
					yield "java.lang.String".equals(name) ? COST_EXACT : COST_BOXED;
				}
				// Only a one-character string narrows to a char.
				yield kind == JavaKind.Lisp.STRING_1 && ("char".equals(name) || "java.lang.Character".equals(name))
						? COST_NARROW : NO_MATCH;
			}
			case CHAR, SUPPLEMENTARY_CHAR -> {
				// A supplementary code point cannot fit a single Java char, so the
				// char/Character overload is refused for it rather than truncated.
				if (kind == JavaKind.Lisp.CHAR && ("char".equals(name) || "java.lang.Character".equals(name)
						|| assignableFrom(target, "java.lang.Character", lookup))) {
					yield "char".equals(name) ? COST_EXACT
							: ("java.lang.Character".equals(name) ? COST_WIDEN : COST_BOXED);
				}
				// int / Integer accept the raw code point (supplementary values too).
				if ("int".equals(name) || "java.lang.Integer".equals(name)) {
					yield COST_WIDEN;
				}
				yield assignableFrom(target, "java.lang.Integer", lookup) ? COST_BOXED : NO_MATCH;
			}
			// A Lisp callable passed where an interface is expected is auto-wrapped in a
			// proxy.
			case FUNCTION -> target.isInterface() ? COST_PROXY : NO_MATCH;
		};
	}

	private static int integerCost(JavaType target, String name, JavaClassLookup lookup) {
		return switch (name) {
			case "int" -> COST_EXACT;
			case "java.lang.Integer", "long", "java.lang.Long" -> COST_WIDEN;
			case "double", "java.lang.Double", "float", "java.lang.Float" -> COST_CONVERT;
			case "short", "java.lang.Short", "byte", "java.lang.Byte" -> COST_NARROW;
			default ->
				assignableFrom(target, "java.lang.Long", lookup) || assignableFrom(target, "java.lang.Integer", lookup)
						? COST_BOXED : NO_MATCH;
		};
	}

	private static int floatCost(JavaType target, String name, JavaClassLookup lookup) {
		return switch (name) {
			case "double" -> COST_EXACT;
			case "java.lang.Double" -> COST_WIDEN;
			case "float", "java.lang.Float" -> COST_NARROW;
			default -> assignableFrom(target, "java.lang.Double", lookup) ? COST_BOXED : NO_MATCH;
		};
	}

	private static boolean assignableFrom(JavaType target, String className, JavaClassLookup lookup) {
		if (target.isPrimitive()) {
			return false;
		}
		JavaType type = lookup.find(className);
		return type != null && target.isAssignableFrom(type);
	}

	/**
	 * Splits a member designator into its name and its parameter tag: {@code "max"},
	 * {@code "max(long,long)"}, {@code "max(long,_)"}, and for {@code java:new}
	 * {@code "java.lang.StringBuilder(int)"}. A tag type is a primitive, a binary class
	 * name ({@code java.util.Map$Entry}), a name without a package, which means
	 * {@code java.lang} ({@code String}), any of those followed by {@code []} per array
	 * dimension or by {@code ...}, a {@link Class#getName()} array spelling ({@code [I}),
	 * or {@code _}, which matches any type.
	 * @param designator the designator as written
	 * @return the name and the tag, its types in {@link Class#getName()} spelling
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
		if (!inside.isEmpty()) {
			for (String part : inside.split(",", -1)) {
				String type = part.strip();
				if (type.isEmpty()) {
					throw new IllegalArgumentException("malformed parameter tag in \"" + designator + "\"");
				}
				types.add(tagTypeName(type));
			}
		}
		return new Member(name, List.copyOf(types));
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
