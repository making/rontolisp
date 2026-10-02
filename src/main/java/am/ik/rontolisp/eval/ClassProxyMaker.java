package am.ik.rontolisp.eval;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import am.ik.rontolisp.compiler.JavaImplementation;
import am.ik.rontolisp.compiler.JavaImplementations;
import am.ik.rontolisp.compiler.JavaType;

/**
 * Defines, at run time, the subclass a {@code (java:subclass ...)} makes: a
 * {@code java.lang.reflect.Proxy} cannot extend a class, so a class proxy is a generated
 * subclass, on the interpreter ({@code java.lang.classfile} plus a child loader that sees
 * the superclass) as on the JVM backend (which generates it at compile time instead).
 * <p>
 * The class overrides every one of the implementation's slots: a named method calls its
 * {@link ClassProxyHandler} (which applies the form's callable with the object and the
 * method's name before its arguments), an abstract method no body implements throws
 * {@link UnsupportedOperationException} with the method's name, and an unnamed concrete
 * method of the class chain is inherited (it calls super). One public {@code super$}
 * accessor per named (name, arity) calls the superclass implementation a
 * {@code proxy-super} reaches.
 */
final class ClassProxyMaker {

	private static final ClassDesc OBJECT = ClassDesc.of("java.lang.Object");

	private static final ClassDesc HANDLER = ClassDesc.of("am.ik.rontolisp.eval.ClassProxyHandler");

	private static final ClassDesc UNSUPPORTED = ClassDesc.of("java.lang.UnsupportedOperationException");

	private static final MethodTypeDesc HANDLER_TYPE = MethodTypeDesc
		.ofDescriptor("(ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

	private static final MethodTypeDesc STRING_TO_VOID = MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V");

	private static final AtomicLong COUNTER = new AtomicLong();

	private static final ConcurrentHashMap<Key, Class<?>> CLASSES = new ConcurrentHashMap<>();

	private ClassProxyMaker() {
	}

	// What the generated class is a function of: its superclass, interfaces, slots
	// and constructor.
	private record Key(String superclass, List<String> interfaces, List<String> slots, String constructor) {
	}

	/**
	 * The class for this shape, defined on first use.
	 * @param superclass the superclass
	 * @param interfaces the extra interfaces
	 * @param implementation the implementation (its slots)
	 * @param constructor the superclass constructor the new instance is made with
	 * @return the generated subclass
	 */
	static Class<?> proxyClass(Class<?> superclass, List<Class<?>> interfaces, JavaImplementation implementation,
			Constructor<?> constructor) {
		List<String> names = new ArrayList<>(interfaces.size());
		for (Class<?> iface : interfaces) {
			names.add(iface.getName());
		}
		List<String> slots = new ArrayList<>(implementation.slots().size());
		for (JavaImplementation.Slot slot : implementation.slots()) {
			slots.add(slot.dispatchKey() + "=" + slot.implementation());
		}
		Key key = new Key(superclass.getName(), List.copyOf(names), List.copyOf(slots), constructor.toString());
		Class<?> cached = CLASSES.get(key);
		if (cached != null) {
			return cached;
		}
		String binaryName = "rontolisp.proxy.Sub$" + COUNTER.getAndIncrement();
		byte[] bytes = build(ClassDesc.of(binaryName), superclass, interfaces, implementation, constructor);
		Class<?> defined = new ChildLoader(ClassProxyMaker.class.getClassLoader()).define(binaryName, bytes);
		Class<?> existing = CLASSES.putIfAbsent(key, defined);
		return existing != null ? existing : defined;
	}

	// A loader that sees what the application sees (in particular the superclass, the
	// interfaces and the dispatch interface): one class per loader, so each unloads
	// with it.
	private static final class ChildLoader extends ClassLoader {

		ChildLoader(ClassLoader parent) {
			super(parent);
		}

		Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}

	}

	private static byte[] build(ClassDesc self, Class<?> superclass, List<Class<?>> interfaces,
			JavaImplementation implementation, Constructor<?> constructor) {
		ClassDesc sup = ClassDesc.of(superclass.getName());
		List<ClassDesc> ifaces = new ArrayList<>(interfaces.size());
		for (Class<?> iface : interfaces) {
			ifaces.add(ClassDesc.of(iface.getName()));
		}
		Class<?>[] params = constructor.getParameterTypes();
		StringBuilder paramsDesc = new StringBuilder();
		for (Class<?> param : params) {
			paramsDesc.append(descriptor(param.getName()));
		}
		return ClassFile.of().build(self, clb -> {
			clb.withVersion(ClassFile.latestMajorVersion(), 0);
			clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER | ClassFile.ACC_FINAL);
			clb.withSuperclass(sup);
			clb.withInterfaceSymbols(ifaces);
			clb.withField("handler", HANDLER, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
			writeConstructor(clb, self, sup, paramsDesc.toString());
			List<JavaImplementation.Slot> slots = implementation.slots();
			for (int i = 0; i < slots.size(); i++) {
				writeSlot(clb, self, slots.get(i), i);
			}
			writeSuperAccessors(clb, implementation);
		});
	}

