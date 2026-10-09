package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ClassDefinition;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.JvmClassSplitter;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaImplementation;
import am.ik.rontolisp.compiler.JavaImplementations;
import am.ik.rontolisp.compiler.JavaMarkers;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;

/**
 * The classes a compiled program implements host interfaces with, generated at compile
 * time and shipped beside it -- no {@code java.lang.reflect.Proxy}, nothing defined at
 * run time, so a GraalVM native image needs no metadata for them
 * ({@code .kb/java-interop.md}, "Implementing interfaces"). One class per
 * {@code java:reify} shape ({@code <Program>$Reify<N>}) and per interface list a
 * {@code java:proxy} -- or a function value passed where an interface is expected --
 * becomes ({@code <Program>$Proxy<N>}, implementing each interface of the list),
 * declaring exactly the slots {@link JavaImplementations} chose -- the ones the
 * interpreter's {@code Proxy} handler dispatches on:
 *
 * <pre>
 * abstract class Prog$Implementation implements java.io.Serializable {
 *     final Object[] fns;
 *     Prog$Implementation(Object[] fns) { this.fns = fns; }
 * }
 * final class Prog$Reify0 extends Prog$Implementation implements I {
 *     static Object of(Object[] fns) { return new Prog$Reify0(fns); }
 *     public R m(P p) { return Prog._jimpl$K(this.fns[i], new Object[] { box(p) }); }
 *     public R n() { throw new UnsupportedOperationException("java:reify: no implementation of I.n()"); }
 *     public String toString() { return "#<java-reify I>"; }
 * }
 * </pre>
 *
 * The common superclass is what a direct call tests an argument counted as an
 * implementation of {@code I} against ({@link #baseClass()}): an object of one of these
 * classes, never any other {@code I}. Its supertypes are a {@code Proxy} class's, less
 * {@code Proxy}, so the object costs what the interpreter's {@code Proxy} object costs
 * ({@code compiler.JavaImplementationType}).
 * <p>
 * Everything that knows the Lisp representation stays in the program class, in one
 * package-private {@code static R _jimpl$K(Object fn, Object[] args)} per implemented
 * method: the arguments unmarshalled ({@code _junm}) into a list -- a proxy's with the
 * method's name first -- the function applied ({@code _apply}), and its value converted
 * to the method's return type by the direct sites' per-type helpers
 * ({@link JvmJavaDirectSites#returnedCost}, {@link JvmJavaDirectSites#returnedConvert}:
 * the bridge's {@code marshal}, a function never made a proxy on the way back) or refused
 * with the interpreter's message. The generated classes call only those, so the program
 * keeps them in its own class when it is split and roots them for the tree-shaker
 * ({@link #callbackNames()}). What leaves a callback thrown is recorded on its way out to
 * the Java caller by the program's {@code _jsig}, so the site whose Java call it reaches
 * throws it on unchanged ({@link JvmJavaDirectSites#finishHelpers}).
 */
final class JvmJavaImplementations {

	/** The prefix of a program-side method a generated class calls. */
	static final String CALLBACK_PREFIX = "_jimpl$";

	/** The descriptor of a generated class's factory. */
	private static final String FACTORY_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String FACTORY = "of";

	private final ConstantPool cp;

	private final ClassEntry thisClass;

	private final String programInternalName;

	private final JavaClassLookup lookup;

	private final JvmJavaDirectSites direct;

	private final MethodRefEntry lispToString;

	/** The generated classes by shape, in the order they were first asked for. */
	private final Map<String, Shell> shells = new LinkedHashMap<>();

	/** The program-side methods by (proxy or not, interface, method, return type). */
	private final Map<String, String> callbacks = new LinkedHashMap<>();

	private final List<JvmJavaDirectSites.Method> methods = new ArrayList<>();

	private int reifies;

	private int proxies;

	private int subclasses;

	/** The generated subclasses by shape, in the order they were first asked for. */
	private final Map<String, Subshell> subshells = new LinkedHashMap<>();

	/** Whether a direct call tests an argument against {@link #baseClass()}. */
	private boolean baseTested;

	/**
	 * Whether the program makes or reads a {@code java:handle} ({@link #handleClass()}).
	 */
	private boolean handles;

	/**
	 * One generated class: its name, what it implements, and each slot's callback.
	 *
	 * @param internalName the class's internal name
	 * @param implementation its slots
	 * @param callbacks each slot's program-side method name, in slot order ({@code null}
	 * for a slot that throws)
	 */
	private record Shell(String internalName, JavaImplementation implementation, List<@Nullable String> callbacks) {
	}

	/**
	 * @param cp the program class's constant pool
	 * @param thisClass the program class
	 * @param programInternalName the program class's internal name: the generated classes
	 * are named after it, in its package
	 * @param lookup the classes the program resolves against
	 * @param direct the direct sites, whose {@code _junm} unmarshals an argument
	 * @param lispToString the program's {@code _lispToString}
	 */
	JvmJavaImplementations(ConstantPool cp, ClassEntry thisClass, String programInternalName, JavaClassLookup lookup,
			JvmJavaDirectSites direct, MethodRefEntry lispToString) {
		this.cp = cp;
		this.thisClass = thisClass;
		this.programInternalName = programInternalName;
		this.lookup = lookup;
		this.direct = direct;
		this.lispToString = lispToString;
	}

	/**
	 * The common superclass of the generated classes: an object of one is what an
	 * argument a resolution counted as an implementation of an interface is.
	 * @return its internal name
	 */
	String baseClass() {
		this.baseTested = true;
		return this.programInternalName + "$Implementation";
	}

	/** The suffix of the generated class a {@code java:handle} makes an object of. */
	static final String HANDLE_SUFFIX = "$Handle";

	/**
	 * The class a {@code (java:handle value "text" hash "order")} makes an object of,
	 * {@code <Program>$Handle}: the value, the text, the hash and the order text in four
	 * fields, the text its {@code toString}, its {@code equals}, {@code hashCode} and
	 * {@code compareTo} (the eval package's {@code JavaHandle}, member for member).
	 * Asking for it ships it beside the program.
	 * @return its internal name
	 */
	String handleClass() {
		this.handles = true;
		return this.programInternalName + HANDLE_SUFFIX;
	}

