package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a {@code java:} call site before it runs -- the one resolution model the
 * interpreter and the JVM compiler share, after Clojure's: a site whose receiver class
 * and argument kinds are known from the program text calls exactly one member, chosen by
 * the run-time rule ({@link JavaOverloads}) over what is known; every other site is left
 * to run time, where that same rule sees the receiver's class and the argument kinds.
 * <p>
 * What is known about a subform, all of it LEXICAL ({@link #typeOf}): a literal's kind; a
 * {@code (java:new "C" ...)} is exactly a {@code C}; a resolved member's value is what
 * its declared type unmarshals to ({@link JavaStaticType#ofDeclared}); a
 * {@code (the (java:object "C") x)} is what a {@code C} unmarshals to
 * ({@code (java:object "C" :exact)}: what {@code java:new} answers); and a typed variable
 * -- declared, inferred from its {@code let} initializer, proclaimed -- which
 * {@link JavaDeclarations} has already turned into that {@code the} at each site. A
 * declared type is trusted: a false one is a deterministic error where the value meets
 * the member, never a different member.
 * <p>
 * The one place a resolved site chooses differently from run-time resolution: a receiver
 * typed by an upper bound resolves among the bound's methods, so a run-time class that
 * adds a public overload of the same name is not consulted
 * ({@code Collection.remove(Object)} rather than {@code ArrayList.remove(int)}), exactly
 * as a Java or a type-hinted Clojure call would. Arguments never do: a site resolves only
 * when EVERY kind its arguments can have selects the same member.
 * <p>
 * The result is a pure function of the site form and the lookup, so the interpreter
 * (reflection) and the compiler (class files) resolve the same site the same way whenever
 * the two lookups describe the same classes.
 */
public final class JavaSiteResolver {

	/** The most argument-kind combinations a site is checked over. */
	private static final int COMBINATION_LIMIT = 256;

	private final JavaClassLookup lookup;

	/**
	 * @param lookup where classes are described
	 */
	public JavaSiteResolver(JavaClassLookup lookup) {
		this.lookup = lookup;
	}

	/**
	 * The operator of a {@code java:} site.
	 * @param form a form
	 * @return the operator, or {@code null} when the form is not a {@code java:new},
	 * {@code java:call}, {@code java:static} or {@code java:field} call
	 */
	public static JavaSite.@Nullable Operator operatorOf(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		return switch (head.name()) {
			case LispNames.JAVA_NEW_QUALIFIED -> JavaSite.Operator.NEW;
			case LispNames.JAVA_CALL_QUALIFIED -> JavaSite.Operator.CALL;
			case LispNames.JAVA_STATIC_QUALIFIED -> JavaSite.Operator.STATIC;
			case LispNames.JAVA_FIELD_QUALIFIED -> JavaSite.Operator.FIELD;
			default -> null;
		};
	}

	/**
	 * The class a {@code (java:object "C")} or {@code (java:object "C" :exact)} type
	 * specifier names.
	 * @param spec a type specifier
	 * @return the class name, or {@code null} when the specifier is not one
	 */
	public static @Nullable String javaObjectClass(LispVal spec) {
		if (spec instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.JAVA_OBJECT_QUALIFIED.equals(head.name()) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispString className
				&& (rest.cdr() instanceof LispNil || isExactMarker(rest.cdr()))) {
			return className.value();
		}
		return null;
	}

	private static boolean isExactMarker(LispVal tail) {
		return tail instanceof LispCons marker && marker.car() instanceof LispSymbol keyword
				&& EXACT.equals(keyword.name()) && marker.cdr() instanceof LispNil;
	}

	/** The marker of {@code (java:object "C" :exact)}. */
	private static final String EXACT = ":EXACT";

	/**
	 * The spellings {@link #specOf} chooses among for a set of Lisp kinds: each is what a
	 * member declared with that type answers ({@link JavaStaticType#ofDeclared}).
	 */
	private static final List<String> KIND_SPELLINGS = List.of("void", "int", "double", "char", "java.lang.Long",
			"java.lang.Double", "java.lang.Character", "boolean", "java.lang.String");

	/**
	 * What a {@code java:object} type specifier says about a value.
	 * {@code (java:object "C")} is what a member declared to answer a {@code C} answers
	 * ({@link JavaStaticType#ofDeclared}: a subclass of {@code C} or {@code nil}, a
	 * primitive's Lisp kind, ...); {@code (java:object "C" :exact)} is what
	 * {@code (java:new "C" ...)} answers ({@link JavaStaticType#ofConstructed}) -- and of
	 * an interface, whose instances are never exactly of it, what a {@code java:reify} or
	 * {@code java:proxy} of it makes ({@link JavaImplementationType}).
	 * @param spec a type specifier
	 * @return its static type, or {@code null} when the specifier is not a
	 * {@code java:object} one
	 */
	public @Nullable JavaStaticType typeOfSpec(LispVal spec) {
		String className = javaObjectClass(spec);
		if (className == null) {
			return null;
		}
		JavaType type = this.lookup.find(className);
		if (type == null) {
			return JavaStaticType.UNKNOWN;
		}
		boolean exact = ((LispCons) ((LispCons) spec).cdr()).cdr() instanceof LispCons;
		if (exact && type.isInterface()) {
			// No object's class is exactly an interface: (java:object "I" :exact) is the
			// object a java:reify / java:proxy of I makes (JavaImplementationType).
			return type.isLinkable() ? kinds(this.lookup.implementationOf(type)) : JavaStaticType.UNKNOWN;
		}
		return exact ? JavaStaticType.ofConstructed(type, this.lookup) : JavaStaticType.ofDeclared(type, this.lookup);
	}

	/**
	 * The {@code java:object} specifier that says the most {@link #typeOfSpec} can read
	 * back of a static type -- the same type, or the narrowest one containing it: a bound
	 * is {@code (java:object "C")}, one exact host class
	 * {@code (java:object "C" :exact)}, a set of Lisp kinds (with at most one host class
	 * that may be {@code nil}) the smallest declared type whose value covers the set, so
	 * {@code {integer}} is {@code (java:object "int")}. A wider type resolves fewer sites
	 * and never a different member (a site resolves only when every kind selects the same
	 * one).
	 * @param type a static type
	 * @return the specifier, or {@code null} when nothing but {@code t} covers the type
	 */
	public @Nullable LispVal specOf(JavaStaticType type) {
		if (type instanceof JavaStaticType.Bounded bounded) {
			return javaObjectSpec(bounded.type().name(), false);
		}
		if (!(type instanceof JavaStaticType.Kinds known)) {
			return null;
		}
		Set<JavaKind> kinds = known.kinds();
		List<String> candidates = new ArrayList<>();
		for (JavaKind kind : kinds) {
			if (kind instanceof JavaImplementationType implementation) {
				// What a java:reify / java:proxy of I makes: (java:object "I" :exact).
				return kinds.size() == 1 ? javaObjectSpec(implementation.iface().name(), true) : null;
			}
			if (kind instanceof JavaType host) {
				if (kinds.size() == 1) {
					return javaObjectSpec(host.name(), true);
				}
				candidates.add(host.name());
			}
		}
		candidates.addAll(KIND_SPELLINGS);
		String best = null;
		int bestSize = Integer.MAX_VALUE;
		for (String name : candidates) {
			JavaType candidate = this.lookup.find(name);
			if (candidate != null
					&& JavaStaticType.ofDeclared(candidate, this.lookup) instanceof JavaStaticType.Kinds covering
					&& covering.kinds().containsAll(kinds) && covering.kinds().size() < bestSize) {
				best = name;
				bestSize = covering.kinds().size();
			}
		}
		return best == null ? null : javaObjectSpec(best, false);
	}

	/**
	 * A fresh {@code (java:object "C")} or {@code (java:object "C" :exact)} specifier.
	 * @param className the class name
	 * @param exact whether the value is exactly a {@code C}
	 * @return the specifier
	 */
	public static LispCons javaObjectSpec(String className, boolean exact) {
		LispVal tail = exact ? new LispCons(new LispSymbol(EXACT), LispNil.INSTANCE) : LispNil.INSTANCE;
		return new LispCons(new LispSymbol(LispNames.JAVA_OBJECT_QUALIFIED),
				new LispCons(new LispString(className), tail));
	}

	/**
	 * The class the outermost {@code (the (java:object "C") ...)} around a form names --
	 * looking through any other {@code the}, as {@link #typeOf} does -- or {@code null}.
	 * @param form an argument form
	 * @return the class name, or {@code null} when no declaration types the form
	 */
	public static @Nullable String declaredClass(LispVal form) {
		LispVal current = form;
		while (current instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.THE.equals(head.name()) && cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			if (parts.size() != 3) {
				return null;
			}
			String className = javaObjectClass(parts.get(1));
			if (className != null) {
				return className;
			}
			current = parts.get(2);
		}
		return null;
	}

	/**
	 * A site as a warning names it: the operator and its literal names.
	 * @param site the site
	 * @return e.g. {@code java:call "append"}
	 */
	public static String describe(LispCons site) {
		JavaSite.Operator operator = operatorOf(site);
		List<LispVal> parts = site.isProperList() ? site.toList() : List.of(site.car());
		StringBuilder sb = new StringBuilder(
				operator == null ? site.car().print() : "java:" + operator.name().toLowerCase(java.util.Locale.ROOT));
		if (operator == JavaSite.Operator.STATIC) {
			appendPart(sb, parts, 1);
			appendPart(sb, parts, 2);
		}
		else if (operator == JavaSite.Operator.NEW) {
			appendPart(sb, parts, 1);
		}
		else {
			appendPart(sb, parts, 2);
		}
		return sb.toString();
	}

	/**
	 * The report of a site left to run time, without a position prefix (each backend adds
	 * its own).
	 * @param site the site
	 * @param resolution how it resolved
	 * @return e.g. {@code java:call "length" is resolved by reflection at run time: the
	 * receiver's class is not known}
	 */
	public static String reflectionWarning(LispCons site, JavaSite resolution) {
		return describe(site) + " is resolved by reflection at run time: " + resolution.reason();
	}

	/**
	 * The {@code java:} sites a form shows, outermost first, quoted data skipped: what
	 * {@code --warn-java-reflection} reports on.
	 * @param form a form
	 * @return the sites, each once
	 */
	public static List<LispCons> sitesIn(LispVal form) {
		List<LispCons> sites = new ArrayList<>();
		collectSites(form, sites, new java.util.IdentityHashMap<>());
		return sites;
	}

	private static void collectSites(LispVal form, List<LispCons> sites,
			java.util.IdentityHashMap<LispCons, Boolean> seen) {
		LispVal current = form;
		boolean head = true;
		while (current instanceof LispCons cons && seen.put(cons, Boolean.TRUE) == null) {
			if (head) {
				if (cons.car() instanceof LispSymbol sym && LispNames.QUOTE.equals(sym.name())) {
					return;
				}
				if (operatorOf(cons) != null) {
					sites.add(cons);
				}
				head = false;
			}
			if (cons.car() instanceof LispCons inner) {
				collectSites(inner, sites, seen);
			}
			current = cons.cdr();
		}
	}

	private static void appendPart(StringBuilder sb, List<LispVal> parts, int index) {
		if (index < parts.size() && parts.get(index) instanceof LispString s) {
			sb.append(' ').append(s.print());
		}
	}

	/**
	 * Resolves a site.
	 * @param site a {@code java:new}, {@code java:call}, {@code java:static} or
	 * {@code java:field} form
	 * @return how it resolves
	 * @throws IllegalArgumentException when the form is no such site
	 */
	public JavaSite resolve(LispCons site) {
		JavaSite.Operator operator = operatorOf(site);
		if (operator == null) {
			throw new IllegalArgumentException("not a java: site: " + site.print());
		}
		if (!site.isProperList()) {
			return JavaSite.unresolved(operator, JavaStaticType.UNKNOWN, "the form is malformed");
		}
		List<LispVal> parts = site.toList();
		try {
			return switch (operator) {
				case NEW -> resolveNew(parts);
				case STATIC -> resolveStatic(parts);
				case CALL -> resolveCall(parts);
				case FIELD -> resolveField(parts);
			};
		}
		catch (IllegalArgumentException ex) {
			// A malformed parameter tag: the run-time path raises it.
			return JavaSite.unresolved(operator, JavaStaticType.UNKNOWN, String.valueOf(ex.getMessage()));
		}
		catch (RuntimeException | LinkageError ex) {
			// A class that cannot be inspected (a missing dependency, an unreadable class
			// file): resolving early must never fail a program resolving at run time
			// would not, so the site is left to run time, which reports what it meets.
			return JavaSite.unresolved(operator, JavaStaticType.UNKNOWN, "the classes could not be inspected: " + ex);
		}
	}

	private JavaSite resolveNew(List<LispVal> parts) {
		JavaSite.Operator op = JavaSite.Operator.NEW;
		if (parts.size() < 2 || !(parts.get(1) instanceof LispString designator)) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "the class name is not a literal string");
		}
		JavaOverloads.Member member = JavaOverloads.parseMember(designator.value());
		JavaType type = this.lookup.find(member.name());
		if (type == null) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "class " + member.name() + " is not found");
		}
		JavaStaticType result = JavaStaticType.ofConstructed(type, this.lookup);
		String unlinkable = unlinkable("class ", type);
		if (unlinkable != null) {
			return JavaSite.unresolved(op, result, unlinkable);
		}
		if (type.isAbstract()) {
			// A public constructor of an abstract class makes nothing: the run-time path
			// reports what reflection reports.
			return JavaSite.unresolved(op, result,
					"class " + type.name() + " is " + (type.isInterface() ? "an interface" : "abstract"));
		}
		List<? extends JavaExecutable> candidates = JavaOverloads.filterByTag(type.constructors(), member.tag());
		return select(op, type, member, candidates, parts.subList(2, parts.size()), result);
	}

	private JavaSite resolveStatic(List<LispVal> parts) {
		JavaSite.Operator op = JavaSite.Operator.STATIC;
		if (parts.size() < 3 || !(parts.get(1) instanceof LispString className)) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "the class name is not a literal string");
		}
		if (!(parts.get(2) instanceof LispString methodName)) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "the method name is not a literal string");
		}
		JavaType type = this.lookup.find(className.value());
		if (type == null) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "class " + className.value() + " is not found");
		}
		String unlinkable = unlinkable("class ", type);
		if (unlinkable != null) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, unlinkable);
		}
		JavaOverloads.Member member = JavaOverloads.parseMember(methodName.value());
		// java:static calls a static method: an instance method of the name is no
		// candidate (the run-time rule, JavaOverloads.staticMethods).
		List<? extends JavaExecutable> candidates = JavaOverloads
			.filterByTag(JavaOverloads.staticMethods(type.methods(member.name())), member.tag());
		return select(op, type, member, candidates, parts.subList(3, parts.size()), null);
	}

	private JavaSite resolveCall(List<LispVal> parts) {
		JavaSite.Operator op = JavaSite.Operator.CALL;
		if (parts.size() < 3 || !(parts.get(2) instanceof LispString methodName)) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "the method name is not a literal string");
		}
		JavaType type = typeOf(parts.get(1)).receiverClass();
		if (type == null) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "the receiver's class is not known");
		}
		String unlinkable = unlinkable("the receiver's class ", type);
		if (unlinkable != null) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, unlinkable);
		}
		JavaOverloads.Member member = JavaOverloads.parseMember(methodName.value());
		List<? extends JavaExecutable> candidates = JavaOverloads.filterByTag(type.methods(member.name()),
				member.tag());
		return select(op, type, member, candidates, parts.subList(3, parts.size()), null);
	}

	private JavaSite resolveField(List<LispVal> parts) {
		JavaSite.Operator op = JavaSite.Operator.FIELD;
		if (parts.size() != 3 || !(parts.get(2) instanceof LispString fieldName)) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "the field name is not a literal string");
		}
		JavaType type;
		if (parts.get(1) instanceof LispString className) {
			type = this.lookup.find(className.value());
			if (type == null) {
				return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "class " + className.value() + " is not found");
			}
		}
		else {
			type = typeOf(parts.get(1)).receiverClass();
			if (type == null) {
				return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, "the object's class is not known");
			}
		}
		JavaField field = type.field(fieldName.value());
		if (field == null) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN,
					"class " + type.name() + " has no public field " + fieldName.value());
		}
		if (!type.isLinkable() || !field.declaringClass().isAccessible()) {
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN,
					"field " + type.name() + "." + fieldName.value() + " is not accessible");
		}
		if (parts.get(1) instanceof LispString && !field.isStatic()) {
			// A class name reads a static field; the run-time path says so.
			return JavaSite.unresolved(op, JavaStaticType.UNKNOWN, notStatic(type.name(), fieldName.value()));
		}
		return new JavaSite(op, type.name(), fieldName.value(), null, field, false,
				JavaStaticType.ofDeclared(field.type(), this.lookup), List.of(), null);
	}

	/**
	 * The error a {@code (java:field "C" "f")} of an instance field raises when it runs,
	 * and the reason such a site is not resolved before.
	 * @param className the class
	 * @param fieldName the field
	 * @return the text
	 */
	public static String notStatic(String className, String fieldName) {
		return "field " + className + "." + fieldName + " is not static";
	}

	/**
	 * Why a compiled program could not name a class, or {@code null} when it can
	 * ({@link JavaType#isLinkable()}).
	 */
	private static @Nullable String unlinkable(String what, JavaType type) {
		if (!type.isAccessible()) {
			return what + type.name() + " is not accessible";
		}
		if (!type.isPublic()) {
			return what + type.name() + " is not public";
		}
		return null;
	}

	/**
	 * The member every combination of the argument kinds selects, or why there is none.
	 * @param constructed the result type of a constructor site, or {@code null} for a
	 * method site (whose result is its return type)
	 */
	private JavaSite select(JavaSite.Operator op, JavaType type, JavaOverloads.Member member,
			List<? extends JavaExecutable> candidates, List<LispVal> args, @Nullable JavaStaticType constructed) {
		JavaStaticType unresolvedResult = constructed != null ? constructed : JavaStaticType.UNKNOWN;
		if (candidates.isEmpty()) {
			String what = op == JavaSite.Operator.NEW ? "constructor"
					: (op == JavaSite.Operator.STATIC ? "static method " : "method ") + member.name();
			return JavaSite.unresolved(op, unresolvedResult, "class " + type.name() + " has no public " + what
					+ (member.tag() != null ? " matching " + member.designator() : ""));
		}
		List<List<JavaKind>> kinds = new ArrayList<>();
		long combinations = 1;
		for (int i = 0; i < args.size(); i++) {
			if (!(typeOf(args.get(i)) instanceof JavaStaticType.Kinds known)) {
				return JavaSite.unresolved(op, unresolvedResult, "the type of argument " + (i + 1) + " is not known");
			}
			// A stable order: the combinations are enumerated the same way everywhere.
			List<JavaKind> sorted = new ArrayList<>(known.kinds());
			sorted.sort(java.util.Comparator.comparing(JavaSiteResolver::kindKey));
			kinds.add(sorted);
			combinations *= sorted.size();
			if (combinations > COMBINATION_LIMIT) {
				return JavaSite.unresolved(op, unresolvedResult, "the arguments can have too many kinds");
			}
		}
		JavaOverloads.Overload chosen = null;
		int[] index = new int[args.size()];
		JavaKind[] combination = new JavaKind[args.size()];
		for (long n = 0; n < combinations; n++) {
			long rest = n;
			for (int i = args.size() - 1; i >= 0; i--) {
				int size = kinds.get(i).size();
				index[i] = (int) (rest % size);
				rest /= size;
				combination[i] = kinds.get(i).get(index[i]);
			}
			JavaOverloads.Overload overload = JavaOverloads.select(candidates, args.size(),
					(i, target) -> JavaOverloads.kindCost(combination[i], target, this.lookup));
			if (overload == null) {
				return JavaSite.unresolved(op, unresolvedResult,
						"no " + (op == JavaSite.Operator.NEW ? "constructor" : "method") + " of " + type.name()
								+ " accepts arguments of kinds " + describeKinds(combination));
			}
			if (chosen == null) {
				chosen = overload;
			}
			else if (!chosen.sameAs(overload)) {
				return JavaSite.unresolved(op, unresolvedResult,
						"the member depends on the argument values (" + describeKinds(combination) + ")");
			}
		}
		if (chosen == null) {
			throw new IllegalStateException("no combination was enumerated");
		}
		JavaExecutable executable = chosen.executable();
		for (JavaType parameter : executable.parameterTypes()) {
			String unlinkable = unlinkable("the parameter type ", parameter);
			if (unlinkable != null) {
				return JavaSite.unresolved(op, unresolvedResult, unlinkable);
			}
		}
		String name = op == JavaSite.Operator.NEW ? type.name() : member.name();
		JavaStaticType result = constructed != null ? constructed
				: JavaStaticType.ofDeclared(executable.returnType(), this.lookup);
		List<JavaSite.Argument> arguments = new ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			arguments.add(new JavaSite.Argument(kinds.get(i), declaredClass(args.get(i))));
		}
		return new JavaSite(op, type.name(), JavaOverloads.fullDesignator(executable, name), executable, null,
				chosen.packed(), result, arguments, null);
	}

	private static String kindKey(JavaKind kind) {
		return kind instanceof JavaType type ? "~" + type.name() : ((JavaKind.Lisp) kind).name();
	}

	private static String describeKinds(JavaKind[] kinds) {
		List<String> names = new ArrayList<>();
		for (JavaKind kind : kinds) {
			names.add(kind instanceof JavaType type ? type.name()
					: ((JavaKind.Lisp) kind).name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
		}
		return String.join(", ", names);
	}

	/**
	 * What is known about a form's value before it is evaluated.
	 * @param form a receiver or argument form
	 * @return its static type
	 */
	public JavaStaticType typeOf(LispVal form) {
		switch (form) {
			case LispInteger ignored -> {
				return kinds(JavaKind.Lisp.INTEGER);
			}
			case LispDouble ignored -> {
				return kinds(JavaKind.Lisp.FLOAT);
			}
			case LispString s -> {
				return kinds(s.value().length() == 1 ? JavaKind.Lisp.STRING_1 : JavaKind.Lisp.STRING);
			}
			case LispChar c -> {
				return kinds(Character.isBmpCodePoint(c.codePoint()) ? JavaKind.Lisp.CHAR
						: JavaKind.Lisp.SUPPLEMENTARY_CHAR);
			}
			case LispNil ignored -> {
				return kinds(JavaKind.Lisp.NIL);
			}
			case LispTrue ignored -> {
				return kinds(JavaKind.Lisp.T);
			}
			case LispCons cons -> {
				return typeOfForm(cons);
			}
			default -> {
				return JavaStaticType.UNKNOWN;
			}
		}
	}

	private JavaStaticType typeOfForm(LispCons cons) {
		if (operatorOf(cons) != null) {
			return resolve(cons).result();
		}
		if (JavaImplementations.isImplementationForm(cons)) {
			// A java:reify / java:proxy whose interface resolves makes an object of a
			// class no program names, whose kind is the interface's implementation type.
			JavaImplementation implementation = JavaImplementations.resolve(cons, this.lookup);
			JavaType iface = implementation.iface();
			return iface == null ? JavaStaticType.UNKNOWN : kinds(this.lookup.implementationOf(iface));
		}
		if (!(cons.car() instanceof LispSymbol head) || !cons.isProperList()) {
			return JavaStaticType.UNKNOWN;
		}
		List<LispVal> parts = cons.toList();
		switch (head.name()) {
			case LispNames.QUOTE -> {
				if (parts.size() == 2) {
					LispVal quoted = parts.get(1);
					if (quoted instanceof LispInteger || quoted instanceof LispDouble || quoted instanceof LispString
							|| quoted instanceof LispChar || quoted instanceof LispNil || quoted instanceof LispTrue) {
						return typeOf(quoted);
					}
				}
				return JavaStaticType.UNKNOWN;
			}
			case LispNames.FUNCTION, LispNames.LAMBDA -> {
				return kinds(JavaKind.Lisp.FUNCTION);
			}
			case LispNames.THE -> {
				if (parts.size() != 3) {
					return JavaStaticType.UNKNOWN;
				}
				JavaStaticType declared = typeOfSpec(parts.get(1));
				// Any other type specifier says nothing the bridge's kinds depend on.
				return declared != null ? declared : typeOf(parts.get(2));
			}
			default -> {
				return JavaStaticType.UNKNOWN;
			}
		}
	}

	private static JavaStaticType kinds(JavaKind kind) {
		Set<JavaKind> set = new HashSet<>();
		set.add(kind);
		return new JavaStaticType.Kinds(set);
	}

}