	// public <init>(Handler handler, P... params) { this.handler = handler;
	// super(params); }
	private static void writeConstructor(ClassBuilder clb, ClassDesc self, ClassDesc sup, String paramsDesc) {
		clb.withMethod("<init>",
				MethodTypeDesc.ofDescriptor("(Lam/ik/rontolisp/eval/ClassProxyHandler;" + paramsDesc + ")V"),
				ClassFile.ACC_PUBLIC, mb -> mb.withCode(cb -> {
					cb.aload(0);
					cb.aload(1);
					cb.putfield(self, "handler", HANDLER);
					cb.aload(0);
					int slot = 2;
					for (String param : split(paramsDesc)) {
						load(cb, param, slot);
						slot += width(param);
					}
					cb.invokespecial(sup, "<init>", MethodTypeDesc.ofDescriptor("(" + paramsDesc + ")V"));
					cb.return_();
				}));
	}

	// One override per slot: a named method calls the handler, an abstract one no body
	// implements throws UnsupportedOperationException with the method's name.
	private static void writeSlot(ClassBuilder clb, ClassDesc self, JavaImplementation.Slot slot, int index) {
		List<JavaType> params = slot.parameterTypes();
		StringBuilder desc = new StringBuilder("(");
		for (JavaType param : params) {
			desc.append(descriptor(param.name()));
		}
		String descriptor = desc.append(')').append(descriptor(slot.returnType().name())).toString();
		MethodTypeDesc type = MethodTypeDesc.ofDescriptor(descriptor);
		if (slot.implementation() == JavaImplementation.NONE) {
			clb.withMethod(slot.name(), type, ClassFile.ACC_PUBLIC, mb -> mb.withCode(cb -> {
				cb.new_(UNSUPPORTED);
				cb.dup();
				cb.ldc(slot.name());
				cb.invokespecial(UNSUPPORTED, "<init>", STRING_TO_VOID);
				cb.athrow();
			}));
			return;
		}
		clb.withMethod(slot.name(), type, ClassFile.ACC_PUBLIC, mb -> mb.withCode(cb -> {
			cb.aload(0);
			cb.getfield(self, "handler", HANDLER);
			cb.loadConstant(index);
			cb.aload(0);
			cb.loadConstant(params.size());
			cb.anewarray(OBJECT);
			int local = 1;
			for (int i = 0; i < params.size(); i++) {
				String name = params.get(i).name();
				cb.dup();
				cb.loadConstant(i);
				load(cb, name, local);
				box(cb, name);
				cb.aastore();
				local += width(name);
			}
			cb.invokeinterface(HANDLER, "invoke", HANDLER_TYPE);
			unbox(cb, slot.returnType().name());
		}));
	}

