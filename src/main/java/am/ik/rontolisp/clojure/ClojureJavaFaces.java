package am.ik.rontolisp.clojure;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The face a {@code deftype}, {@code reify} or {@code defrecord} value shows Java: the
 * object {@code %clojure-host-member} hands Java for it, an implementation of the Java
 * interfaces its body implements that stands for the value
 * ({@code .kb/clojure-frontend.md}, "Java faces"). The oracle's type IS a class
 * implementing them, so Java calls its methods, its {@code equals} and {@code hashCode}
 * key a {@code HashSet}, its {@code compareTo} orders a {@code TreeSet}, and it comes
 * back as itself. A record's class is a {@code java.util.Map} too: its face's {@code Map}
 * methods, {@code equals}, {@code hashCode} and {@code toString} are those of the
 * record's map view, the object Java sees of a record with no face.
 *
 * <p>
 * The lowering knows the interfaces where it defines the type, so it makes the face with
 * a maker over literal interface names: {@code (lambda (x) (java:reify '("I" ...) :value
 * x :class "C" "m(P)" (lambda (a) (... (funcall (%clojure-interface-entry x "m") x a)))
 * ... :java-false))}, each named method calling the type's own row method -- the body's,
 * or the refusal of an abstract one it leaves out -- so the JVM resolves it to a
 * generated class and nothing reflects, and the interpreter builds the same object
 * through its {@code Proxy}. A default method the body leaves out keeps Java's body.
 * Every face is a {@code Comparable}, as every identity handle is: a type implementing no
 * {@code Comparable} refuses {@code compareTo} with the oracle's
 * {@code ClassCastException} words. A face's result reaches Java as the oracle's object
 * ({@code %clojure-host-member}; a boolean by truth, an {@code Iterator} as a Java one, a
 * {@code Map}'s {@code entrySet} as Java's entries).
 *
 * <p>
 * The maker is stored in the type's row, under {@code "%java"}, by
 * {@code (%clojure-java-face-tag TAG maker)} around the tag of the type's first row store
 * -- the producer of {@link ClojureArms.Family#JAVA_FACE}, whose test is
 * {@code %clojure-host-member}'s clause. Only a program naming a {@code java:} operator
 * hands a value to Java, so a file's registrations wait for the end of its lowering
 * ({@link #settle}) and stay out of any other program, which lowers as before; a
 * session's take effect at once, since a later buffer may hand the value over.
 */
final class ClojureJavaFaces {

	/**
	 * The registration of a type's maker: {@code (%clojure-java-face-tag tag maker)},
	 * answering the tag.
	 */
	static final String FACE_TAG = "RONTOLISP::%CLOJURE-JAVA-FACE-TAG";

	/** The test of a value whose type has a face. */
	static final String FACE_P = "RONTOLISP::%CLOJURE-JAVA-FACE-P";

	/** A Java {@code Iterator} a face's method answers: a seq iterator's own face. */
	private static final String HOST_ITERATOR = "RONTOLISP::%CLOJURE-HOST-ITERATOR";

	/**
	 * What a {@code java.util.Map} face's {@code entrySet} answers: its {@code [k v]}
	 * members as Java's {@code Map.Entry}.
	 */
	private static final String HOST_ENTRY_SET = "RONTOLISP::%CLOJURE-HOST-ENTRY-SET";

	/** What a face's method answers Java for a value of a reference type. */
	private static final String HOST_MEMBER = "RONTOLISP::%CLOJURE-HOST-MEMBER";

	/** {@code Comparable}, which every face implements. */
	private static final String COMPARABLE = "java.lang.Comparable";

	/** The read-only {@code java.util.Map} view of a record. */
	private static final String HOST_RECORD_VIEW = "RONTOLISP::%CLOJURE-HOST-RECORD-VIEW";

	/** {@code java.util.Map}, which a record's face implements. */
	private static final String MAP = "java.util.Map";

	/** {@code Comparable.compareTo}'s key. */
	private static final String COMPARE_TO = "compareTo(java.lang.Object)";

	/**
	 * {@code Object}'s overridable methods, by key, in key order: the face's designators
	 * follow it, so a program compiles to the same bytes on every run.
	 */
	private static final Map<String, String> OBJECT_KEYS = objectKeys();

	private static Map<String, String> objectKeys() {
		Map<String, String> keys = new LinkedHashMap<>();
		keys.put("equals(java.lang.Object)", "equals");
		keys.put("hashCode()", "hashCode");
		keys.put("toString()", "toString");
		return java.util.Collections.unmodifiableMap(keys);
	}

	private ClojureJavaFaces() {
	}

	/**
	 * A registration waiting for the end of the lowering: the cell holding the tag form
	 * of the type's first row store, and what the maker is built from -- built only when
	 * it is registered, so a program it stays out of lowers as before, temporaries and
	 * all.
	 *
	 * @param cell the cell whose car is the tag form
	 * @param body the body's interfaces and methods
	 * @param listed the loadable Java interfaces of the body's closure, in its order
	 * @param className the class Java's messages name the value by
	 * @param record whether the value is a record, whose face is a {@code Map}
	 */
	record Pending(LispCons cell, ClojureInterfaces.InterfaceBody body, List<Class<?>> listed, String className,
			boolean record) {
	}

	/**
	 * Gives a {@code deftype}, {@code reify} or {@code defrecord} body its face: records
	 * the registration of its maker on the tag of its first row store, which a session
	 * makes at once. A body implementing no Java interface and overriding no
	 * {@code Object} method has none (Java sees an object equal only to itself), nor has
	 * a record implementing no Java interface (Java sees its map view), nor a program
	 * compiled where the host is not.
	 * @param ctx the hub
	 * @param what the defining form
	 * @param body the body's interfaces and methods
	 * @param className the class Java's messages name the value by
	 * @param stores the body's row stores ({@link ClojureInterfaces#rowForms})
	 */
	static void give(ClojureLowering ctx, String what, ClojureInterfaces.InterfaceBody body, String className,
			List<LispVal> stores) {
		if (!ctx.hostTarget || stores.isEmpty() || !(stores.get(0) instanceof LispCons store)
				|| !(store.cdr() instanceof LispCons cell)) {
			return;
		}
		List<Class<?>> listed = new ArrayList<>();
		for (ClojureInterfaces.HostInterface one : body.closure()) {
			Class<?> iface = javaInterface(one.name());
			if (iface != null && !listed.contains(iface)) {
				listed.add(iface);
			}
		}
		boolean record = "defrecord".equals(what);
		boolean overrides = false;
		for (String method : OBJECT_KEYS.values()) {
			overrides |= body.methods().containsKey(method);
		}
		if (listed.isEmpty() && (record || !overrides)) {
			// a record's Object overrides reach Java through its map view
			return;
		}
		if (record && !listed.contains(java.util.Map.class)) {
			listed.add(0, java.util.Map.class);
		}
		Pending pending = new Pending(cell, body, List.copyOf(listed), className, record);
		if (ctx.session) {
			register(ctx, pending);
		}
		else {
			ctx.javaFaces.add(pending);
		}
	}

	/**
	 * Registers every face the lowering recorded when the program names a {@code java:}
	 * operator, the only way a value reaches Java; drops them otherwise.
	 * @param ctx the hub, its lowering done
	 */
	static void settle(ClojureLowering ctx) {
		if (ctx.hostTarget && ctx.namedHost) {
			for (Pending pending : ctx.javaFaces) {
				register(ctx, pending);
			}
		}
		ctx.javaFaces.clear();
	}

	private static void register(ClojureLowering ctx, Pending pending) {
		LispCons cell = pending.cell();
		cell.setCar(ClojureLowerUtil.list(new LispSymbol(FACE_TAG), cell.car(),
				maker(ctx, pending.body(), pending.listed(), pending.className(), pending.record())));
	}

	/**
	 * The maker of a body's face: a {@code java:reify} over its Java interfaces and
	 * {@code Comparable}, standing for the value; a record's over its map view too, made
	 * once per face.
	 */
	private static LispVal maker(ClojureLowering ctx, ClojureInterfaces.InterfaceBody body, List<Class<?>> given,
			String className, boolean record) {
		List<Class<?>> listed = new ArrayList<>(given);
		Class<?> comparable = Comparable.class;
		boolean comparableRow = listed.contains(comparable);
		if (!comparableRow) {
			listed.add(comparable);
		}
		List<Class<?>> interfaces = mostSpecific(listed);
		LispSymbol self = ctx.freshTemp();
		LispSymbol view = record ? ctx.freshTemp() : null;
		List<LispVal> parts = new ArrayList<>();
		parts.add(new LispSymbol(LispNames.JAVA_REIFY_QUALIFIED));
		List<LispVal> names = new ArrayList<>();
		for (Class<?> iface : listed) {
			names.add(LispString.literal(iface.getName()));
		}
		parts.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(names)));
		parts.add(new LispSymbol(LispNames.JAVA_VALUE_OPTION));
		parts.add(self);
		parts.add(new LispSymbol(LispNames.JAVA_CLASS_OPTION));
		parts.add(LispString.literal(className));
		Map<String, List<Method>> declared = methods(interfaces);
		for (Map.Entry<String, List<Method>> group : declared.entrySet()) {
			String key = group.getKey();
			// the declaration whose return type a slot's answer is converted for: the
			// first by return type name, so every run picks the same one
			Method method = group.getValue().get(0);
			boolean abstractMethod = false;
			for (Method declaration : group.getValue()) {
				abstractMethod |= Modifier.isAbstract(declaration.getModifiers());
			}
			LispVal call;
			if (!comparableRow && COMPARE_TO.equals(key)) {
				call = notComparable(ctx, className);
			}
			else if (defines(body, method)) {
				call = rowCall(ctx, self, method);
			}
			else if (view != null
					&& (OBJECT_KEYS.containsKey(key) || abstractMethod && declaresMap(group.getValue()))) {
				// a record's Map, its equals and its hashCode: its map view's
				call = viewCall(ctx, view, method);
			}
			else if (!OBJECT_KEYS.containsKey(key) && abstractMethod && (body.methods().containsKey(method.getName())
					|| ClojureInterfaces.storesRefusal(body.closure(), method.getName()))) {
				// the refusal the row holds for an abstract method the body leaves out
				// (the oracle's AbstractMethodError)
				call = rowCall(ctx, self, method);
			}
			else {
				// a default method the body leaves out keeps Java's body, and an Object
				// method Object's
				continue;
			}
			parts.add(LispString.literal(key));
			parts.add(call);
		}
		for (Map.Entry<String, String> object : OBJECT_KEYS.entrySet()) {
			// an Object method no interface redeclares
			if (declared.containsKey(object.getKey())) {
				continue;
			}
			if (body.methods().containsKey(object.getValue())) {
				parts.add(LispString.literal(object.getKey()));
				parts.add(rowCall(ctx, self, objectMethod(object.getKey())));
			}
			else if (view != null) {
				// a record's toString: its map view's, its str
				parts.add(LispString.literal(object.getKey()));
				parts.add(viewCall(ctx, view, objectMethod(object.getKey())));
			}
		}
		parts.add(new LispSymbol(LispNames.JAVA_FALSE_MARKER));
		LispVal face = ClojureLowerUtil.list(parts);
		if (view != null) {
			face = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(
							ClojureLowerUtil.list(view, ClojureLowerUtil.list(new LispSymbol(HOST_RECORD_VIEW), self))),
					face);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(self), face);
	}

	/** Whether {@code java.util.Map} declares one of a group's methods. */
	private static boolean declaresMap(List<Method> group) {
		for (Method method : group) {
			if (method.getDeclaringClass() == java.util.Map.class) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A slot's function calling the record's map view's method on Java's arguments, each
	 * as Java takes it, the answer as {@link #rowCall}'s. The view is declared a
	 * {@code Map}, so the call resolves and nothing reflects.
	 */
	private static LispVal viewCall(ClojureLowering ctx, LispSymbol view, Method method) {
		List<LispVal> params = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(LispNames.JAVA_CALL_QUALIFIED));
		call.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("the"),
				ClojureLowerUtil.list(new LispSymbol(LispNames.JAVA_OBJECT_QUALIFIED), LispString.literal(MAP)), view));
		call.add(LispString.literal(method.getName()));
		for (int i = 0; i < method.getParameterCount(); i++) {
			LispSymbol param = ctx.freshTemp();
			params.add(param);
			call.add(ClojureLowerUtil.list(new LispSymbol(HOST_MEMBER), param));
		}
		boolean reference = !method.getReturnType().isPrimitive();
		if (reference) {
			// a false member answers Clojure's false, not nil
			call.add(new LispSymbol(LispNames.JAVA_FALSE_MARKER));
		}
		LispVal answer = ClojureLowerUtil.list(call);
		if (reference) {
			answer = ClojureLowerUtil.list(new LispSymbol(HOST_MEMBER), answer);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(params), answer);
	}

	/**
	 * Whether the body defines the Java method: its name at the method's parameter count,
	 * the target counted, so an overload it leaves out (a {@code java.util.Map}'s default
	 * {@code remove(key, value)} beside its {@code remove(key)}) keeps Java's body.
	 */
	private static boolean defines(ClojureInterfaces.InterfaceBody body, Method method) {
		List<ClojureLowering.MethodArity> arities = body.methods().get(method.getName());
		if (arities == null) {
			return false;
		}
		for (ClojureLowering.MethodArity arity : arities) {
			if (ClojureBindingLowering.paramShape(arity.params()).fixed() == method.getParameterCount() + 1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A slot's function calling the type's row method on the value and Java's arguments,
	 * its answer as Java takes it.
	 */
	private static LispVal rowCall(ClojureLowering ctx, LispSymbol self, Method method) {
		List<LispVal> params = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowerUtil.sym("funcall"));
		call.add(ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.ENTRY), self,
				LispString.literal(method.getName())));
		call.add(self);
		for (int i = 0; i < method.getParameterCount(); i++) {
			LispSymbol param = ctx.freshTemp();
			params.add(param);
			call.add(param);
		}
		LispVal answer = ClojureLowerUtil.list(call);
		Class<?> returned = method.getReturnType();
		if (returned == boolean.class) {
			// the oracle's booleanCast: a value answers by its truth
			answer = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TRUTHY"), answer);
		}
		else if (returned == java.util.Iterator.class) {
			answer = ClojureLowerUtil.list(new LispSymbol(HOST_ITERATOR), answer);
		}
		else if ("entrySet".equals(method.getName()) && method.getParameterCount() == 0
				&& java.util.Map.class.isAssignableFrom(method.getDeclaringClass())) {
			// a Map's entries are Java's Map.Entry, where a map entry here is a vector
			answer = ClojureLowerUtil.list(new LispSymbol(HOST_ENTRY_SET), answer);
		}
		else if (!returned.isPrimitive()) {
			answer = ClojureLowerUtil.list(new LispSymbol(HOST_MEMBER), answer);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(params), answer);
	}

	/**
	 * The {@code compareTo} of a face whose type implements no {@code Comparable}: the
	 * oracle's cast failure, as an identity handle's.
	 */
	private static LispVal notComparable(ClojureLowering ctx, String className) {
		LispSymbol other = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(other),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), other)),
				ClojureRefusals.refusal(ClojureRefusals.CLASS_CAST,
						LispString.literal("class " + className + " cannot be cast to class " + COMPARABLE)));
	}

	/** {@code Object}'s method of a key. */
	private static Method objectMethod(String key) {
		try {
			return switch (key) {
				case "equals(java.lang.Object)" -> Object.class.getMethod("equals", Object.class);
				case "hashCode()" -> Object.class.getMethod("hashCode");
				default -> Object.class.getMethod("toString");
			};
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * The loadable Java interface a body's interface name names, or null: a
	 * {@code clojure.lang} one is the oracle's, which no Java code here is written
	 * against.
	 */
	private static @Nullable Class<?> javaInterface(String name) {
		if (name.startsWith("clojure.lang.")) {
			return null;
		}
		try {
			Class<?> loaded = ClojureHostClasses.load(name);
			return loaded.isInterface() ? loaded : null;
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return null;
		}
	}

	/**
	 * The interfaces a face implements: those no other one of them extends, in order (the
	 * {@code java:reify} rule, {@code compiler/JavaImplementations.mostSpecific}).
	 */
	private static List<Class<?>> mostSpecific(List<Class<?>> interfaces) {
		List<Class<?>> out = new ArrayList<>();
		for (Class<?> iface : interfaces) {
			boolean implied = false;
			for (Class<?> other : interfaces) {
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
	 * The instance methods the interfaces declare, by key in key order -- what
	 * {@code java:reify} groups them by -- each key's declarations (a bridge aside) in
	 * return type, then declaring class, order.
	 */
	private static Map<String, List<Method>> methods(List<Class<?>> interfaces) {
		Map<String, List<Method>> out = new java.util.TreeMap<>();
		for (Class<?> iface : interfaces) {
			for (Method method : iface.getMethods()) {
				if (!Modifier.isStatic(method.getModifiers()) && !method.isBridge()) {
					List<Method> group = out.computeIfAbsent(key(method), ignored -> new ArrayList<>());
					if (!group.contains(method)) {
						group.add(method);
					}
				}
			}
		}
		for (List<Method> group : out.values()) {
			group.sort(java.util.Comparator.comparing((Method m) -> m.getReturnType().getName())
				.thenComparing(m -> m.getDeclaringClass().getName()));
		}
		return new LinkedHashMap<>(out);
	}

	/** A method's key, as a designator tags it: {@code name(p1,p2)}. */
	private static String key(Method method) {
		List<String> params = new ArrayList<>();
		for (Class<?> param : method.getParameterTypes()) {
			params.add(param.getName());
		}
		return method.getName() + "(" + String.join(",", params) + ")";
	}

}
