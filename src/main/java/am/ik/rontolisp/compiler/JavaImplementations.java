package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.JavaInterfaceMethods.Group;
import am.ik.rontolisp.compiler.JavaInterfaceMethods.Variant;
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
 * {@code (java:proxy "I" ... f)}: every method of every interface, default ones too,
 * calls {@code f} with the method's name before its arguments -- one name the interfaces
 * share is one call, whichever interface declares it; only {@code Object}'s three keep
 * their identity behavior.
 * <p>
 * The methods are what {@link JavaType#publicMethods()} answers for the interfaces,
 * ordered by name, parameters and return type, so the choice -- and a compiled program's
 * class -- does not depend on the order a lookup lists them in.
 */
public final class JavaImplementations {

	/** The error a malformed {@code java:reify} call raises when it runs. */
	public static final String REIFY_USAGE = "java:reify expects (java:reify \"interface\"-or-list"
			+ " [:value v] [:class \"class\"] \"method\" function ...)";

	/** The error a malformed {@code java:proxy} call raises when it runs. */
	public static final String PROXY_USAGE = "java:proxy expects (java:proxy \"interface\"... callable)";

	/** The error a malformed {@code java:subclass} call raises when it runs. */
	public static final String SUBCLASS_USAGE = "java:subclass expects (java:subclass \"superclass\""
			+ " '(\"interface\"...) '(\"method\"...) constructor-args... callable)";

	/**
	 * The error a {@code java:handle} call raises -- with {@code ", got X"} for the first
	 * argument it refuses: a text that is no string or nil (a nil one with a hash that is
	 * not nil), a hash that is no integer or nil, an order that is no string, function or
	 * nil, a class that is no string or nil.
	 */
	public static final String HANDLE_USAGE = "java:handle expects (java:handle value text [hash [order [\"class\"]]])";

	/**
	 * The error a {@code java:view} call raises -- with {@code ", got X"} for the first
	 * argument it refuses: a shape that is none of the five, a printer that is no
	 * function or nil, an order that is no function (a {@code :vector}'s) or nil, a class
	 * that is no string or nil, items that are no sequence ({@code :map}'s no hash table
	 * or plist, {@code :bytes}' no {@code (unsigned-byte 8)} vector).
	 */
	public static final String VIEW_USAGE = "java:view expects (java:view value items :list|:vector|:set|:map|:bytes"
			+ " [printer [order [\"class\"]]])";

	/** The error of a {@code java:view} item that converts to no {@code Object}. */
	public static final String VIEW_NO_VALUE = "java:view: no Java value for ";

	/**
	 * The index the markers ending a {@code java:reify}, {@code java:proxy} or
	 * {@code java:subclass} form ({@link JavaMarkers}) may start at: after a
	 * {@code java:reify}'s interfaces and the options after them ({@link #reifyParts}), a
	 * {@code java:proxy}'s interface and callable, a {@code java:subclass}'s three names
	 * and its callable. A {@code java:subclass}'s {@code :functional} converts a function
	 * constructor argument by the method's arguments, as at a {@code java:new} ending in
	 * it; {@code :java-false} answers Java's {@code false} to the form's functions as
	 * {@code |false|}.
	 * @param parts the form's elements, the operator first
	 * @return the index in the form's elements
	 */
	private static int firstMarker(List<LispVal> parts) {
		return switch (operatorName(parts)) {
			case LispNames.JAVA_REIFY_QUALIFIED -> {
				ReifyParts shape = reifyParts(parts, 1);
				yield shape == null ? 2 : shape.firstDesignator();
			}
			case LispNames.JAVA_PROXY_QUALIFIED -> 3;
			default -> 5;
		};
	}

	/**
	 * The markers ending an implementation form.
	 * @param parts the form's elements, the operator first
	 * @return the markers
	 */
	public static JavaMarkers markers(List<LispVal> parts) {
		return JavaMarkers.of(parts, firstMarker(parts));
	}

	/**
	 * How many elements ending an implementation form are markers.
	 * @param parts the form's elements, the operator first
	 * @return the count
	 */
	public static int markerCount(List<LispVal> parts) {
		return JavaMarkers.count(parts, firstMarker(parts));
	}

	/**
	 * Where the parts of a {@code java:reify} are, past its interfaces: the options
	 * standing the object for a value -- {@code :value v}
	 * ({@link LispNames#JAVA_VALUE_OPTION}) and {@code :class c}
	 * ({@link LispNames#JAVA_CLASS_OPTION}), each at most once, in any order -- then the
	 * designator and function pairs, then the markers. A keyword is never a designator,
	 * so the options are told from the pairs whatever the values are.
	 *
	 * @param firstDesignator the index of the first designator (or of the first marker,
	 * or the end)
	 * @param value the index of the {@code :value} form, or {@code -1}
	 * @param className the index of the {@code :class} form, or {@code -1}
	 */
	public record ReifyParts(int firstDesignator, int value, int className) {
	}

	/**
	 * The options after a {@code java:reify}'s interfaces.
	 * @param parts a form's elements (the interfaces at 1) or the evaluated arguments
	 * (the interfaces at 0)
	 * @param interfaces the index of the interfaces
	 * @return where the parts are, or {@code null} when an option is named twice, or
	 * {@code :class} without {@code :value}: the form is malformed
	 */
	public static @Nullable ReifyParts reifyParts(List<LispVal> parts, int interfaces) {
		int value = -1;
		int className = -1;
		int i = interfaces + 1;
		while (i + 1 < parts.size() && parts.get(i) instanceof LispSymbol option) {
			if (LispNames.JAVA_VALUE_OPTION.equals(option.name()) && value < 0) {
				value = i + 1;
			}
			else if (LispNames.JAVA_CLASS_OPTION.equals(option.name()) && className < 0) {
				className = i + 1;
			}
			else if (LispNames.JAVA_VALUE_OPTION.equals(option.name())
					|| LispNames.JAVA_CLASS_OPTION.equals(option.name())) {
				return null;
			}
			else {
				break;
			}
			i += 2;
		}
		if (className >= 0 && value < 0) {
			return null;
		}
		return new ReifyParts(i, value, className);
	}

	private static String operatorName(List<LispVal> parts) {
		return !parts.isEmpty() && parts.get(0) instanceof LispSymbol head ? head.name() : "";
	}

	/**
	 * How many throwables a thread holds between the function called back from Java that
	 * raised each one and the {@code java:} site whose Java call passes it on (the
	 * interpreter's {@code JavaInterop}, a compiled program's {@code _jsig}): the newest
	 * are kept, so one that Java swallowed is dropped in time.
	 */
	public static final int PENDING_SIGNALS = 16;

	private JavaImplementations() {
	}

	/**
	 * Whether a form is a {@code java:reify}, {@code java:proxy} or {@code java:subclass}
	 * call.
	 * @param form a form
	 * @return whether its operator is one of the three
	 */
	public static boolean isImplementationForm(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& (LispNames.JAVA_REIFY_QUALIFIED.equals(head.name())
						|| LispNames.JAVA_PROXY_QUALIFIED.equals(head.name())
						|| LispNames.JAVA_SUBCLASS_QUALIFIED.equals(head.name()));
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
				if (cons.car() instanceof LispSymbol sym && LispNames.isQuote(sym.name())) {
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
	 * A form as a warning or a refusal names it: the operator and its interface literals
	 * -- a {@code java:proxy}'s each, up to the first that is not one; a
	 * {@code java:subclass}'s superclass, up to the first position that is not one.
	 * @param form a {@code java:reify}, {@code java:proxy} or {@code java:subclass} form
	 * @return e.g. {@code java:reify "java.lang.Runnable"}
	 */
	public static String describe(LispCons form) {
		boolean proxy = form.car() instanceof LispSymbol head && LispNames.JAVA_PROXY_QUALIFIED.equals(head.name());
		boolean subclass = form.car() instanceof LispSymbol head2
				&& LispNames.JAVA_SUBCLASS_QUALIFIED.equals(head2.name());
		StringBuilder text = new StringBuilder(subclass ? "java:subclass" : proxy ? "java:proxy" : "java:reify");
		LispVal rest = form.cdr();
		if (subclass) {
			if (rest instanceof LispCons cell && cell.car() instanceof LispString superName) {
				text.append(' ').append(superName.print());
			}
			return text.toString();
		}
		if (!proxy && rest instanceof LispCons cell) {
			// a java:reify's interfaces: one literal, or a quoted list of them
			List<String> names = quotedStrings(cell.car());
			for (String name : names != null ? names : List.<String>of()) {
				text.append(' ').append(new LispString(name).print());
			}
			if (names != null) {
				return text.toString();
			}
		}
		while (rest instanceof LispCons cell && cell.car() instanceof LispString iface
				&& (proxy ? cell.cdr() instanceof LispCons : text.length() == "java:reify".length())) {
			text.append(' ').append(iface.print());
			rest = cell.cdr();
		}
		return text.toString();
	}

	/**
	 * The error a {@code java:proxy} that names one interface twice raises when it runs.
	 * @param name the interface name
	 * @return the message
	 */
	public static String repeatedInterface(String name) {
		return "java:proxy names interface " + name + " twice";
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
		if (form.car() instanceof LispSymbol head && LispNames.JAVA_SUBCLASS_QUALIFIED.equals(head.name())) {
			return describe(form) + " is left to run time: " + implementation.reason();
		}
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
	 * no method or several, is the error the form raises then. A {@code java:subclass}
	 * form resolves through {@link #resolveSubclass}.
	 * @param form a {@code java:reify}, {@code java:proxy} or {@code java:subclass} form
	 * @param lookup where the classes are described
	 * @return how it implements its interface
	 */
	public static JavaImplementation resolve(LispCons form, JavaClassLookup lookup) {
		if (form.car() instanceof LispSymbol head && LispNames.JAVA_SUBCLASS_QUALIFIED.equals(head.name())) {
			return resolveSubclass(form, lookup);
		}
		boolean proxy = form.car() instanceof LispSymbol head2 && LispNames.JAVA_PROXY_QUALIFIED.equals(head2.name());
		if (!form.isProperList()) {
			return unresolved(proxy, "the form is malformed");
		}
		List<LispVal> all = form.toList();
		JavaMarkers markers = markers(all);
		List<LispVal> parts = all.subList(0, all.size() - markerCount(all));
		ReifyParts shape = proxy ? null : reifyParts(parts, 1);
		if (proxy ? parts.size() < 3
				: shape == null || parts.size() < 2 || (parts.size() - shape.firstDesignator()) % 2 != 0) {
			return unresolved(proxy, "the form is malformed");
		}
		// A java:reify names one interface or a quoted list of them; a java:proxy every
		// part before its callable.
		List<String> names = new ArrayList<>();
		if (proxy) {
			int interfaceCount = parts.size() - 2;
			for (int i = 1; i <= interfaceCount; i++) {
				if (!(parts.get(i) instanceof LispString name)) {
					return unresolved(proxy, interfaceCount == 1 ? "the interface name is not a literal string"
							: "interface name " + i + " is not a literal string");
				}
				names.add(name.value());
			}
		}
		else if (parts.get(1) instanceof LispString name) {
			names.add(name.value());
		}
		else {
			List<String> listed = quotedStrings(parts.get(1));
			if (listed == null) {
				return unresolved(proxy, "the interface names are not a literal string or a quoted list of them");
			}
			if (listed.isEmpty()) {
				return unresolved(proxy, "the form is malformed");
			}
			names.addAll(listed);
		}
		JavaImplementation.StandIn standIn = null;
		if (shape != null && shape.value() >= 0) {
			String className = null;
			if (shape.className() >= 0) {
				if (parts.get(shape.className()) instanceof LispString given) {
					className = given.value();
				}
				else if (!(parts.get(shape.className()) instanceof LispNil)) {
					return unresolved(proxy, "the class name is not a literal string");
				}
			}
			standIn = new JavaImplementation.StandIn(className);
		}
		List<String> designators = new ArrayList<>();
		int firstDesignator = shape == null ? parts.size() : shape.firstDesignator();
		for (int i = firstDesignator; i < parts.size(); i += 2) {
			if (!(parts.get(i) instanceof LispString designator)) {
				return unresolved(proxy, "method name " + ((i - firstDesignator) / 2 + 1) + " is not a literal string");
			}
			designators.add(designator.value());
		}
		try {
			List<JavaType> interfaces = new ArrayList<>();
			for (String name : names) {
				JavaType type = lookup.find(name);
				if (type == null) {
					return unresolved(proxy, "class " + name + " is not found");
				}
				if (!type.isInterface()) {
					return unresolved(proxy, notAnInterface(proxy, name));
				}
				if (!type.isLinkable()) {
					return unresolved(proxy,
							"interface " + type.name() + " is not " + (type.isAccessible() ? "public" : "accessible"));
				}
				if (interfaces.contains(type)) {
					return unresolved(proxy, proxy ? repeatedInterface(name) : reifyRepeatedInterface(name));
				}
				interfaces.add(type);
			}
			JavaImplementation implementation = (proxy ? proxy(interfaces, lookup)
					: reify(interfaces, designators, lookup))
				.withMarkers(markers);
			if (standIn != null) {
				String conflict = standInConflict(implementation.interfaces());
				if (conflict != null) {
					return unresolved(proxy, conflict);
				}
				implementation = implementation.withStandIn(standIn);
			}
			for (JavaImplementation.Slot slot : implementation.slots()) {
				// The generated class returns the method's type: it must be able to name
				// it.
				if (!slot.returnType().isLinkable()) {
					return unresolved(proxy, "the return type " + slot.returnType().name() + " of "
							+ implementation.declaringName(slot.key()) + "." + slot.key() + " is not public");
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
		return new JavaImplementation(proxy, List.of(), List.of(), reason);
	}

	/**
	 * The error a {@code java:reify} that names one interface twice raises when it runs.
	 * @param name the interface name
	 * @return the message
	 */
	public static String reifyRepeatedInterface(String name) {
		return "java:reify names interface " + name + " twice";
	}

	/**
	 * The error a {@code java:reify} given {@code :value} raises when one of its
	 * interfaces declares a method the object standing for the value implements itself:
	 * {@code value()} or {@code className()} of {@code runtime/RontoJavaValue}.
	 * @param interfaces the interfaces
	 * @return the message, or {@code null} when none declares one
	 */
	public static @Nullable String standInConflict(List<JavaType> interfaces) {
		for (JavaType iface : interfaces) {
			for (JavaExecutable method : iface.publicMethods()) {
				if (!method.isStatic() && method.parameterTypes().isEmpty()
						&& STAND_IN_METHODS.contains(method.name())) {
					return standInConflict(iface.name(), method.name());
				}
			}
		}
		return null;
	}

	/**
	 * The error a {@code java:reify} given {@code :value} raises for an interface
	 * declaring a method the object standing for the value implements itself.
	 * @param iface the interface
	 * @param method {@code value} or {@code className}
	 * @return the message
	 */
	public static String standInConflict(String iface, String method) {
		return "java:reify: :value conflicts with " + iface + "." + method + "()";
	}

	/**
	 * The methods of {@code runtime/RontoJavaValue}, which an object standing for a value
	 * implements itself, both taking no argument.
	 */
	public static final List<String> STAND_IN_METHODS = List.of("value", "className");

	/**
	 * The interfaces an implementation of all of them declares: those no other one of
	 * them extends, in their order. Implementing a subinterface implements its
	 * superinterfaces, and its methods are already the most specific ones -- an
	 * overriding default rather than the one it overrides -- so naming a superinterface
	 * beside it changes nothing.
	 * @param interfaces the interfaces, distinct
	 * @return the ones to implement
	 */
	public static List<JavaType> mostSpecific(List<JavaType> interfaces) {
		List<JavaType> out = new ArrayList<>();
		for (JavaType iface : interfaces) {
			boolean implied = false;
			for (JavaType other : interfaces) {
				if (other != iface && iface.isAssignableFrom(other)) {
					implied = true;
					break;
				}
			}
			if (!implied) {
				out.add(iface);
			}
		}
		return out;
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
		return reify(List.of(iface), designators, lookup);
	}

	/**
	 * How {@code (java:reify '("I" "J" ...) designator function ...)} implements the
	 * interfaces: one object implementing each ({@link #mostSpecific} of them, a
	 * superinterface of another listed one implied), a designator naming one method of
	 * any of them -- a method two declare with one parameter list is one -- or of
	 * {@code Object}'s three.
	 * @param listed the interfaces, distinct, in the form's order
	 * @param designators the method designators, in order: designator {@code i}'s
	 * function is implementation {@code i}
	 * @param lookup where {@code Object} is found
	 * @return the implementation
	 * @throws IllegalArgumentException with the error the form raises: a malformed tag, a
	 * designator that names no method or several, a method named twice
	 */
	public static JavaImplementation reify(List<JavaType> listed, List<String> designators, JavaClassLookup lookup) {
		List<JavaType> interfaces = mostSpecific(listed);
		String names = JavaImplementation.interfaceNames(interfaces);
		Map<String, Group> groups = JavaInterfaceMethods.groups(interfaces);
		Map<String, Group> objectGroups = JavaInterfaceMethods.objectGroups(lookup);
		Map<String, Integer> assigned = new LinkedHashMap<>();
		JavaImplementation named = new JavaImplementation(false, interfaces, List.of(), null);
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
				throw new IllegalArgumentException("java:reify: "
						+ (interfaces.size() == 1 ? "interface " + names + " has" : "interfaces " + names + " have")
						+ " no method " + designator);
			}
			if (candidates.size() > 1) {
				List<String> keys = new ArrayList<>();
				for (Group candidate : candidates) {
					keys.add(candidate.key());
				}
				throw new IllegalArgumentException("java:reify: " + designator + " names more than one method of "
						+ names + ": " + String.join(", ", keys));
			}
			String key = candidates.get(0).key();
			if (assigned.putIfAbsent(key, i) != null) {
				throw new IllegalArgumentException(
						"java:reify: " + named.declaringName(key) + "." + key + " is implemented twice");
			}
		}
		List<JavaImplementation.Slot> slots = new ArrayList<>();
		for (Group group : groups.values()) {
			Integer implementation = assigned.get(group.key());
			for (Variant variant : group.variants()) {
				if (implementation != null) {
					slots.add(slot(group, variant, implementation));
				}
				else if (variant.mustImplement() && !JavaInterfaceMethods.OBJECT_METHODS.contains(group.key())) {
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
		return new JavaImplementation(false, interfaces, slots, null);
	}

	/**
	 * How a function passed where the interface is expected implements it at a
	 * {@code :functional} site (a Clojure fn, as Java implements a lambda): every method
	 * a class must implement -- an abstract one, {@code Object}'s three aside -- calls
	 * the function with the method's arguments alone; a default method keeps its body and
	 * {@code Object}'s three their identity behavior. A {@code java:reify} whose one
	 * function implements every abstract method.
	 * @param iface the interface
	 * @param lookup unused; for symmetry with {@link #reify}
	 * @return the implementation
	 */
	public static JavaImplementation functional(JavaType iface, JavaClassLookup lookup) {
		return functional(iface, lookup, JavaMarkers.FUNCTIONAL);
	}

	/**
	 * {@link #functional(JavaType, JavaClassLookup)} at a call ending in these markers
	 * ({@code :functional} among them): with {@code :java-false} an argument Java hands
	 * the function answers Java's {@code false} as {@code |false|}, and a function
	 * implementing {@code java.util.Comparator} may answer a boolean
	 * ({@link JavaImplementation#readsComparison}); with {@code :octets} a {@code byte[]}
	 * Java hands it is an {@code (unsigned-byte 8)} vector.
	 * @param iface the interface
	 * @param lookup unused; for symmetry with {@link #reify}
	 * @param markers the call's markers
	 * @return the implementation
	 */
	public static JavaImplementation functional(JavaType iface, JavaClassLookup lookup, JavaMarkers markers) {
		List<JavaImplementation.Slot> slots = new ArrayList<>();
		for (Group group : JavaInterfaceMethods.groups(iface).values()) {
			if (JavaInterfaceMethods.OBJECT_METHODS.contains(group.key())) {
				continue;
			}
			boolean abstractMethod = false;
			for (Variant variant : group.variants()) {
				abstractMethod |= variant.mustImplement();
			}
			if (abstractMethod) {
				for (Variant variant : group.variants()) {
					slots.add(slot(group, variant, 0));
				}
			}
		}
		return new JavaImplementation(false, List.of(iface), slots, null, null,
				new JavaMarkers(true, markers.javaFalse(), markers.octets()));
	}

	/**
	 * How {@code (java:proxy "I" callable)} implements the interface: every method but
	 * {@code Object}'s three calls the callable.
	 * @param iface the interface
	 * @param lookup unused; for symmetry with {@link #reify}
	 * @return the implementation
	 */
	public static JavaImplementation proxy(JavaType iface, JavaClassLookup lookup) {
		return proxy(List.of(iface), lookup);
	}

	/**
	 * How {@code (java:proxy "I" "J" ... callable)} implements the interfaces: every
	 * method of each but {@code Object}'s three calls the callable. A method two
	 * interfaces both declare -- one name, parameter list and return type -- is one slot;
	 * the same name with other parameters or another return type is a slot of its own,
	 * calling the same callable.
	 * @param interfaces the interfaces, distinct, in the form's order
	 * @param lookup unused; for symmetry with {@link #reify}
	 * @return the implementation
	 */
	public static JavaImplementation proxy(List<JavaType> interfaces, JavaClassLookup lookup) {
		List<JavaImplementation.Slot> slots = new ArrayList<>();
		for (Group group : JavaInterfaceMethods.groups(interfaces).values()) {
			if (JavaInterfaceMethods.OBJECT_METHODS.contains(group.key())) {
				continue;
			}
			for (Variant variant : group.variants()) {
				slots.add(slot(group, variant, 0));
			}
		}
		return new JavaImplementation(true, interfaces, slots, null);
	}

	/**
	 * The error a {@code java:subclass} of a class that is not a class raises when it
	 * runs.
	 * @param name the interface name
	 * @return the message
	 */
	public static String notAClass(String name) {
		return "java:subclass expects a class, got " + name;
	}

	/**
	 * The error a {@code java:subclass} of a final class raises when it runs.
	 * @param name the class name
	 * @return the message
	 */
	public static String finalSuperclass(String name) {
		return "java:subclass: class " + name + " is final and cannot be extended";
	}

	/**
	 * The error a {@code java:subclass} whose interface is not one raises when it runs.
	 * @param name the class name
	 * @return the message
	 */
	public static String subclassNotAnInterface(String name) {
		return "java:subclass expects an interface, got " + name;
	}

	/**
	 * The error a {@code java:subclass} that names one interface twice raises when it
	 * runs.
	 * @param name the interface name
	 * @return the message
	 */
	public static String subclassRepeatedInterface(String name) {
		return "java:subclass names interface " + name + " twice";
	}

	/**
	 * Resolves a {@code java:subclass} form before it runs: RESOLVED when its superclass,
	 * interface and method names are literals and name classes a compiled program can
	 * extend and implement; otherwise the form is resolved when it runs, and
	 * {@link JavaImplementation#reason()} says why -- which, for a method name that names
	 * no method, is the error the form raises then.
	 * @param form a {@code java:subclass} form
	 * @param lookup where the classes are described
	 * @return how it extends its superclass
	 */
	public static JavaImplementation resolveSubclass(LispCons form, JavaClassLookup lookup) {
		if (!form.isProperList()) {
			return subclassUnresolved("the form is malformed");
		}
		List<LispVal> all = form.toList();
		JavaMarkers markers = markers(all);
		List<LispVal> parts = all.subList(0, all.size() - markerCount(all));
		if (parts.size() < 5) {
			return subclassUnresolved("the form is malformed");
		}
		if (!(parts.get(1) instanceof LispString superName)) {
			return subclassUnresolved("the superclass name is not a literal string");
		}
		List<String> interfaceNames = quotedStrings(parts.get(2));
		if (interfaceNames == null) {
			return subclassUnresolved("the interface names are not a quoted list of literal strings");
		}
		List<String> methodNames = quotedStrings(parts.get(3));
		if (methodNames == null) {
			return subclassUnresolved("the method names are not a quoted list of literal strings");
		}
		try {
			JavaType superclass = lookup.find(superName.value());
			if (superclass == null) {
				return subclassUnresolved("class " + superName.value() + " is not found");
			}
			if (superclass.isInterface()) {
				return subclassUnresolved(notAClass(superName.value()));
			}
			if (superclass.isFinal()) {
				return subclassUnresolved(finalSuperclass(superName.value()));
			}
			if (!superclass.isLinkable()) {
				return subclassUnresolved("class " + superclass.name() + " is not "
						+ (superclass.isAccessible() ? "public" : "accessible"));
			}
			List<JavaType> interfaces = new ArrayList<>();
			for (String name : interfaceNames) {
				JavaType type = lookup.find(name);
				if (type == null) {
					return subclassUnresolved("class " + name + " is not found");
				}
				if (!type.isInterface()) {
					return subclassUnresolved(subclassNotAnInterface(name));
				}
				if (!type.isLinkable()) {
					return subclassUnresolved(
							"interface " + type.name() + " is not " + (type.isAccessible() ? "public" : "accessible"));
				}
				if (interfaces.contains(type)) {
					return subclassUnresolved(subclassRepeatedInterface(name));
				}
				interfaces.add(type);
			}
			JavaImplementation implementation = subclass(superclass, interfaces, methodNames).withMarkers(markers);
			int argc = parts.size() - 5;
			boolean viable = false;
			for (JavaOverloads.Overload overload : JavaOverloads.ranked(superclass.subclassConstructors(), argc)) {
				boolean linkable = true;
				for (JavaType parameter : overload.executable().parameterTypes()) {
					if (!parameter.isLinkable()) {
						linkable = false;
						break;
					}
				}
				if (linkable) {
					viable = true;
					break;
				}
			}
			if (!viable) {
				return subclassUnresolved(
						"No matching constructor for " + superclass.name() + " with " + argc + " argument(s)");
			}
			for (JavaImplementation.Slot slot : implementation.slots()) {
				// The generated class takes and returns the method's types: it must be
				// able to name them.
				if (!slot.returnType().isLinkable()) {
					return subclassUnresolved("the return type " + slot.returnType().name() + " of " + superclass.name()
							+ "." + slot.key() + " is not public");
				}
				for (JavaType parameter : slot.parameterTypes()) {
					if (!parameter.isLinkable()) {
						return subclassUnresolved("the parameter type " + parameter.name() + " of " + superclass.name()
								+ "." + slot.key() + " is not public");
					}
				}
			}
			return implementation;
		}
		catch (IllegalArgumentException ex) {
			// A method name that names no method, or a final one: the run-time path
			// raises it.
			return subclassUnresolved(String.valueOf(ex.getMessage()));
		}
		catch (RuntimeException | LinkageError ex) {
			return subclassUnresolved("the classes could not be inspected: " + ex);
		}
	}

	private static JavaImplementation subclassUnresolved(String reason) {
		return new JavaImplementation(true, List.of(), List.of(), reason, null);
	}

	// A (quote (...)) datum as its literal strings, or null when it is not a quoted
	// proper list of literal strings.
	private static @Nullable List<String> quotedStrings(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 || !(parts.get(0) instanceof LispSymbol quote) || !LispNames.QUOTE.equals(quote.name())) {
			return null;
		}
		LispVal quoted = parts.get(1);
		if (quoted instanceof LispNil) {
			return List.of();
		}
		if (!(quoted instanceof LispCons list) || !list.isProperList()) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for (LispVal item : list.toList()) {
			if (!(item instanceof LispString name)) {
				return null;
			}
			names.add(name.value());
		}
		return names;
	}

	/**
	 * How {@code (java:subclass "S" '("I"...) '("m"...) ctor-args... callable)} extends
	 * its superclass: every named method -- of the class chain or of an extra interface
	 * -- calls the callable with the object and the method's name before its arguments;
	 * an unnamed method with a concrete declaration in the class chain is inherited (it
	 * calls super); an abstract one the chain does not implement, and an unnamed
	 * interface method, throws {@link UnsupportedOperationException} with the method's
	 * name.
	 * @param superclass the superclass, a linkable class
	 * @param interfaces the extra interfaces, distinct, in the form's order
	 * @param methods the overridden method names, in the form's order
	 * @return the implementation
	 * @throws IllegalArgumentException with the error the form raises: a method name that
	 * names no method, or one that is final
	 */
	public static JavaImplementation subclass(JavaType superclass, List<JavaType> interfaces, List<String> methods) {
		Map<String, SubclassGroup> all = new TreeMap<>();
		for (JavaExecutable method : superclass.overridableMethods()) {
			if (method.isStatic()) {
				continue;
			}
			String key = JavaImplementation.key(method.name(), method.parameterTypes());
			SubclassGroup group = all.get(key);
			if (group == null) {
				all.put(key, group = new SubclassGroup(method.name(), method.parameterTypes()));
			}
			group.classDeclarations.add(method);
		}
		for (JavaType iface : interfaces) {
			for (JavaExecutable method : iface.publicMethods()) {
				if (method.isStatic()) {
					continue;
				}
				String key = JavaImplementation.key(method.name(), method.parameterTypes());
				SubclassGroup group = all.get(key);
				if (group == null) {
					all.put(key, group = new SubclassGroup(method.name(), method.parameterTypes()));
				}
				group.interfaceDeclarations.add(method);
			}
		}
		Set<String> named = new HashSet<>(methods);
		List<JavaImplementation.Slot> slots = new ArrayList<>();
		for (SubclassGroup group : all.values()) {
			boolean overrides = named.contains(group.name);
			if (!overrides) {
				// Unnamed with a concrete declaration in the class chain: inherited.
				if (group.concreteClassDeclaration() != null) {
					continue;
				}
				// Unnamed abstract: no super to call -- the oracle's
				// UnsupportedOperationException with the method name.
				for (JavaType returnType : group.returnTypes()) {
					slots.add(new JavaImplementation.Slot(group.name, group.parameterTypes, returnType,
							JavaImplementation.NONE));
				}
				continue;
			}
			for (JavaType returnType : group.returnTypes()) {
				slots.add(new JavaImplementation.Slot(group.name, group.parameterTypes, returnType, 0));
			}
		}
		for (String name : methods) {
			boolean found = false;
			for (SubclassGroup group : all.values()) {
				if (group.name.equals(name)) {
					found = true;
					break;
				}
			}
			if (!found) {
				// A static, final or private method of the name exists but no override
				// can run the body; anything else names nothing at all.
				if (!superclass.methods(name).isEmpty() || anyInterfaceMethod(interfaces, name)) {
					throw new IllegalArgumentException(
							"java:subclass: " + superclass.name() + "." + name + " cannot be overridden");
				}
				throw new IllegalArgumentException("java:subclass: " + superclass.name() + " has no method " + name);
			}
		}
		return new JavaImplementation(true, List.copyOf(interfaces), slots, null, superclass);
	}

	// Whether any extra interface declares a method of this name.
	private static boolean anyInterfaceMethod(List<JavaType> interfaces, String name) {
		for (JavaType iface : interfaces) {
			if (!iface.methods(name).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The name of the generated {@code super} accessor a {@code proxy-super} of a method
	 * with this arity calls: one per (name, arity), so the lowering needs no types to
	 * spell it.
	 * @param name the method name
	 * @param arity the argument count
	 * @return e.g. {@code super$paintComponent$1}
	 */
	public static String superAccessor(String name, int arity) {
		return "super$" + name + "$" + arity;
	}

	/**
	 * What a generated {@code super} accessor calls.
	 *
	 * @param owner the class or interface whose implementation runs (the most derived
	 * concrete declaration of the class chain, else the first extra interface with a
	 * default for it)
	 * @param executable the declaration it calls
	 */
	public record SuperTarget(JavaType owner, JavaExecutable executable) {
	}

	/**
	 * What a generated {@code super} accessor for this method calls: the most derived
	 * concrete declaration of the class chain, else the first extra interface's default
	 * for it -- or {@code null} when no superclass implementation exists (an abstract
	 * method nothing implements), in which case no accessor is generated.
	 * @param superclass the superclass
	 * @param interfaces the extra interfaces, in the form's order
	 * @param name the method name
	 * @param params its parameter types
	 * @return the call target, or {@code null}
	 */
	public static @Nullable SuperTarget superTarget(JavaType superclass, List<JavaType> interfaces, String name,
			List<JavaType> params) {
		for (JavaExecutable method : superclass.overridableMethods()) {
			if (!method.isStatic() && !method.isAbstract() && method.name().equals(name)
					&& sameParamNames(method.parameterTypes(), params)) {
				return new SuperTarget(method.declaringClass(), method);
			}
		}
		for (JavaType iface : interfaces) {
			for (JavaExecutable method : iface.publicMethods()) {
				if (!method.isStatic() && !method.isAbstract() && method.name().equals(name)
						&& sameParamNames(method.parameterTypes(), params)) {
					return new SuperTarget(method.declaringClass(), method);
				}
			}
		}
		return null;
	}

	private static boolean sameParamNames(List<? extends JavaType> a, List<JavaType> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (!a.get(i).name().equals(b.get(i).name())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * One overridable method: one name and parameter list, and every declaration of it
	 * (the class chain's and the extra interfaces').
	 */
	private static final class SubclassGroup {

		final String name;

		final List<JavaType> parameterTypes;

		final List<JavaExecutable> classDeclarations = new ArrayList<>();

		final List<JavaExecutable> interfaceDeclarations = new ArrayList<>();

		SubclassGroup(String name, List<? extends JavaType> parameterTypes) {
			this.name = name;
			this.parameterTypes = List.copyOf(parameterTypes);
		}

		String key() {
			return JavaImplementation.key(this.name, this.parameterTypes);
		}

		// A concrete declaration in the class chain: what an unnamed method inherits.
		// The most derived class declaration wins; an interface default never counts.
		private @Nullable JavaExecutable concreteClassDeclaration() {
			for (JavaExecutable declaration : this.classDeclarations) {
				if (!declaration.isAbstract()) {
					return declaration;
				}
			}
			return null;
		}

		// Every return-type variant, by return type name: one slot each, as for an
		// interface proxy's covariant variants.
		private List<JavaType> returnTypes() {
			Map<String, JavaType> byName = new TreeMap<>();
			for (JavaExecutable declaration : this.classDeclarations) {
				byName.putIfAbsent(declaration.returnType().name(), declaration.returnType());
			}
			for (JavaExecutable declaration : this.interfaceDeclarations) {
				byName.putIfAbsent(declaration.returnType().name(), declaration.returnType());
			}
			return List.copyOf(byName.values());
		}

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

}