	// final class <Program>$Handle implements Comparable: Object value, String text, int
	// hash, String order; toString the text; equals over the text, hashCode the hash,
	// compareTo over the order (a non-handle compared is a ClassCastException, as a
	// Comparable of another class throws).
	private static ClassDefinition writeHandle(String internalName) {
		ConstantPool pool = new ConstantPool();
		ClassEntry selfClass = pool.classEntry(internalName);
		ClassEntry objectClass = pool.classEntry("java/lang/Object");
		ClassEntry stringClass = pool.classEntry("java/lang/String");
		ClassDefinition.Builder definition = ClassDefinition.builder(pool,
				AccessFlag.ACC_FINAL | AccessFlag.ACC_SUPER | AccessFlag.ACC_SYNTHETIC, selfClass, objectClass,
				pool.utf8Entry("Code"));
		definition.addInterface(pool.classEntry("java/lang/Comparable"));
		definition.addField(AccessFlag.ACC_FINAL, pool.utf8Entry("value"), pool.utf8Entry("Ljava/lang/Object;"));
		definition.addField(AccessFlag.ACC_FINAL, pool.utf8Entry("text"), pool.utf8Entry("Ljava/lang/String;"));
		definition.addField(AccessFlag.ACC_FINAL, pool.utf8Entry("hash"), pool.utf8Entry("I"));
		definition.addField(AccessFlag.ACC_FINAL, pool.utf8Entry("order"), pool.utf8Entry("Ljava/lang/String;"));
		FieldRefEntry value = pool.fieldRef(selfClass, "value", "Ljava/lang/Object;");
		FieldRefEntry text = pool.fieldRef(selfClass, "text", "Ljava/lang/String;");
		FieldRefEntry hash = pool.fieldRef(selfClass, "hash", "I");
		FieldRefEntry order = pool.fieldRef(selfClass, "order", "Ljava/lang/String;");
		// <init>(Object value, String text, int hash, String order)
		MethodCode init = new MethodCode();
		init.aload(0);
		init.invokespecial(pool.methodRef(objectClass, "<init>", "()V"));
		init.aload(0);
		init.aload(1);
		init.putfield(value);
		init.aload(0);
		init.aload(2);
		init.putfield(text);
		init.aload(0);
		init.iload(3);
		init.putfield(hash);
		init.aload(0);
		init.aload(4);
		init.putfield(order);
		init.return_();
		definition.addMethod(0, pool.utf8Entry("<init>"),
				pool.utf8Entry("(Ljava/lang/Object;Ljava/lang/String;ILjava/lang/String;)V"), init);
		MethodCode toString = new MethodCode();
		toString.aload(0);
		toString.getfield(text);
		toString.areturn();
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry("toString"), pool.utf8Entry("()Ljava/lang/String;"),
				toString);
		MethodCode equals = new MethodCode();
		MethodCode.Label other = equals.newLabel();
		equals.aload(1);
		equals.instanceOf(selfClass);
		equals.ifeq(other);
		equals.aload(0);
		equals.getfield(text);
		equals.aload(1);
		equals.checkcast(selfClass);
		equals.getfield(text);
		equals.invokevirtual(pool.methodRef(stringClass, "equals", "(Ljava/lang/Object;)Z"));
		equals.ireturn();
		equals.labelBinding(other);
		equals.loadConstant(0);
		equals.ireturn();
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry("equals"), pool.utf8Entry("(Ljava/lang/Object;)Z"),
				equals);
		MethodCode hashCode = new MethodCode();
		hashCode.aload(0);
		hashCode.getfield(hash);
		hashCode.ireturn();
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry("hashCode"), pool.utf8Entry("()I"), hashCode);
		MethodCode compareTo = new MethodCode();
		compareTo.aload(0);
		compareTo.getfield(order);
		compareTo.aload(1);
		compareTo.checkcast(selfClass);
		compareTo.getfield(order);
		compareTo.invokevirtual(pool.methodRef(stringClass, "compareTo", "(Ljava/lang/String;)I"));
		compareTo.ireturn();
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry("compareTo"),
				pool.utf8Entry("(Ljava/lang/Object;)I"), compareTo);
		return definition.build();
	}

	/**
	 * The factory of the class a resolved {@code java:reify} / {@code java:proxy}
	 * implements its interface with: {@code of(Object[] functions)}, the functions in
	 * implementation order, answering the new object.
	 * @param implementation a resolved implementation
	 * @return the factory, a method of the generated class
	 */
	MethodRefEntry factory(JavaImplementation implementation) {
		Shell shell = shell(implementation);
		return this.cp.methodRef(this.cp.classEntry(shell.internalName()), FACTORY, FACTORY_DESC);
	}

	/**
	 * The factory of the proxy class of an interface: what a function value passed where
	 * the interface is expected becomes, as a {@code java:proxy} of it.
	 * @param iface a linkable interface
	 * @return the factory, taking a one-element array holding the function
	 */
	MethodRefEntry proxyFactory(JavaType iface) {
		return proxyFactory(iface, JavaMarkers.NONE);
	}

	/**
	 * {@link #proxyFactory(JavaType)} at a site ending in these markers: after
	 * {@code :java-false} the callable is handed Java's {@code false} as {@code |false|}.
	 * @param iface a linkable interface
	 * @param markers the site's markers
	 * @return the factory, taking a one-element array holding the function
	 */
	MethodRefEntry proxyFactory(JavaType iface, JavaMarkers markers) {
		return factory(JavaImplementations.proxy(iface, this.lookup).withMarkers(markers));
	}

	/**
	 * The factory of the class a function value passed where the interface is expected at
	 * a site ending in {@code :functional} becomes: every abstract method calls the
	 * function with its arguments ({@link JavaImplementations#functional}), handed Java's
	 * {@code false} as {@code |false|} after {@code :java-false}.
	 * @param iface a linkable interface
	 * @param markers the site's markers
	 * @return the factory, taking a one-element array holding the function
	 */
	MethodRefEntry functionalFactory(JavaType iface, JavaMarkers markers) {
		return factory(JavaImplementations.functional(iface, this.lookup, markers));
	}

	/**
	 * The construction dispatcher of the class a resolved {@code java:subclass} extends
	 * its superclass with: {@code _jsubclass$N(Object callable, Object[]
	 * constructorArguments)}, answering the new object. The dispatcher chooses the
	 * superclass constructor when it runs -- the arguments' kinds are known only then --
	 * among the overloads the resolution allows, the first cheapest
	 * ({@link JavaOverloads#selectRanked}). A function constructor argument implements
	 * its interface by the method's arguments when the form ends in {@code :functional},
	 * not as a {@code java:proxy}; after {@code :java-false} every function is handed
	 * Java's {@code false} as {@code |false|} ({@link JavaImplementation#markers}).
	 * @param implementation a resolved subclass implementation
	 * @param argc the constructor argument count
	 * @return the dispatcher, a method of the program class
	 */
	MethodRefEntry subclassFactory(JavaImplementation implementation, int argc) {
		Subshell shell = subshell(implementation, argc);
		return this.cp.methodRef(this.thisClass, shell.construct(),
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
	}

	/**
	 * One generated subclass: its name, what it extends, and each slot's callback.
	 *
	 * @param internalName the class's internal name
	 * @param implementation its slots
	 * @param argc the constructor argument count
	 * @param overloads the superclass constructors it is built with
	 * @param callbacks each slot's program-side method name, in slot order ({@code null}
	 * for a slot that throws)
	 * @param construct the program-side construction dispatcher's name
	 */
	private record Subshell(String internalName, JavaImplementation implementation, int argc,
			List<JavaOverloads.Overload> overloads, List<@Nullable String> callbacks, String construct) {
	}

	/**
	 * @return the program-side callbacks to add to the class (the helpers they call are
	 * the direct sites')
	 */
	List<JvmJavaDirectSites.Method> methods() {
		return List.copyOf(this.methods);
	}

	/**
	 * @return the program-side methods only the generated classes call: package-private,
	 * kept in the program class when it is split, roots of the tree-shaker
	 */
	Set<String> callbackNames() {
		Set<String> names = new LinkedHashSet<>();
		for (Shell shell : this.shells.values()) {
			for (String callback : shell.callbacks()) {
				if (callback != null) {
					names.add(callback);
				}
			}
		}
		for (Subshell shell : this.subshells.values()) {
			for (String callback : shell.callbacks()) {
				if (callback != null) {
					names.add(callback);
				}
			}
		}
		return names;
	}

	/**
	 * The generated classes, written for the program's class-file version, keyed by their
	 * path within an output tree.
	 * @param target the program's class-file version and the class hierarchy its frames
	 * merge through
	 * @return the class files
	 */
	Map<String, byte[]> classFiles(JvmClassSplitter.Target target) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		if (this.handles) {
			String handle = this.programInternalName + HANDLE_SUFFIX;
			files.put(handle + ".class", written(writeHandle(handle), target));
		}
		if (this.baseTested || !this.shells.isEmpty()) {
			String base = this.programInternalName + "$Implementation";
			files.put(base + ".class", written(writeBase(base), target));
		}
		for (Shell shell : this.shells.values()) {
			files.put(shell.internalName() + ".class", written(write(shell), target));
		}
		for (Subshell shell : this.subshells.values()) {
			if (!shell.overloads().isEmpty()) {
				files.put(shell.internalName() + ".class", written(writeSubclass(shell), target));
			}
		}
		return files;
	}

	// One class, never split: every member stays where the interface finds it.
	private static byte[] written(ClassDefinition definition, JvmClassSplitter.Target target) {
		return JvmClassSplitter.write(definition, null, method -> true, ConstantPool.MAX_INDEX, target).mainClass();
	}

	private Shell shell(JavaImplementation implementation) {
		if (!implementation.resolved()) {
			throw new IllegalArgumentException("an unresolved implementation: " + implementation.reason());
		}
		String iface = implementation.interfaceNames();
		StringBuilder key = new StringBuilder(implementation.proxy() ? "proxy|" : "reify|").append(iface);
		for (JavaImplementation.Slot slot : implementation.slots()) {
			key.append('|').append(slot.dispatchKey()).append('=').append(slot.implementation());
			if (implementation.readsComparison(slot)) {
				// its function's answer read as a comparison (_jcmp)
				key.append(" compares");
			}
		}
		if (implementation.javaFalse()) {
			// its callbacks hand Java's false over as |false| (_junf)
			key.append("|java-false");
		}
		Shell cached = this.shells.get(key.toString());
		if (cached != null) {
			return cached;
		}
		String name = this.programInternalName
				+ (implementation.proxy() ? "$Proxy" + this.proxies++ : "$Reify" + this.reifies++);
		List<@Nullable String> callbacks = new ArrayList<>();
		for (JavaImplementation.Slot slot : implementation.slots()) {
			callbacks.add(slot.implementation() == JavaImplementation.NONE ? null : callback(implementation, slot));
		}
		Shell shell = new Shell(name, implementation, callbacks);
		this.shells.put(key.toString(), shell);
		return shell;
	}

	// The program-side method a slot calls, made the first time one of its shape asks:
	// one per (proxy or not, interfaces, method, return type, how Java's false reaches
	// the function, whether its answer is read as a comparison).
	private String callback(JavaImplementation implementation, JavaImplementation.Slot slot) {
		boolean proxy = implementation.proxy();
		String iface = implementation.interfaceNames();
		boolean javaFalse = implementation.javaFalse();
		boolean compares = implementation.readsComparison(slot);
		String key = (proxy ? "proxy|" : "reify|") + iface + "|" + slot.dispatchKey() + (javaFalse ? "|java-false" : "")
				+ (compares ? "|compares" : "");
		String cached = this.callbacks.get(key);
		if (cached != null) {
			return cached;
		}
		String name = CALLBACK_PREFIX + this.callbacks.size();
		this.callbacks.put(key, name);
		this.methods.add(buildCallback(proxy, iface, slot, javaFalse, compares, this.cp.utf8Entry(name),
				this.cp.utf8Entry(callbackDescriptor(slot))));
		return name;
	}

	private static String callbackDescriptor(JavaImplementation.Slot slot) {
		return "(Ljava/lang/Object;[Ljava/lang/Object;)" + JvmJavaDirectSites.descriptor(slot.returnType());
	}

	private Subshell subshell(JavaImplementation implementation, int argc) {
		if (!implementation.resolved() || !implementation.isSubclass()) {
			throw new IllegalArgumentException("not a resolved java:subclass");
		}
		JavaType superclass = java.util.Objects.requireNonNull(implementation.superclass());
		StringBuilder key = new StringBuilder("subclass|").append(superclass.name())
			.append('|')
			.append(implementation.interfaceNames());
		for (JavaImplementation.Slot slot : implementation.slots()) {
			key.append('|').append(slot.dispatchKey()).append('=').append(slot.implementation());
		}
		key.append('#').append(argc).append(implementation.markers().functional() ? " functional" : "");
		if (implementation.javaFalse()) {
			key.append(" java-false");
		}
		Subshell cached = this.subshells.get(key.toString());
		if (cached != null) {
			return cached;
		}
		String name = this.programInternalName + "$Subclass" + this.subclasses++;
		List<JavaOverloads.Overload> overloads = subclassOverloads(superclass, argc);
		List<@Nullable String> callbacks = new ArrayList<>();
		for (JavaImplementation.Slot slot : implementation.slots()) {
			callbacks
				.add(slot.implementation() == JavaImplementation.NONE ? null : subclassCallback(implementation, slot));
		}
		String construct = "_jsubclass$" + this.methods.size();
		this.methods.add(buildSubclassConstruct(implementation, overloads, argc, name, this.cp.utf8Entry(construct),
				this.cp.utf8Entry("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")));
		Subshell shell = new Subshell(name, implementation, argc, overloads, callbacks, construct);
		this.subshells.put(key.toString(), shell);
		return shell;
	}

	// The superclass constructors a generated subclass is built with: every arity
	// match (a varargs one packed too), in the order selectRanked breaks a cost tie
	// by, whose parameters a compiled program can name.
	private static List<JavaOverloads.Overload> subclassOverloads(JavaType superclass, int argc) {
		List<JavaOverloads.Overload> overloads = new ArrayList<>();
		for (JavaOverloads.Overload overload : JavaOverloads.ranked(superclass.subclassConstructors(), argc)) {
			boolean linkable = true;
			for (JavaType parameter : overload.executable().parameterTypes()) {
				if (!parameter.isLinkable()) {
					linkable = false;
					break;
				}
			}
			if (linkable) {
				overloads.add(overload);
			}
		}
		return overloads;
	}

	// The program-side method a subclass slot calls, made the first time one of its
	// shape asks: one per (superclass, interfaces, method, return type, how Java's false
	// reaches the callable).
	private String subclassCallback(JavaImplementation implementation, JavaImplementation.Slot slot) {
		JavaType superclass = java.util.Objects.requireNonNull(implementation.superclass());
		String key = "subclass|" + superclass.name() + "|" + implementation.interfaceNames() + "|" + slot.dispatchKey()
				+ (implementation.javaFalse() ? "|java-false" : "");
		String cached = this.callbacks.get(key);
		if (cached != null) {
			return cached;
		}
		String name = CALLBACK_PREFIX + this.callbacks.size();
		this.callbacks.put(key, name);
		this.methods.add(buildSubclassCallback(implementation, slot, this.cp.utf8Entry(name),
				this.cp.utf8Entry(subclassCallbackDescriptor(slot))));
		return name;
	}

	private static String subclassCallbackDescriptor(JavaImplementation.Slot slot) {
		return "(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)"
				+ JvmJavaDirectSites.descriptor(slot.returnType());
	}

	private ClassEntry cls(String internalName) {
		return this.cp.classEntry(internalName);
	}

	private MethodRefEntry method(String owner, String name, String desc) {
		return this.cp.methodRef(cls(owner), name, desc);
	}

	// static R _jimpl$K(Object fn, Object[] args): (fn [name] (_junm args[0]) ...) --
	// _junf after :java-false -- then the value converted to R -- or the interpreter's
	// error for one that does not; a comparison's answer read first by _jcmp. What
	// leaves it thrown -- the function's exit or condition, that error -- is recorded on
	// its way out to the Java caller (_jsig), for the site whose Java call it reaches.
	private JvmJavaDirectSites.Method buildCallback(boolean proxy, String iface, JavaImplementation.Slot slot,
			boolean javaFalse, boolean compares, Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		MethodCode.Label start = a.newBoundLabel();
		ClassEntry objectClass = cls("java/lang/Object");
		MethodRefEntry unmarshal = this.direct.unmarshalHelper(javaFalse);
		MethodRefEntry signal = this.direct.signalHelper();
		// 2 = the argument list, 3 = i, 4 = the value
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aconst_null();
		a.astore(2);
		a.aload(1);
		a.arraylength();
		a.istore(3);
		a.labelBinding(loop);
		a.iinc(3, -1);
		a.iload(3);
		a.iflt(done);
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(1);
		a.iload(3);
		a.aaload();
		a.invokestatic(unmarshal);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(2);
		a.aastore();
		a.astore(2);
		a.goto_(loop);
		a.labelBinding(done);
		if (proxy) {
			// A proxy's function takes the method's name first.
			a.loadConstant(2);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.ldc(this.cp.stringEntry("\"" + slot.name() + "\""));
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.aload(2);
			a.aastore();
			a.astore(2);
		}
		a.aload(0);
		a.aload(2);
		a.invokestatic(this.cp.methodRef(this.thisClass, "_apply",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		JavaType returnType = slot.returnType();
		if ("void".equals(returnType.name())) {
			a.pop();
			a.return_();
		}
		else {
			a.astore(4);
			if (compares) {
				// Integer c = _jcmp(fn, args, value); if (c != null) return c.intValue();
				MethodCode.Label notCompared = a.newLabel();
				a.aload(0);
				a.aload(2);
				a.aload(4);
				a.invokestatic(this.direct.comparisonHelper());
				a.astore(5);
				a.aload(5);
				a.ifnull(notCompared);
				a.aload(5);
				a.invokevirtual(method("java/lang/Integer", "intValue", "()I"));
				a.return_(returnKind(returnType));
				a.labelBinding(notCompared);
			}
			MethodCode.Label fits = a.newLabel();
			a.aload(4);
			a.invokestatic(this.direct.returnedCost(returnType));
			a.ifge(fits);
			MethodRefEntry concat = method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
			a.new_(cls("java/lang/RuntimeException"));
			a.dup();
			a.ldc(this.cp.stringEntry(JavaImplementation.returnMismatchPrefix(proxy)));
			a.aload(4);
			a.invokestatic(this.lispToString);
			a.invokevirtual(concat);
			a.ldc(this.cp.stringEntry(JavaImplementation.returnMismatchSuffix(proxy, iface, slot.name(), returnType)));
			a.invokevirtual(concat);
			a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
			a.athrow();
			a.labelBinding(fits);
			a.aload(4);
			a.invokestatic(this.direct.returnedConvert(returnType));
			a.return_(returnKind(returnType));
		}
		MethodCode.Label end = a.newBoundLabel();
		a.invokestatic(signal);
		a.athrow();
		a.exceptionCatch(start, end, end, cls("java/lang/Throwable"));
		return new JvmJavaDirectSites.Method(name, desc, a);
	}

	private static TypeKind returnKind(JavaType type) {
		return switch (type.name()) {
			case "boolean", "byte", "char", "short", "int" -> TypeKind.INT;
			case "long" -> TypeKind.LONG;
			case "float" -> TypeKind.FLOAT;
			case "double" -> TypeKind.DOUBLE;
			case "void" -> TypeKind.VOID;
			default -> TypeKind.REFERENCE;
		};
	}

	// static R _jsub$K(Object fn, Object self, Object[] args): (fn self name (_junm
	// args[0]) ...), then the value converted to R -- or the interpreter's error for
	// one that does not. What leaves it thrown is recorded on its way out to the Java
	// caller (_jsig), for the site whose Java call it reaches.
	private JvmJavaDirectSites.Method buildSubclassCallback(JavaImplementation implementation,
			JavaImplementation.Slot slot, Utf8Entry name, Utf8Entry desc) {
		MethodCode a = new MethodCode();
		MethodCode.Label start = a.newBoundLabel();
		ClassEntry objectClass = cls("java/lang/Object");
		MethodRefEntry unmarshal = this.direct.unmarshalHelper(implementation.javaFalse());
		MethodRefEntry signal = this.direct.signalHelper();
		// 3 = the argument list, 4 = i, 5 = the value
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aconst_null();
		a.astore(3);
		a.aload(2);
		a.arraylength();
		a.istore(4);
		a.labelBinding(loop);
		a.iinc(4, -1);
		a.iload(4);
		a.iflt(done);
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(2);
		a.iload(4);
		a.aaload();
		a.invokestatic(unmarshal);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(3);
		a.aastore();
		a.astore(3);
		a.goto_(loop);
		a.labelBinding(done);
		// A subclass's function takes the object, then the method's name: nested cons
		// cells, as the unmarshalled arguments already are.
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.ldc(this.cp.stringEntry("\"" + slot.name() + "\""));
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(3);
		a.aastore();
		a.astore(3);
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(1);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(3);
		a.aastore();
		a.astore(3);
		a.aload(0);
		a.aload(3);
		a.invokestatic(this.cp.methodRef(this.thisClass, "_apply",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		JavaType returnType = slot.returnType();
		JavaType superclass = java.util.Objects.requireNonNull(implementation.superclass());
		if ("void".equals(returnType.name())) {
			a.pop();
			a.return_();
		}
		else {
			a.astore(5);
			MethodCode.Label fits = a.newLabel();
			a.aload(5);
			a.invokestatic(this.direct.returnedCost(returnType));
			a.ifge(fits);
			MethodRefEntry concat = method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
			a.new_(cls("java/lang/RuntimeException"));
			a.dup();
			a.ldc(this.cp.stringEntry(JavaImplementation.subclassReturnMismatchPrefix()));
			a.aload(5);
			a.invokestatic(this.lispToString);
			a.invokevirtual(concat);
			a.ldc(this.cp.stringEntry(JavaImplementation.subclassReturnMismatchSuffix(superclass,
					implementation.interfaces(), returnType)));
			a.invokevirtual(concat);
			a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
			a.athrow();
			a.labelBinding(fits);
			a.aload(5);
			a.invokestatic(this.direct.returnedConvert(returnType));
			a.return_(returnKind(returnType));
		}
		MethodCode.Label end = a.newBoundLabel();
		a.invokestatic(signal);
		a.athrow();
		a.exceptionCatch(start, end, end, cls("java/lang/Throwable"));
		return new JvmJavaDirectSites.Method(name, desc, a);
	}

	// static Object _jsubclass$N(Object fn, Object[] args): the cheapest superclass
	// constructor for the arguments' kinds -- the first of the ranked ones on a tie,
	// like a dispatched site -- called with the converted arguments on a new object
	// of the generated subclass carrying the callable.
	private JvmJavaDirectSites.Method buildSubclassConstruct(JavaImplementation implementation,
			List<JavaOverloads.Overload> overloads, int argc, String internalName, Utf8Entry name, Utf8Entry desc) {
		JavaMarkers markers = implementation.markers();
		JavaType superclass = java.util.Objects.requireNonNull(implementation.superclass());
		MethodCode a = new MethodCode();
		ClassEntry objectClass = cls("java/lang/Object");
		ClassEntry sub = this.cp.classEntry(internalName);
		MethodCode.Label memberFailed = a.newLabel();
		List<MethodCode.Label[]> catches = new ArrayList<>();
		if (overloads.isEmpty()) {
			// No superclass constructor takes this many arguments: the run-time error.
			throwMessage(a, "No matching constructor for " + superclass.name() + " with " + argc + " argument(s)");
			return new JvmJavaDirectSites.Method(name, desc, a);
		}
		int next = 2;
		// Each (argument, parameter type) cost once, in a local.
		Map<String, Integer> costs = new LinkedHashMap<>();
		int[][] costSlots = new int[overloads.size()][argc];
		for (int k = 0; k < overloads.size(); k++) {
			for (int i = 0; i < argc; i++) {
				JavaType parameter = JavaOverloads.parameterAt(overloads.get(k), i);
				String key = i + "|" + parameter.name();
				Integer slot = costs.get(key);
				if (slot == null) {
					slot = next++;
					a.aload(1);
					a.loadConstant(i);
					a.aaload();
					a.invokestatic(this.direct.argumentCost(parameter));
					a.istore(slot);
					costs.put(key, slot);
				}
				costSlots[k][i] = slot;
			}
		}
		// The cheapest overload: a later one replaces the best only when strictly
		// cheaper.
		int best = next++;
		int bestCost = next++;
		int total = next++;
		a.loadConstant(-1);
		a.istore(best);
		a.loadConstant(0);
		a.istore(bestCost);
		for (int k = 0; k < overloads.size(); k++) {
			MethodCode.Label skip = a.newLabel();
			a.loadConstant(overloads.get(k).packed() ? JavaOverloads.COST_VARARGS : 0);
			a.istore(total);
			for (int i = 0; i < argc; i++) {
				a.iload(costSlots[k][i]);
				a.iflt(skip);
				a.iload(total);
				a.iload(costSlots[k][i]);
				a.iadd();
				a.istore(total);
			}
			MethodCode.Label take = a.newLabel();
			a.iload(best);
			a.iflt(take);
			a.iload(total);
			a.iload(bestCost);
			a.if_icmpge(skip);
			a.labelBinding(take);
			a.loadConstant(k);
			a.istore(best);
			a.iload(total);
			a.istore(bestCost);
			a.labelBinding(skip);
		}
		MethodCode.Label found = a.newLabel();
		a.iload(best);
		a.ifge(found);
		throwMessage(a, "No matching constructor for " + superclass.name() + " with " + argc + " argument(s)");
		a.labelBinding(found);
		for (int k = 0; k < overloads.size(); k++) {
			boolean last = k == overloads.size() - 1;
			MethodCode.Label nextArm = a.newLabel();
			if (!last) {
				a.iload(best);
				a.loadConstant(k);
				a.if_icmpne(nextArm);
			}
			JavaOverloads.Overload overload = overloads.get(k);
			List<? extends JavaType> params = overload.executable().parameterTypes();
			int fixed = overload.packed() ? params.size() - 1 : params.size();
			a.new_(sub);
			a.dup();
			a.loadConstant(1);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.aload(0);
			a.aastore();
			for (int j = 0; j < fixed; j++) {
				JavaType param = params.get(j);
				a.aload(1);
				a.loadConstant(j);
				a.aaload();
				a.invokestatic(this.direct.argumentConvert(param, markers));
			}
			if (overload.packed()) {
				JavaType component = java.util.Objects.requireNonNull(params.get(fixed).componentType());
				int array = next++;
				a.loadConstant(argc - fixed);
				if (component.isPrimitive()) {
					a.newarray(primitiveKind(component));
				}
				else {
					a.anewarray(this.cp.classEntry(JvmJavaDirectSites.internalName(component)));
				}
				a.astore(array);
				for (int j = fixed; j < argc; j++) {
					a.aload(array);
					a.loadConstant(j - fixed);
					a.aload(1);
					a.loadConstant(j);
					a.aaload();
					a.invokestatic(this.direct.argumentConvert(component, markers));
					a.arrayStore(primitiveKind(component));
				}
				a.aload(array);
			}
			StringBuilder initDesc = new StringBuilder("([Ljava/lang/Object;");
			for (JavaType param : params) {
				initDesc.append(JvmJavaDirectSites.descriptor(param));
			}
			initDesc.append(")V");
			MethodCode.Label start = a.newBoundLabel();
			a.invokespecial(this.cp.methodRef(sub, "<init>", initDesc.toString()));
			catches.add(new MethodCode.Label[] { start, a.newBoundLabel() });
			a.areturn();
			if (!last) {
				a.labelBinding(nextArm);
			}
		}
		// What the constructor threw: through _jfail, which passes on what a function
		// called back from Java raised and wraps anything else.
		a.labelBinding(memberFailed);
		for (MethodCode.Label[] range : catches) {
			a.exceptionCatch(range[0], range[1], memberFailed, cls("java/lang/Throwable"));
		}
		a.ldc(this.cp.stringEntry("error constructing " + superclass.name() + ": "));
		a.invokestatic(this.direct.failureHelper());
		a.athrow();
		return new JvmJavaDirectSites.Method(name, desc, a);
	}

	private static TypeKind primitiveKind(JavaType type) {
		return switch (type.name()) {
			case "boolean" -> TypeKind.BOOLEAN;
			case "byte" -> TypeKind.BYTE;
			case "char" -> TypeKind.CHAR;
			case "short" -> TypeKind.SHORT;
			case "int" -> TypeKind.INT;
			case "long" -> TypeKind.LONG;
			case "float" -> TypeKind.FLOAT;
			case "double" -> TypeKind.DOUBLE;
			default -> TypeKind.REFERENCE;
		};
	}

	private void throwMessage(MethodCode a, String message) {
		a.new_(cls("java/lang/RuntimeException"));
		a.dup();
		a.ldc(this.cp.stringEntry(message));
		a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
	}

	// --- the generated class ---

	// abstract class <Program>$Implementation implements java.io.Serializable: the
	// functions field and the constructor every generated class shares.
	private static ClassDefinition writeBase(String internalName) {
		ConstantPool pool = new ConstantPool();
		ClassEntry selfClass = pool.classEntry(internalName);
		ClassEntry objectClass = pool.classEntry("java/lang/Object");
		ClassDefinition.Builder definition = ClassDefinition.builder(pool,
				AccessFlag.ACC_ABSTRACT | AccessFlag.ACC_SUPER | AccessFlag.ACC_SYNTHETIC, selfClass, objectClass,
				pool.utf8Entry("Code"));
		// A Proxy class's supertypes, less Proxy itself: an argument of this kind costs
		// what the interpreter's Proxy object costs (compiler/JavaImplementationType).
		definition.addInterface(pool.classEntry("java/io/Serializable"));
		Utf8Entry fnsName = pool.utf8Entry("fns");
		Utf8Entry fnsDesc = pool.utf8Entry("[Ljava/lang/Object;");
		definition.addField(AccessFlag.ACC_FINAL, fnsName, fnsDesc);
		// <init>(Object[] fns) { super(); this.fns = fns; }
		Utf8Entry initName = pool.utf8Entry("<init>");
		MethodCode init = new MethodCode();
		init.aload(0);
		init.invokespecial(pool.methodRef(objectClass, "<init>", "()V"));
		init.aload(0);
		init.aload(1);
		init.putfield(pool.fieldRef(selfClass, "fns", "[Ljava/lang/Object;"));
		init.return_();
		definition.addMethod(0, initName, pool.utf8Entry("([Ljava/lang/Object;)V"), init);
		return definition.build();
	}

	private ClassDefinition write(Shell shell) {
		JavaImplementation implementation = shell.implementation();
		String iface = implementation.interfaceNames();
		ConstantPool pool = new ConstantPool();
		ClassEntry selfClass = pool.classEntry(shell.internalName());
		ClassEntry baseClass = pool.classEntry(this.programInternalName + "$Implementation");
		ClassDefinition.Builder definition = ClassDefinition.builder(pool,
				AccessFlag.ACC_FINAL | AccessFlag.ACC_SUPER | AccessFlag.ACC_SYNTHETIC, selfClass, baseClass,
				pool.utf8Entry("Code"));
		for (JavaType each : implementation.interfaces()) {
			definition.addInterface(pool.classEntry(JvmJavaDirectSites.internalName(each)));
		}
		ClassEntry self = selfClass;
		ClassEntry base = baseClass;
		FieldRefEntry fns = pool.fieldRef(base, "fns", "[Ljava/lang/Object;");
		// private <init>(Object[] fns) { super(fns); }
		Utf8Entry initName = pool.utf8Entry("<init>");
		Utf8Entry initDesc = pool.utf8Entry("([Ljava/lang/Object;)V");
		MethodCode init = new MethodCode();
		init.aload(0);
		init.aload(1);
		init.invokespecial(pool.methodRef(base, "<init>", "([Ljava/lang/Object;)V"));
		init.return_();
		definition.addMethod(AccessFlag.ACC_PRIVATE, initName, initDesc, init);
		// static Object of(Object[] fns) { return new Self(fns); }
		MethodCode factory = new MethodCode();
		factory.new_(self);
		factory.dup();
		factory.aload(0);
		factory.invokespecial(pool.methodRef(self, "<init>", "([Ljava/lang/Object;)V"));
		factory.areturn();
		definition.addMethod(AccessFlag.ACC_STATIC, pool.utf8Entry(FACTORY), pool.utf8Entry(FACTORY_DESC), factory);
		ClassEntry program = pool.classEntry(this.programInternalName);
		List<JavaImplementation.Slot> slots = implementation.slots();
		for (int i = 0; i < slots.size(); i++) {
			writeSlot(definition, pool, fns, program, iface, slots.get(i), shell.callbacks().get(i));
		}
		if (!implementation.declaresToString()) {
			MethodCode text = new MethodCode();
			text.ldc(pool.stringEntry(implementation.defaultToString()));
			text.areturn();
			definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry("toString"),
					pool.utf8Entry("()Ljava/lang/String;"), text);
		}
		return definition.build();
	}

	// public R m(P...): the callback over (this.fns[i], the boxed arguments), or the
	// throw of an abstract method no function implements.
	private static void writeSlot(ClassDefinition.Builder definition, ConstantPool pool, FieldRefEntry fns,
			ClassEntry program, String iface, JavaImplementation.Slot slot, @Nullable String callback) {
		StringBuilder desc = new StringBuilder("(");
		for (JavaType param : slot.parameterTypes()) {
			desc.append(JvmJavaDirectSites.descriptor(param));
		}
		desc.append(')').append(JvmJavaDirectSites.descriptor(slot.returnType()));
		MethodCode a = new MethodCode();
		if (callback == null) {
			ClassEntry unsupported = pool.classEntry("java/lang/UnsupportedOperationException");
			a.new_(unsupported);
			a.dup();
			a.ldc(pool.stringEntry(JavaImplementation.noImplementation(iface, slot.key())));
			a.invokespecial(pool.methodRef(unsupported, "<init>", "(Ljava/lang/String;)V"));
			a.athrow();
			definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry(slot.name()), pool.utf8Entry(desc.toString()),
					a);
			return;
		}
		a.aload(0);
		a.getfield(fns);
		a.loadConstant(slot.implementation());
		a.aaload();
		List<JavaType> params = slot.parameterTypes();
		a.loadConstant(params.size());
		a.anewarray(pool.classEntry("java/lang/Object"));
		int local = 1;
		for (int i = 0; i < params.size(); i++) {
			JavaType param = params.get(i);
			a.dup();
			a.loadConstant(i);
			load(a, param, local);
			box(a, pool, param);
			a.aastore();
			local += width(param);
		}
		a.invokestatic(pool.methodRef(program, callback, callbackDescriptor(slot)));
		a.return_(returnKind(slot.returnType()));
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry(slot.name()), pool.utf8Entry(desc.toString()), a);
	}

	private static int width(JavaType type) {
		return "long".equals(type.name()) || "double".equals(type.name()) ? 2 : 1;
	}

	private static void load(MethodCode a, JavaType type, int slot) {
		switch (type.name()) {
			case "long" -> a.lload(slot);
			case "double" -> a.dload(slot);
			case "float" -> a.fload(slot);
			case "boolean", "byte", "char", "short", "int" -> a.iload(slot);
			default -> a.aload(slot);
		}
	}

	// A primitive argument boxed as a Proxy boxes it for its handler.
	private static void box(MethodCode a, ConstantPool pool, JavaType type) {
		String box = switch (type.name()) {
			case "boolean" -> "java/lang/Boolean:(Z)Ljava/lang/Boolean;";
			case "byte" -> "java/lang/Byte:(B)Ljava/lang/Byte;";
			case "char" -> "java/lang/Character:(C)Ljava/lang/Character;";
			case "short" -> "java/lang/Short:(S)Ljava/lang/Short;";
			case "int" -> "java/lang/Integer:(I)Ljava/lang/Integer;";
			case "long" -> "java/lang/Long:(J)Ljava/lang/Long;";
			case "float" -> "java/lang/Float:(F)Ljava/lang/Float;";
			case "double" -> "java/lang/Double:(D)Ljava/lang/Double;";
			default -> null;
		};
		if (box == null) {
			return;
		}
		int colon = box.indexOf(':');
		a.invokestatic(pool.methodRef(pool.classEntry(box.substring(0, colon)), "valueOf", box.substring(colon + 1)));
	}

	// --- the generated subclass ---

	// final class Prog$SubclassN extends Super implements I...: the fns field, one
	// constructor per superclass constructor overload (each taking the functions
	// first), one override per slot, and one super$m$a accessor per named (name,
	// arity) with a superclass implementation to call.
	private ClassDefinition writeSubclass(Subshell shell) {
		JavaImplementation implementation = shell.implementation();
		JavaType superclass = java.util.Objects.requireNonNull(implementation.superclass());
		ConstantPool pool = new ConstantPool();
		ClassEntry selfClass = pool.classEntry(shell.internalName());
		ClassEntry superClass = pool.classEntry(JvmJavaDirectSites.internalName(superclass));
		ClassDefinition.Builder definition = ClassDefinition.builder(pool,
				AccessFlag.ACC_FINAL | AccessFlag.ACC_SUPER | AccessFlag.ACC_SYNTHETIC, selfClass, superClass,
				pool.utf8Entry("Code"));
		for (JavaType each : implementation.interfaces()) {
			definition.addInterface(pool.classEntry(JvmJavaDirectSites.internalName(each)));
		}
		ClassEntry self = selfClass;
		FieldRefEntry fns = pool.fieldRef(self, "fns", "[Ljava/lang/Object;");
		definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_FINAL, pool.utf8Entry("fns"),
				pool.utf8Entry("[Ljava/lang/Object;"));
		ClassEntry program = pool.classEntry(this.programInternalName);
		for (JavaOverloads.Overload overload : shell.overloads()) {
			writeSubclassConstructor(definition, pool, self, superClass, fns, overload);
		}
		List<JavaImplementation.Slot> slots = implementation.slots();
		for (int i = 0; i < slots.size(); i++) {
			writeSubclassSlot(definition, pool, fns, program, implementation, slots.get(i), shell.callbacks().get(i));
		}
		writeSubclassSuperAccessors(definition, pool, implementation);
		return definition.build();
	}

	// public <init>(Object[] fns, P...): { this.fns = fns; super(P...); }
	// (the field first: a superclass constructor may call back into an override).
	private static void writeSubclassConstructor(ClassDefinition.Builder definition, ConstantPool pool, ClassEntry self,
			ClassEntry superClass, FieldRefEntry fns, JavaOverloads.Overload overload) {
		List<? extends JavaType> params = overload.executable().parameterTypes();
		StringBuilder desc = new StringBuilder("([Ljava/lang/Object;");
		for (JavaType param : params) {
			desc.append(JvmJavaDirectSites.descriptor(param));
		}
		desc.append(")V");
		MethodCode init = new MethodCode();
		init.aload(0);
		init.aload(1);
		init.putfield(fns);
		init.aload(0);
		int local = 2;
		for (JavaType param : params) {
			load(init, param, local);
			local += width(param);
		}
		StringBuilder target = new StringBuilder("(");
		for (JavaType param : params) {
			target.append(JvmJavaDirectSites.descriptor(param));
		}
		target.append(")V");
		init.invokespecial(pool.methodRef(superClass, "<init>", target.toString()));
		init.return_();
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry("<init>"), pool.utf8Entry(desc.toString()), init);
	}

	// public R m(P...): the subclass callback over (this.fns[0], this, the boxed
	// arguments), or the oracle's UnsupportedOperationException with the method's
	// name for an abstract method no body implements.
	private static void writeSubclassSlot(ClassDefinition.Builder definition, ConstantPool pool, FieldRefEntry fns,
			ClassEntry program, JavaImplementation implementation, JavaImplementation.Slot slot,
			@Nullable String callback) {
		StringBuilder desc = new StringBuilder("(");
		for (JavaType param : slot.parameterTypes()) {
			desc.append(JvmJavaDirectSites.descriptor(param));
		}
		desc.append(')').append(JvmJavaDirectSites.descriptor(slot.returnType()));
		MethodCode a = new MethodCode();
		if (callback == null) {
			ClassEntry unsupported = pool.classEntry("java/lang/UnsupportedOperationException");
			a.new_(unsupported);
			a.dup();
			a.ldc(pool.stringEntry(slot.name()));
			a.invokespecial(pool.methodRef(unsupported, "<init>", "(Ljava/lang/String;)V"));
			a.athrow();
			definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry(slot.name()), pool.utf8Entry(desc.toString()),
					a);
			return;
		}
		a.aload(0);
		a.getfield(fns);
		a.loadConstant(0);
		a.aaload();
		a.aload(0);
		List<JavaType> params = slot.parameterTypes();
		a.loadConstant(params.size());
		a.anewarray(pool.classEntry("java/lang/Object"));
		int local = 1;
		for (int i = 0; i < params.size(); i++) {
			JavaType param = params.get(i);
			a.dup();
			a.loadConstant(i);
			load(a, param, local);
			box(a, pool, param);
			a.aastore();
			local += width(param);
		}
		a.invokestatic(pool.methodRef(program, callback, subclassCallbackDescriptor(slot)));
		a.return_(returnKind(slot.returnType()));
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry(slot.name()), pool.utf8Entry(desc.toString()), a);
	}

	// One public super$m$a accessor per named (name, arity) with a superclass
	// implementation to call: what a proxy-super reaches.
	private static void writeSubclassSuperAccessors(ClassDefinition.Builder definition, ConstantPool pool,
			JavaImplementation implementation) {
		JavaType superclass = java.util.Objects.requireNonNull(implementation.superclass());
		List<String> done = new ArrayList<>();
		for (JavaImplementation.Slot slot : implementation.slots()) {
			if (slot.implementation() == JavaImplementation.NONE) {
				continue;
			}
			String accessor = JavaImplementations.superAccessor(slot.name(), slot.parameterTypes().size());
			if (done.contains(accessor)) {
				continue;
			}
			JavaImplementations.SuperTarget target = JavaImplementations.superTarget(superclass,
					implementation.interfaces(), slot.name(), slot.parameterTypes());
			if (target == null) {
				continue;
			}
			done.add(accessor);
			StringBuilder desc = new StringBuilder("(");
			for (JavaType param : target.executable().parameterTypes()) {
				desc.append(JvmJavaDirectSites.descriptor(param));
			}
			desc.append(')').append(JvmJavaDirectSites.descriptor(target.executable().returnType()));
			MethodCode a = new MethodCode();
			a.aload(0);
			int local = 1;
			for (JavaType param : target.executable().parameterTypes()) {
				load(a, param, local);
				local += width(param);
			}
			String owner = JvmJavaDirectSites.internalName(target.owner());
			if (target.owner().isInterface()) {
				a.invokespecial(pool.interfaceMethodRef(owner, target.executable().name(), desc.toString()));
			}
			else {
				a.invokespecial(pool.methodRef(owner, target.executable().name(), desc.toString()));
			}
			a.return_(returnKind(target.executable().returnType()));
			definition.addMethod(AccessFlag.ACC_PUBLIC, pool.utf8Entry(accessor), pool.utf8Entry(desc.toString()), a);
		}
	}

}
