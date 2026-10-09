package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The instance methods a set of interfaces declares, grouped by name and parameters, and
 * which of their return-type variants an implementing class must implement -- the
 * structure {@link JavaImplementations} builds a {@code java:reify}, {@code java:proxy}
 * or {@code :functional} implementation from, and overload ranking asks whether an
 * interface is functional by.
 */
final class JavaInterfaceMethods {

	static final List<String> OBJECT_METHODS = List.of("equals(java.lang.Object)", "hashCode()", "toString()");

	/**
	 * Whether the interface is a functional one in Java's sense: exactly one method a
	 * class must implement, {@code Object}'s three aside -- what a lambda can implement.
	 * A function converted to an interface costs less for one
	 * ({@code JavaOverloads.kindCost}), so {@code TreeSet(Comparator)} wins over
	 * {@code TreeSet(Collection)} for a function, as Java's lambda and the oracle's fn (a
	 * {@code Comparator} itself, never a {@code Collection}) choose. Remembered per type.
	 * @param iface an interface
	 * @return whether it has exactly one abstract method
	 */
	static boolean isFunctionalInterface(JavaType iface) {
		Boolean known;
		synchronized (FUNCTIONAL_INTERFACES) {
			known = FUNCTIONAL_INTERFACES.get(iface);
		}
		if (known != null) {
			return known;
		}
		int abstractMethods = 0;
		for (Group group : groups(iface).values()) {
			if (OBJECT_METHODS.contains(group.key())) {
				continue;
			}
			for (Variant variant : group.variants()) {
				if (variant.mustImplement()) {
					abstractMethods++;
					break;
				}
			}
		}
		boolean functional = abstractMethods == 1;
		synchronized (FUNCTIONAL_INTERFACES) {
			FUNCTIONAL_INTERFACES.put(iface, functional);
		}
		return functional;
	}

	// isFunctionalInterface's answers, by type: weak, as a compile's types die with it.
	private static final Map<JavaType, Boolean> FUNCTIONAL_INTERFACES = new java.util.WeakHashMap<>();

	/**
	 * A method of the interface: one name and parameter list, and its return-type
	 * variants.
	 */
	record Group(String name, List<JavaType> parameterTypes, List<Variant> variants) {

		String key() {
			return JavaImplementation.key(this.name, this.parameterTypes);
		}

	}

	/**
	 * One return type of a method; it must be implemented when a declaration of it is
	 * abstract, or when two interfaces supply a default for it (which a class must
	 * resolve by overriding).
	 */
	record Variant(JavaType returnType, boolean mustImplement) {
	}

	static Map<String, Group> groups(JavaType iface) {
		return groups(List.of(iface));
	}

	// The interfaces' instance methods by key, in key order; each key's variants by
	// return type name.
	static Map<String, Group> groups(List<JavaType> interfaces) {
		Map<String, List<JavaExecutable>> byKey = new TreeMap<>();
		for (JavaType iface : interfaces) {
			for (JavaExecutable method : iface.publicMethods()) {
				if (!method.isStatic()) {
					byKey
						.computeIfAbsent(JavaImplementation.key(method.name(), method.parameterTypes()),
								k -> new ArrayList<>())
						.add(method);
				}
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
	static Map<String, Group> objectGroups(JavaClassLookup lookup) {
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

	private JavaInterfaceMethods() {
	}

}
