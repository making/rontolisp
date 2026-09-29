package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ClassDefinition;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.JvmClassSplitter;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaImplementation;
import am.ik.rontolisp.compiler.JavaImplementations;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;

/**
 * The classes a compiled program implements host interfaces with, generated at compile
 * time and shipped beside it -- no {@code java.lang.reflect.Proxy}, nothing defined at
 * run time, so a GraalVM native image needs no metadata for them
 * ({@code .kb/java-interop.md}, "Implementing interfaces"). One class per
 * {@code java:reify} shape ({@code <Program>$Reify<N>}) and per interface a
 * {@code java:proxy} or a function value passed where the interface is expected becomes
 * ({@code <Program>$Proxy<N>}), declaring exactly the slots {@link JavaImplementations}
 * chose -- the ones the interpreter's {@code Proxy} handler dispatches on:
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

	/** Whether a direct call tests an argument against {@link #baseClass()}. */
	private boolean baseTested;

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
		return factory(JavaImplementations.proxy(iface, this.lookup));
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
		if (this.baseTested || !this.shells.isEmpty()) {
			String base = this.programInternalName + "$Implementation";
			files.put(base + ".class", written(writeBase(base), target));
		}
		for (Shell shell : this.shells.values()) {
			files.put(shell.internalName() + ".class", written(write(shell), target));
		}
		return files;
	}

	// One class, never split: every member stays where the interface finds it.
	private static byte[] written(ClassDefinition definition, JvmClassSplitter.Target target) {
		return JvmClassSplitter.write(definition, null, method -> true, ConstantPool.MAX_INDEX, target).mainClass();
	}

	private Shell shell(JavaImplementation implementation) {
		JavaType iface = Objects.requireNonNull(implementation.iface(), "a resolved implementation");
		StringBuilder key = new StringBuilder(implementation.proxy() ? "proxy|" : "reify|").append(iface.name());
		for (JavaImplementation.Slot slot : implementation.slots()) {
			key.append('|').append(slot.dispatchKey()).append('=').append(slot.implementation());
		}
		Shell cached = this.shells.get(key.toString());
		if (cached != null) {
			return cached;
		}
		String name = this.programInternalName
				+ (implementation.proxy() ? "$Proxy" + this.proxies++ : "$Reify" + this.reifies++);
		List<@Nullable String> callbacks = new ArrayList<>();
		for (JavaImplementation.Slot slot : implementation.slots()) {
			callbacks.add(slot.implementation() == JavaImplementation.NONE ? null
					: callback(implementation.proxy(), iface, slot));
		}
		Shell shell = new Shell(name, implementation, callbacks);
		this.shells.put(key.toString(), shell);
		return shell;
	}

	// The program-side method a slot calls, made the first time one of its shape asks:
	// one per (proxy or not, interface, method, return type).
	private String callback(boolean proxy, JavaType iface, JavaImplementation.Slot slot) {
		String key = (proxy ? "proxy|" : "reify|") + iface.name() + "|" + slot.dispatchKey();
		String cached = this.callbacks.get(key);
		if (cached != null) {
			return cached;
		}
		String name = CALLBACK_PREFIX + this.callbacks.size();
		this.callbacks.put(key, name);
		this.methods
			.add(buildCallback(proxy, iface, slot, this.cp.addUtf8(name), this.cp.addUtf8(callbackDescriptor(slot))));
		return name;
	}

	private static String callbackDescriptor(JavaImplementation.Slot slot) {
		return "(Ljava/lang/Object;[Ljava/lang/Object;)" + JvmJavaDirectSites.descriptor(slot.returnType());
	}

	private ClassEntry cls(String internalName) {
		return this.cp.classEntry(internalName);
	}

	private MethodRefEntry method(String owner, String name, String desc) {
		return this.cp.methodRef(cls(owner), name, desc);
	}

	// static R _jimpl$K(Object fn, Object[] args): (fn [name] (_junm args[0]) ...), then
	// the value converted to R -- or the interpreter's error for one that does not. What
	// leaves it thrown -- the function's exit or condition, that error -- is recorded on
	// its way out to the Java caller (_jsig), for the site whose Java call it reaches.
	private JvmJavaDirectSites.Method buildCallback(boolean proxy, JavaType iface, JavaImplementation.Slot slot,
			Utf8Constant name, Utf8Constant desc) {
		MethodCode a = new MethodCode();
		MethodCode.Label start = a.newBoundLabel();
		ClassEntry objectClass = cls("java/lang/Object");
		MethodRefEntry unmarshal = this.direct.unmarshalHelper();
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
			a.ldc(this.cp
				.stringEntry(JavaImplementation.returnMismatchSuffix(proxy, iface.name(), slot.name(), returnType)));
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

	// --- the generated class ---

	// abstract class <Program>$Implementation implements java.io.Serializable: the
	// functions field and the constructor every generated class shares.
	private static ClassDefinition writeBase(String internalName) {
		ConstantPool pool = new ConstantPool();
		ClassConstant selfClass = pool.addClass(pool.addUtf8(internalName));
		ClassConstant objectClass = pool.addClass(pool.addUtf8("java/lang/Object"));
		ClassDefinition.Builder definition = ClassDefinition.builder(pool,
				AccessFlag.ACC_ABSTRACT | AccessFlag.ACC_SUPER | AccessFlag.ACC_SYNTHETIC, selfClass, objectClass,
				pool.addUtf8("Code"));
		// A Proxy class's supertypes, less Proxy itself: an argument of this kind costs
		// what the interpreter's Proxy object costs (compiler/JavaImplementationType).
		definition.addInterface(pool.addClass(pool.addUtf8("java/io/Serializable")));
		Utf8Constant fnsName = pool.addUtf8("fns");
		Utf8Constant fnsDesc = pool.addUtf8("[Ljava/lang/Object;");
		definition.addField(AccessFlag.ACC_FINAL, fnsName, fnsDesc);
		// <init>(Object[] fns) { super(); this.fns = fns; }
		Utf8Constant initName = pool.addUtf8("<init>");
		MethodCode init = new MethodCode();
		init.aload(0);
		init.invokespecial(pool.methodRef(objectClass.entry(), "<init>", "()V"));
		init.aload(0);
		init.aload(1);
		init.putfield(pool.fieldRef(selfClass.entry(), "fns", "[Ljava/lang/Object;"));
		init.return_();
		definition.addMethod(0, initName, pool.addUtf8("([Ljava/lang/Object;)V"), init);
		return definition.build();
	}

	private ClassDefinition write(Shell shell) {
		JavaImplementation implementation = shell.implementation();
		JavaType iface = Objects.requireNonNull(implementation.iface());
		ConstantPool pool = new ConstantPool();
		ClassConstant selfClass = pool.addClass(pool.addUtf8(shell.internalName()));
		ClassConstant baseClass = pool.addClass(pool.addUtf8(this.programInternalName + "$Implementation"));
		ClassDefinition.Builder definition = ClassDefinition.builder(pool,
				AccessFlag.ACC_FINAL | AccessFlag.ACC_SUPER | AccessFlag.ACC_SYNTHETIC, selfClass, baseClass,
				pool.addUtf8("Code"));
		definition.addInterface(pool.addClass(pool.addUtf8(JvmJavaDirectSites.internalName(iface))));
		ClassEntry self = selfClass.entry();
		ClassEntry base = baseClass.entry();
		FieldRefEntry fns = pool.fieldRef(base, "fns", "[Ljava/lang/Object;");
		// private <init>(Object[] fns) { super(fns); }
		Utf8Constant initName = pool.addUtf8("<init>");
		Utf8Constant initDesc = pool.addUtf8("([Ljava/lang/Object;)V");
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
		definition.addMethod(AccessFlag.ACC_STATIC, pool.addUtf8(FACTORY), pool.addUtf8(FACTORY_DESC), factory);
		ClassEntry program = pool.classEntry(this.programInternalName);
		List<JavaImplementation.Slot> slots = implementation.slots();
		for (int i = 0; i < slots.size(); i++) {
			writeSlot(definition, pool, fns, program, iface, slots.get(i), shell.callbacks().get(i));
		}
		if (!implementation.declaresToString()) {
			MethodCode text = new MethodCode();
			text.ldc(pool.stringEntry(implementation.defaultToString()));
			text.areturn();
			definition.addMethod(AccessFlag.ACC_PUBLIC, pool.addUtf8("toString"), pool.addUtf8("()Ljava/lang/String;"),
					text);
		}
		return definition.build();
	}

	// public R m(P...): the callback over (this.fns[i], the boxed arguments), or the
	// throw of an abstract method no function implements.
	private static void writeSlot(ClassDefinition.Builder definition, ConstantPool pool, FieldRefEntry fns,
			ClassEntry program, JavaType iface, JavaImplementation.Slot slot, @Nullable String callback) {
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
			a.ldc(pool.stringEntry(JavaImplementation.noImplementation(iface.name(), slot.key())));
			a.invokespecial(pool.methodRef(unsupported, "<init>", "(Ljava/lang/String;)V"));
			a.athrow();
			definition.addMethod(AccessFlag.ACC_PUBLIC, pool.addUtf8(slot.name()), pool.addUtf8(desc.toString()), a);
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
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.addUtf8(slot.name()), pool.addUtf8(desc.toString()), a);
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

}