	// One public super$m$a accessor per named (name, arity) with a superclass
	// implementation to call: what a proxy-super reaches.
	private static void writeSuperAccessors(ClassBuilder clb, JavaImplementation implementation) {
		JavaType superclass = implementation.superclass();
		if (superclass == null) {
			return;
		}
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
				desc.append(descriptor(param.name()));
			}
			String descriptor = desc.append(')').append(descriptor(target.executable().returnType().name())).toString();
			MethodTypeDesc type = MethodTypeDesc.ofDescriptor(descriptor);
			boolean iface = target.owner().isInterface();
			clb.withMethod(accessor, type, ClassFile.ACC_PUBLIC, mb -> mb.withCode(cb -> {
				cb.aload(0);
				int local = 1;
				for (JavaType param : target.executable().parameterTypes()) {
					load(cb, param.name(), local);
					local += width(param.name());
				}
				cb.invokespecial(ClassDesc.of(target.owner().name()), target.executable().name(), type, iface);
				returnValue(cb, target.executable().returnType().name());
			}));
		}
	}

	// The JVM descriptor of a getName() spelling.
	static String descriptor(String name) {
		return switch (name) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			case "void" -> "V";
			default -> name.startsWith("[") ? name.replace('.', '/') : "L" + name.replace('.', '/') + ";";
		};
	}

	// The JVM field descriptors concatenated in a method descriptor, in order.
	private static List<String> split(String paramsDesc) {
		List<String> names = new ArrayList<>();
		int i = 0;
		while (i < paramsDesc.length()) {
			char c = paramsDesc.charAt(i);
			if (c == 'L') {
				int end = paramsDesc.indexOf(';', i);
				names.add(paramsDesc.substring(i, end + 1));
				i = end + 1;
			}
			else if (c == '[') {
				int start = i;
				while (paramsDesc.charAt(i) == '[') {
					i++;
				}
				if (paramsDesc.charAt(i) == 'L') {
					i = paramsDesc.indexOf(';', i) + 1;
				}
				else {
					i++;
				}
				names.add(paramsDesc.substring(start, i));
			}
			else {
				names.add(paramsDesc.substring(i, i + 1));
				i++;
			}
		}
		return names;
	}

	private static int width(String name) {
		return "J".equals(name) || "D".equals(name) || "long".equals(name) || "double".equals(name) ? 2 : 1;
	}

	private static void load(CodeBuilder cb, String name, int slot) {
		switch (name) {
			case "J", "long" -> cb.lload(slot);
			case "D", "double" -> cb.dload(slot);
			case "F", "float" -> cb.fload(slot);
			case "Z", "B", "C", "S", "I", "boolean", "byte", "char", "short", "int" -> cb.iload(slot);
			default -> cb.aload(slot);
		}
	}

	// A primitive argument boxed as a Proxy boxes it for its handler.
	private static void box(CodeBuilder cb, String name) {
		switch (name) {
			case "Z", "boolean" -> cb.invokestatic(ClassDesc.of("java.lang.Boolean"), "valueOf",
					MethodTypeDesc.ofDescriptor("(Z)Ljava/lang/Boolean;"));
			case "B", "byte" -> cb.invokestatic(ClassDesc.of("java.lang.Byte"), "valueOf",
					MethodTypeDesc.ofDescriptor("(B)Ljava/lang/Byte;"));
			case "C", "char" -> cb.invokestatic(ClassDesc.of("java.lang.Character"), "valueOf",
					MethodTypeDesc.ofDescriptor("(C)Ljava/lang/Character;"));
			case "S", "short" -> cb.invokestatic(ClassDesc.of("java.lang.Short"), "valueOf",
					MethodTypeDesc.ofDescriptor("(S)Ljava/lang/Short;"));
			case "I", "int" -> cb.invokestatic(ClassDesc.of("java.lang.Integer"), "valueOf",
					MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Integer;"));
			case "J", "long" -> cb.invokestatic(ClassDesc.of("java.lang.Long"), "valueOf",
					MethodTypeDesc.ofDescriptor("(J)Ljava/lang/Long;"));
			case "F", "float" -> cb.invokestatic(ClassDesc.of("java.lang.Float"), "valueOf",
					MethodTypeDesc.ofDescriptor("(F)Ljava/lang/Float;"));
			case "D", "double" -> cb.invokestatic(ClassDesc.of("java.lang.Double"), "valueOf",
					MethodTypeDesc.ofDescriptor("(D)Ljava/lang/Double;"));
			default -> {
			}
		}
	}

	// The handler's boxed value as the method's return: a reference checked, a
	// primitive unboxed, void dropped.
	private static void unbox(CodeBuilder cb, String name) {
		switch (name) {
			case "V", "void" -> {
				cb.pop();
				cb.return_();
			}
			case "Z", "boolean" -> {
				cb.checkcast(ClassDesc.of("java.lang.Boolean"));
				cb.invokevirtual(ClassDesc.of("java.lang.Boolean"), "booleanValue", MethodTypeDesc.ofDescriptor("()Z"));
				cb.ireturn();
			}
			case "B", "byte" -> {
				cb.checkcast(ClassDesc.of("java.lang.Byte"));
				cb.invokevirtual(ClassDesc.of("java.lang.Byte"), "byteValue", MethodTypeDesc.ofDescriptor("()B"));
				cb.ireturn();
			}
			case "C", "char" -> {
				cb.checkcast(ClassDesc.of("java.lang.Character"));
				cb.invokevirtual(ClassDesc.of("java.lang.Character"), "charValue", MethodTypeDesc.ofDescriptor("()C"));
				cb.ireturn();
			}
			case "S", "short" -> {
				cb.checkcast(ClassDesc.of("java.lang.Short"));
				cb.invokevirtual(ClassDesc.of("java.lang.Short"), "shortValue", MethodTypeDesc.ofDescriptor("()S"));
				cb.ireturn();
			}
			case "I", "int" -> {
				cb.checkcast(ClassDesc.of("java.lang.Integer"));
				cb.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue", MethodTypeDesc.ofDescriptor("()I"));
				cb.ireturn();
			}
			case "J", "long" -> {
				cb.checkcast(ClassDesc.of("java.lang.Long"));
				cb.invokevirtual(ClassDesc.of("java.lang.Long"), "longValue", MethodTypeDesc.ofDescriptor("()J"));
				cb.lreturn();
			}
			case "F", "float" -> {
				cb.checkcast(ClassDesc.of("java.lang.Float"));
				cb.invokevirtual(ClassDesc.of("java.lang.Float"), "floatValue", MethodTypeDesc.ofDescriptor("()F"));
				cb.freturn();
			}
			case "D", "double" -> {
				cb.checkcast(ClassDesc.of("java.lang.Double"));
				cb.invokevirtual(ClassDesc.of("java.lang.Double"), "doubleValue", MethodTypeDesc.ofDescriptor("()D"));
				cb.dreturn();
			}
			default -> {
				cb.checkcast(ClassDesc.ofDescriptor(descriptor(name)));
				cb.areturn();
			}
		}
	}

	// A superclass implementation's value as the accessor's return: already the
	// method's type, never boxed.
	private static void returnValue(CodeBuilder cb, String name) {
		switch (name) {
			case "V", "void" -> cb.return_();
			case "Z", "B", "C", "S", "I", "boolean", "byte", "char", "short", "int" -> cb.ireturn();
			case "J", "long" -> cb.lreturn();
			case "F", "float" -> cb.freturn();
			case "D", "double" -> cb.dreturn();
			default -> cb.areturn();
		}
	}

}
