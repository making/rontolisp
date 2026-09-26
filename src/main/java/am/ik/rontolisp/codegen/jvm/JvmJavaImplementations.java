package am.ik.rontolisp.codegen.jvm;

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
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.jvm.StackMapAugmenter;
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
 * ({@link #callbackNames()}).
 */
final class JvmJavaImplementations {

	/** The prefix of a program-side method a generated class calls. */
	static final String CALLBACK_PREFIX = "_jimpl$";

	/** The descriptor of a generated class's factory. */
	private static final String FACTORY_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

	private static final String FACTORY = "of";

	private final ConstantPool cp;

	private final ClassConstant thisClass;

	private final String programInternalName;

	private final JavaClassLookup lookup;

	private final JvmJavaDirectSites direct;

	private final MethodrefConstant lispToString;

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
	JvmJavaImplementations(ConstantPool cp, ClassConstant thisClass, String programInternalName, JavaClassLookup lookup,
			JvmJavaDirectSites direct, MethodrefConstant lispToString) {
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
	MethodrefConstant factory(JavaImplementation implementation) {
		Shell shell = shell(implementation);
		return this.cp.addMethodref(this.cp.addClass(this.cp.addUtf8(shell.internalName())),
				this.cp.addNameAndType(this.cp.addUtf8(FACTORY), this.cp.addUtf8(FACTORY_DESC)));
	}

	/**
	 * The factory of the proxy class of an interface: what a function value passed where
	 * the interface is expected becomes, as a {@code java:proxy} of it.
	 * @param iface a linkable interface
	 * @return the factory, taking a one-element array holding the function
	 */
	MethodrefConstant proxyFactory(JavaType iface) {
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
	 * @param majorVersion the program's class-file major version
	 * @return the class files
	 */
	Map<String, byte[]> classFiles(int majorVersion) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		if (this.baseTested || !this.shells.isEmpty()) {
			String base = this.programInternalName + "$Implementation";
			files.put(base + ".class", StackMapAugmenter.augment(writeBase(base), majorVersion));
		}
		for (Shell shell : this.shells.values()) {
			files.put(shell.internalName() + ".class", StackMapAugmenter.augment(write(shell), majorVersion));
		}
		return files;
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

	private ClassConstant cls(String internalName) {
		return this.cp.addClass(this.cp.addUtf8(internalName));
	}

	private MethodrefConstant method(String owner, String name, String desc) {
		return this.cp.addMethodref(cls(owner), this.cp.addNameAndType(this.cp.addUtf8(name), this.cp.addUtf8(desc)));
	}

	// static R _jimpl$K(Object fn, Object[] args): (fn [name] (_junm args[0]) ...), then
	// the value converted to R -- or the interpreter's error for one that does not.
	private JvmJavaDirectSites.Method buildCallback(boolean proxy, JavaType iface, JavaImplementation.Slot slot,
			Utf8Constant name, Utf8Constant desc) {
		JvmAsm a = new JvmAsm();
		ClassConstant objectClass = cls("java/lang/Object");
		MethodrefConstant unmarshal = this.direct.unmarshalHelper();
		// 2 = the argument list, 3 = i, 4 = the value
		int loop = a.label();
		int done = a.label();
		a.aconstNull();
		a.astore(2);
		a.aload(1);
		a.arraylength();
		a.istore(3);
		a.bind(loop);
		a.iinc(3, -1);
		a.iload(3);
		a.branch(Opcode.IFLT, done);
		a.iconst(2);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.aload(1);
		a.iload(3);
		a.aaload();
		a.invokestatic(unmarshal);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(2);
		a.aastore();
		a.astore(2);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		if (proxy) {
			// A proxy's function takes the method's name first.
			a.iconst(2);
			a.anewarray(objectClass);
			a.dup();
			a.iconst(0);
			a.ldcString(this.cp.addString("\"" + slot.name() + "\""));
			a.aastore();
			a.dup();
			a.iconst(1);
			a.aload(2);
			a.aastore();
			a.astore(2);
		}
		a.aload(0);
		a.aload(2);
		a.invokestatic(this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(this.cp.addUtf8("_apply"),
				this.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		JavaType returnType = slot.returnType();
		if ("void".equals(returnType.name())) {
			a.pop();
			a.op(Opcode.RETURN);
			return new JvmJavaDirectSites.Method(name, desc, 6, 5, a.finish(), List.of());
		}
		a.astore(4);
		int fits = a.label();
		a.aload(4);
		a.invokestatic(this.direct.returnedCost(returnType));
		a.branch(Opcode.IFGE, fits);
		MethodrefConstant concat = method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		a.anew(cls("java/lang/RuntimeException"));
		a.dup();
		a.ldcString(this.cp.addString(JavaImplementation.returnMismatchPrefix(proxy)));
		a.aload(4);
		a.invokestatic(this.lispToString);
		a.invokevirtual(concat);
		a.ldcString(this.cp
			.addString(JavaImplementation.returnMismatchSuffix(proxy, iface.name(), slot.name(), returnType)));
		a.invokevirtual(concat);
		a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
		a.bind(fits);
		a.aload(4);
		a.invokestatic(this.direct.returnedConvert(returnType));
		a.op(returnOpcode(returnType));
		return new JvmJavaDirectSites.Method(name, desc, 6, 5, a.finish(), List.of());
	}

	private static int returnOpcode(JavaType type) {
		return switch (type.name()) {
			case "boolean", "byte", "char", "short", "int" -> Opcode.IRETURN;
			case "long" -> Opcode.LRETURN;
			case "float" -> Opcode.FRETURN;
			case "double" -> Opcode.DRETURN;
			case "void" -> Opcode.RETURN;
			default -> Opcode.ARETURN;
		};
	}

	// --- the generated class ---

	// abstract class <Program>$Implementation implements java.io.Serializable: the
	// functions field and the constructor every generated class shares.
	private static byte[] writeBase(String internalName) {
		ConstantPool pool = new ConstantPool();
		ClassConstant self = pool.addClass(pool.addUtf8(internalName));
		ClassConstant object = pool.addClass(pool.addUtf8("java/lang/Object"));
		ClassDefinition.Builder definition = ClassDefinition.builder(pool,
				AccessFlag.ACC_ABSTRACT | AccessFlag.ACC_SUPER | AccessFlag.ACC_SYNTHETIC, self, object,
				pool.addUtf8("Code"));
		// A Proxy class's supertypes, less Proxy itself: an argument of this kind costs
		// what the interpreter's Proxy object costs (compiler/JavaImplementationType).
		definition.addInterface(pool.addClass(pool.addUtf8("java/io/Serializable")));
		Utf8Constant fnsName = pool.addUtf8("fns");
		Utf8Constant fnsDesc = pool.addUtf8("[Ljava/lang/Object;");
		definition.addField(AccessFlag.ACC_FINAL, fnsName, fnsDesc);
		// <init>(Object[] fns) { super(); this.fns = fns; }
		Utf8Constant initName = pool.addUtf8("<init>");
		JvmAsm init = new JvmAsm();
		init.aload(0);
		init.invokespecial(pool.addMethodref(object, pool.addNameAndType(initName, pool.addUtf8("()V"))));
		init.aload(0);
		init.aload(1);
		init.op(Opcode.PUTFIELD);
		init.u2(pool.addFieldref(self, pool.addNameAndType(fnsName, fnsDesc)).index());
		init.op(Opcode.RETURN);
		definition.addMethod(0, initName, pool.addUtf8("([Ljava/lang/Object;)V"), 2, 2, init.finish(), List.of());
		return definition.build().toBytes();
	}

	private byte[] write(Shell shell) {
		JavaImplementation implementation = shell.implementation();
		JavaType iface = Objects.requireNonNull(implementation.iface());
		ConstantPool pool = new ConstantPool();
		ClassConstant self = pool.addClass(pool.addUtf8(shell.internalName()));
		ClassConstant base = pool.addClass(pool.addUtf8(this.programInternalName + "$Implementation"));
		ClassDefinition.Builder definition = ClassDefinition.builder(pool,
				AccessFlag.ACC_FINAL | AccessFlag.ACC_SUPER | AccessFlag.ACC_SYNTHETIC, self, base,
				pool.addUtf8("Code"));
		definition.addInterface(pool.addClass(pool.addUtf8(JvmJavaDirectSites.internalName(iface))));
		Utf8Constant fnsName = pool.addUtf8("fns");
		Utf8Constant fnsDesc = pool.addUtf8("[Ljava/lang/Object;");
		FieldrefConstant fns = pool.addFieldref(base, pool.addNameAndType(fnsName, fnsDesc));
		// private <init>(Object[] fns) { super(fns); }
		Utf8Constant initName = pool.addUtf8("<init>");
		Utf8Constant initDesc = pool.addUtf8("([Ljava/lang/Object;)V");
		JvmAsm init = new JvmAsm();
		init.aload(0);
		init.aload(1);
		init.invokespecial(pool.addMethodref(base, pool.addNameAndType(initName, initDesc)));
		init.op(Opcode.RETURN);
		definition.addMethod(AccessFlag.ACC_PRIVATE, initName, initDesc, 2, 2, init.finish(), List.of());
		// static Object of(Object[] fns) { return new Self(fns); }
		JvmAsm factory = new JvmAsm();
		factory.anew(self);
		factory.dup();
		factory.aload(0);
		factory.invokespecial(pool.addMethodref(self, pool.addNameAndType(initName, initDesc)));
		factory.areturn();
		definition.addMethod(AccessFlag.ACC_STATIC, pool.addUtf8(FACTORY), pool.addUtf8(FACTORY_DESC), 3, 1,
				factory.finish(), List.of());
		ClassConstant program = pool.addClass(pool.addUtf8(this.programInternalName));
		List<JavaImplementation.Slot> slots = implementation.slots();
		for (int i = 0; i < slots.size(); i++) {
			writeSlot(definition, pool, fns, program, iface, slots.get(i), shell.callbacks().get(i));
		}
		if (!implementation.declaresToString()) {
			JvmAsm text = new JvmAsm();
			text.ldcString(pool.addString(implementation.defaultToString()));
			text.areturn();
			definition.addMethod(AccessFlag.ACC_PUBLIC, pool.addUtf8("toString"), pool.addUtf8("()Ljava/lang/String;"),
					1, 1, text.finish(), List.of());
		}
		return definition.build().toBytes();
	}

	// public R m(P...): the callback over (this.fns[i], the boxed arguments), or the
	// throw of an abstract method no function implements.
	private static void writeSlot(ClassDefinition.Builder definition, ConstantPool pool, FieldrefConstant fns,
			ClassConstant program, JavaType iface, JavaImplementation.Slot slot, @Nullable String callback) {
		StringBuilder desc = new StringBuilder("(");
		int locals = 1;
		for (JavaType param : slot.parameterTypes()) {
			desc.append(JvmJavaDirectSites.descriptor(param));
			locals += width(param);
		}
		desc.append(')').append(JvmJavaDirectSites.descriptor(slot.returnType()));
		JvmAsm a = new JvmAsm();
		if (callback == null) {
			ClassConstant unsupported = pool.addClass(pool.addUtf8("java/lang/UnsupportedOperationException"));
			a.anew(unsupported);
			a.dup();
			a.ldcString(pool.addString(JavaImplementation.noImplementation(iface.name(), slot.key())));
			a.invokespecial(pool.addMethodref(unsupported,
					pool.addNameAndType(pool.addUtf8("<init>"), pool.addUtf8("(Ljava/lang/String;)V"))));
			a.athrow();
			definition.addMethod(AccessFlag.ACC_PUBLIC, pool.addUtf8(slot.name()), pool.addUtf8(desc.toString()), 3,
					locals, a.finish(), List.of());
			return;
		}
		a.aload(0);
		a.getfield(fns);
		a.iconst(slot.implementation());
		a.aaload();
		List<JavaType> params = slot.parameterTypes();
		a.iconst(params.size());
		a.anewarray(pool.addClass(pool.addUtf8("java/lang/Object")));
		int local = 1;
		for (int i = 0; i < params.size(); i++) {
			JavaType param = params.get(i);
			a.dup();
			a.iconst(i);
			load(a, param, local);
			box(a, pool, param);
			a.aastore();
			local += width(param);
		}
		a.invokestatic(pool.addMethodref(program,
				pool.addNameAndType(pool.addUtf8(callback), pool.addUtf8(callbackDescriptor(slot)))));
		a.op(returnOpcode(slot.returnType()));
		definition.addMethod(AccessFlag.ACC_PUBLIC, pool.addUtf8(slot.name()), pool.addUtf8(desc.toString()), 7, locals,
				a.finish(), List.of());
	}

	private static int width(JavaType type) {
		return "long".equals(type.name()) || "double".equals(type.name()) ? 2 : 1;
	}

	private static void load(JvmAsm a, JavaType type, int slot) {
		switch (type.name()) {
			case "long" -> a.lload(slot);
			case "double" -> a.dload(slot);
			case "float" -> a.fload(slot);
			case "boolean", "byte", "char", "short", "int" -> a.iload(slot);
			default -> a.aload(slot);
		}
	}

	// A primitive argument boxed as a Proxy boxes it for its handler.
	private static void box(JvmAsm a, ConstantPool pool, JavaType type) {
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
		a.invokestatic(pool.addMethodref(pool.addClass(pool.addUtf8(box.substring(0, colon))),
				pool.addNameAndType(pool.addUtf8("valueOf"), pool.addUtf8(box.substring(colon + 1)))));
	}

}
