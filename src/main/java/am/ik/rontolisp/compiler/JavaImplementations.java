package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Chooses how a {@code java:reify} or a {@code java:proxy} implements its interface --
 * the one rule the interpreter, a compiled program's generated class and the reflective
 * bridge share ({@link JavaImplementation}).
 * <p>
 * {@code (java:reify "I" "m" f "n(int)" g ...)}: each designator names ONE method of the
 * interface, or {@code toString}/{@code equals}/{@code hashCode} of {@code Object}, by
 * name and -- as {@code java:call}'s do ({@link JavaOverloads#parseMember}) -- an
 * optional parameter tag; a name several parameter lists share must be tagged. The
 * designator's function implements every return-type variant of the method. An abstract
 * method no designator names throws {@link UnsupportedOperationException}; a default
 * method keeps its body; {@code equals}/{@code hashCode} are {@code Object}'s.
 * {@code (java:proxy "I" f)}: every method, default ones too, calls {@code f} with the
 * method's name before its arguments; only {@code Object}'s three keep their identity
 * behavior.
 * <p>
 * The methods are what {@link JavaType#publicMethods()} answers for the interface,
 * ordered by name, parameters and return type, so the choice -- and a compiled program's
 * class -- does not depend on the order a lookup lists them in.
 */
public final class JavaImplementations {

	/** The error a malformed {@code java:reify} call raises when it runs. */
	public static final String REIFY_USAGE = "java:reify expects (java:reify \"interface\" \"method\" function ...)";

	private static final List<String> OBJECT_METHODS = List.of("equals(java.lang.Object)", "hashCode()", "toString()");

	private JavaImplementations() {
	}

	/**
	 * Whether a form is a {@code java:reify} or {@code java:proxy} call.
	 * @param form a form
	 * @return whether its operator is one of the two
	 */
	public static boolean isImplementationForm(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& (LispNames.JAVA_REIFY_QUALIFIED.equals(head.name())
						|| LispNames.JAVA_PROXY_QUALIFIED.equals(head.name()));
	}

	/**
	 * The {@code java:reify} and {@code java:proxy} forms a form shows, outermost first,
	 * quoted data skipped: what {@code --warn-java-reflection} reports on beside the call
	 * sites ({@link JavaSiteResolver#sitesIn}).
	 * @param form a form
	 * @return the forms, each once
	 */
	public static List<LispCons> formsIn(LispVal form) {
		List<LispCons> forms = new ArrayList<>();
		collectForms(form, forms, new java.util.IdentityHashMap<>());
		return forms;
	}

	private static void collectForms(LispVal form, List<LispCons> forms,
			java.util.IdentityHashMap<LispCons, Boolean> seen) {
		LispVal current = form;
		boolean head = true;
		while (current instanceof LispCons cons && seen.put(cons, Boolean.TRUE) == null) {
			if (head) {
				if (cons.car() instanceof LispSymbol sym && LispNames.QUOTE.equals(sym.name())) {
					return;
				}
				if (isImplementationForm(cons)) {
					forms.add(cons);
				}
				head = false;
			}
			if (cons.car() instanceof LispCons inner) {
				collectForms(inner, forms, seen);
			}
			current = cons.cdr();
		}
	}

	/**
	 * A form as a warning or a refusal names it: the operator and its interface literal.
	 * @param form a {@code java:reify} or {@code java:proxy} form
	 * @return e.g. {@code java:reify "java.lang.Runnable"}
	 */
	public static String describe(LispCons form) {
		String operator = form.car() instanceof LispSymbol head && LispNames.JAVA_PROXY_QUALIFIED.equals(head.name())
				? "java:proxy" : "java:reify";
		return form.cdr() instanceof LispCons rest && rest.car() instanceof LispString iface
				? operator + " " + iface.print() : operator;
	}

	/**
	 * The report of a form whose interface is implemented by reflection when it runs,
	 * without a position prefix.
	 * @param form the form
	 * @param implementation how it resolved
	 * @return e.g. {@code java:proxy is implemented by reflection at run time: the
	 * interface name is not a literal string}
	 */
	public static String reflectionWarning(LispCons form, JavaImplementation implementation) {
		return describe(form) + " is implemented by reflection at run time: " + implementation.reason();
	}

	/**
	 * The error a {@code java:reify} or {@code java:proxy} of a class that is not an
	 * interface raises when it runs.
	 * @param proxy whether it is a {@code java:proxy}
	 * @param name the class name
	 * @return the message
	 */
	public static String notAnInterface(boolean proxy, String name) {
		return (proxy ? "java:proxy" : "java:reify") + " expects an interface, got " + name;
	}

	/**
	 * Resolves a {@code java:reify} or {@code java:proxy} form before it runs: RESOLVED
	 * when its interface and designators are literals and the interface is one a compiled
	 * program can implement; otherwise the form is resolved when it runs, and
	 * {@link JavaImplementation#reason()} says why -- which, for a designator that names
	 * no method or several, is the error the form raises then.
	 * @param form a {@code java:reify} or {@code java:proxy} form
	 * @param lookup where the classes are described
	 * @return how it implements its interface
	 */
	public static JavaImplementation resolve(LispCons form, JavaClassLookup lookup) {
		boolean proxy = form.car() instanceof LispSymbol head && LispNames.JAVA_PROXY_QUALIFIED.equals(head.name());
		if (!form.isProperList()) {
			return unresolved(proxy, "the form is malformed");
		}
		List<LispVal> parts = form.toList();
		if (proxy ? parts.size() != 3 : parts.size() < 2 || parts.size() % 2 != 0) {
			return unresolved(proxy, "the form is malformed");
		}
		if (!(parts.get(1) instanceof LispString name)) {
			return unresolved(proxy, "the interface name is not a literal string");
		}
		List<String> designators = new ArrayList<>();
		for (int i = 2; !proxy && i < parts.size(); i += 2) {
			if (!(parts.get(i) instanceof LispString designator)) {
				return unresolved(proxy, "method name " + (i / 2) + " is not a literal string");
			}
			designators.add(designator.value());
		}
		try {
			JavaType type = lookup.find(name.value());
			if (type == null) {
				return unresolved(proxy, "class " + name.value() + " is not found");
			}
			if (!type.isInterface()) {
				return unresolved(proxy, notAnInterface(proxy, name.value()));
			}
			if (!type.isLinkable()) {
				return unresolved(proxy,
						"interface " + type.name() + " is not " + (type.isAccessible() ? "public" : "accessible"));
			}
			JavaImplementation implementation = proxy ? proxy(type, lookup) : reify(type, designators, lookup);
			for (JavaImplementation.Slot slot : implementation.slots()) {
				// The generated class returns the method's type: it must be able to name
				// it.
				if (!slot.returnType().isLinkable()) {
					return unresolved(proxy, "the return type " + slot.returnType().name() + " of " + type.name() + "."
							+ slot.key() + " is not public");
				}
			}
			return implementation;
		}
		catch (IllegalArgumentException ex) {
			// A designator that names no method, or several: the run-time path raises it.
			return unresolved(proxy, String.valueOf(ex.getMessage()));
		}
		catch (RuntimeException | LinkageError ex) {
			return unresolved(proxy, "the classes could not be inspected: " + ex);
		}
	}

	private static JavaImplementation unresolved(boolean proxy, String reason) {
		return new JavaImplementation(proxy, null, List.of(), reason);
	}

	/**
	 * How {@code (java:reify "I" designator function ...)} implements the interface.
	 * @param iface the interface
	 * @param designators the method designators, in order: designator {@code i}'s
	 * function is implementation {@code i}
	 * @param lookup where {@code Object} is found
	 * @return the implementation
	 * @throws IllegalArgumentException with the error the form raises: a malformed tag, a
	 * designator that names no method or several, a method named twice
	 */
	public static JavaImplementation reify(JavaType iface, List<String> designators, JavaClassLookup lookup) {
		Map<String, Group> groups = groups(iface);
		Map<String, Group> objectGroups = objectGroups(lookup);
		Map<String, Integer> assigned = new LinkedHashMap<>();
		for (int i = 0; i < designators.size(); i++) {
			String designator = designators.get(i);
			JavaOverloads.Member member = JavaOverloads.parseMember(designator);
			List<Group> candidates = new ArrayList<>();
			for (Group group : groups.values()) {
				if (group.name().equals(member.name()) && matches(group, member.tag())) {
					candidates.add(group);
				}
			}
			for (Map.Entry<String, Group> object : objectGroups.entrySet()) {
				Group group = object.getValue();
				if (!groups.containsKey(object.getKey()) && group.name().equals(member.name())
						&& matches(group, member.tag())) {
					candidates.add(group);
				}
			}
			if (candidates.isEmpty()) {
				throw new IllegalArgumentException(
						"java:reify: interface " + iface.name() + " has no method " + designator);
			}
			if (candidates.size() > 1) {
				List<String> keys = new ArrayList<>();
				for (Group candidate : candidates) {
					keys.add(candidate.key());
				}
				throw new IllegalArgumentException("java:reify: " + designator + " names more than one method of "
						+ iface.name() + ": " + String.join(", ", keys));
			}
			String key = candidates.get(0).key();
			if (assigned.putIfAbsent(key, i) != null) {
				throw new IllegalArgumentException("java:reify: " + iface.name() + "." + key + " is implemented twice");
			}
		}
		List<JavaImplementation.Slot> slots = new ArrayList<>();
		for (Group group : groups.values()) {
			Integer implementation = assigned.get(group.key());
			for (Variant variant : group.variants()) {
				if (implementation != null) {
					slots.add(slot(group, variant, implementation));
				}
				else if (variant.mustImplement() && !OBJECT_METHODS.contains(group.key())) {
					// Object's own equals/hashCode/toString implement a redeclared one.
					slots.add(slot(group, variant, JavaImplementation.NONE));
				}
			}
		}
		for (Map.Entry<String, Group> object : objectGroups.entrySet()) {
			Integer implementation = assigned.get(object.getKey());
			if (implementation != null && !groups.containsKey(object.getKey())) {
				for (Variant variant : object.getValue().variants()) {
					slots.add(slot(object.getValue(), variant, implementation));
				}
			}
		}
		return new JavaImplementation(false, iface, slots, null);
	}

	/**
	 * How {@code (java:proxy "I" callable)} implements the interface: every method but
	 * {@code Object}'s three calls the callable.
	 * @param iface the interface
	 * @param lookup unused; for symmetry with {@link #reify}
	 * @return the implementation
	 */
	public static JavaImplementation proxy(JavaType iface, JavaClassLookup lookup) {
		List<JavaImplementation.Slot> slots = new ArrayList<>();
		for (Group group : groups(iface).values()) {
			if (OBJECT_METHODS.contains(group.key())) {
				continue;
			}
			for (Variant variant : group.variants()) {
				slots.add(slot(group, variant, 0));
			}
		}
		return new JavaImplementation(true, iface, slots, null);
	}

	private static JavaImplementation.Slot slot(Group group, Variant variant, int implementation) {
		return new JavaImplementation.Slot(group.name(), group.parameterTypes(), variant.returnType(), implementation);
	}

	private static boolean matches(Group group, @Nullable List<String> tag) {
		if (tag == null) {
			return true;
		}
		List<JavaType> params = group.parameterTypes();
		if (params.size() != tag.size()) {
			return false;
		}
		for (int i = 0; i < params.size(); i++) {
			if (!JavaOverloads.WILDCARD.equals(tag.get(i)) && !tag.get(i).equals(params.get(i).name())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A method of the interface: one name and parameter list, and its return-type
	 * variants.
	 */
	private record Group(String name, List<JavaType> parameterTypes, List<Variant> variants) {

		String key() {
			return JavaImplementation.key(this.name, this.parameterTypes);
		}

	}

	/**
	 * One return type of a method; it must be implemented when a declaration of it is
	 * abstract, or when two interfaces supply a default for it (which a class must
	 * resolve by overriding).
	 */
	private record Variant(JavaType returnType, boolean mustImplement) {
	}

	// The interface's instance methods by key, in key order; each key's variants by
	// return type name.
	private static Map<String, Group> groups(JavaType iface) {
		Map<String, List<JavaExecutable>> byKey = new TreeMap<>();
		for (JavaExecutable method : iface.publicMethods()) {
			if (!method.isStatic()) {
				byKey
					.computeIfAbsent(JavaImplementation.key(method.name(), method.parameterTypes()),
							k -> new ArrayList<>())
					.add(method);
			}
		}
		Map<String, Group> groups = new LinkedHashMap<>();
		for (Map.Entry<String, List<JavaExecutable>> entry : byKey.entrySet()) {
			groups.put(entry.getKey(), group(entry.getValue()));
		}
		return groups;
	}

	private static Group group(List<JavaExecutable> declarations) {
		Map<String, List<JavaExecutable>> byReturn = new TreeMap<>();
		for (JavaExecutable declaration : declarations) {
			byReturn.computeIfAbsent(declaration.returnType().name(), k -> new ArrayList<>()).add(declaration);
		}
		List<Variant> variants = new ArrayList<>();
		for (List<JavaExecutable> sameReturn : byReturn.values()) {
			int defaults = 0;
			boolean anyAbstract = false;
			for (JavaExecutable declaration : sameReturn) {
				if (declaration.isAbstract()) {
					anyAbstract = true;
				}
				else {
					defaults++;
				}
			}
			variants.add(new Variant(sameReturn.get(0).returnType(), anyAbstract || defaults > 1));
		}
		JavaExecutable first = declarations.get(0);
		return new Group(first.name(), List.copyOf(first.parameterTypes()), List.copyOf(variants));
	}

	// Object's public methods a java:reify may implement: equals, hashCode, toString.
	private static Map<String, Group> objectGroups(JavaClassLookup lookup) {
		Map<String, Group> groups = new LinkedHashMap<>();
		JavaType object = lookup.find("java.lang.Object");
		if (object == null) {
			return groups;
		}
		Map<String, List<JavaExecutable>> byKey = new TreeMap<>();
		for (JavaExecutable method : object.publicMethods()) {
			String key = JavaImplementation.key(method.name(), method.parameterTypes());
			if (OBJECT_METHODS.contains(key)) {
				byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(method);
			}
		}
		for (Map.Entry<String, List<JavaExecutable>> entry : byKey.entrySet()) {
			List<Variant> variants = List.of(new Variant(entry.getValue().get(0).returnType(), false));
			JavaExecutable first = entry.getValue().get(0);
			groups.put(entry.getKey(), new Group(first.name(), List.copyOf(first.parameterTypes()), variants));
		}
		return groups;
	}

}
