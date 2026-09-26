package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaField;
import am.ik.rontolisp.compiler.JavaImplementationType;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaSite;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java:} sites of one compile attempt that resolved before they run, each
 * compiled to a DIRECT call ({@code .kb/java-interop.md}, "Direct calls"): one private
 * static method per site shape, {@code _jsite$N}, which the site passes its evaluated
 * receiver and arguments. The method checks the receiver and every argument exactly as
 * the interpreter's {@code JavaInterop.invokeResolved} does -- same order, same messages
 * -- converts each argument to its parameter type inline, calls the member with
 * {@code invokevirtual} / {@code invokeinterface} / {@code invokestatic} / {@code new} +
 * {@code invokespecial} / {@code getfield} / {@code getstatic}, and converts the value
 * back as the bridge's {@code unmarshal} would. Nothing in it reflects, so a class whose
 * sites are all direct carries no bridge and needs no reachability metadata under GraalVM
 * native-image.
 * <p>
 * A value is checked by its KIND ({@link JavaKind}), computed from the compiled
 * representation as the bridge's {@code kindOf} computes it; the conversions are the
 * bridge's {@code convert} and {@code convertLong}, one arm per (kind, parameter type)
 * the resolution counted on. A function value where an interface is expected becomes the
 * interface's generated proxy class ({@link JvmJavaImplementations}), as the bridge makes
 * it a {@code java.lang.reflect.Proxy}: nothing here reflects.
 * <p>
 * Three shared helpers are emitted beside the sites when one needs them: {@code _jhost}
 * (the bridge's {@code isJavaObject}), {@code _junm} (its {@code unmarshal}, for a value
 * declared as a type a box, a string or an array may hide behind) and {@code _jarr} (its
 * {@code arrayToList}, over every array type without {@code java.lang.reflect.Array}).
 */
final class JvmJavaDirectSites {

	/** The prefix of a direct site's method name. */
	static final String SITE_PREFIX = "_jsite$";

	/** {@code _jhost(Object)Z}: whether a value is a wrapped host object. */
	static final String HOST = "_jhost";

	/** {@code _junm(Object)Object}: a Java value as the Lisp value it stands for. */
	static final String UNMARSHAL = "_junm";

	/** {@code _jarr(Object)Object}: a Java array as a Lisp list, anything else itself. */
	static final String ARRAY_TO_LIST = "_jarr";

	private static final String OBJECT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * Past this many values a site's method takes them in one {@code Object[]}: a method
	 * has at most 255 parameter slots.
	 */
	static final int MAX_SPREAD = 200;

	private static final int T_BOOLEAN = 4;

	private static final int T_CHAR = 5;

	private static final int T_FLOAT = 6;

	private static final int T_DOUBLE = 7;

	private static final int T_BYTE = 8;

	private static final int T_SHORT = 9;

	private static final int T_INT = 10;

	private static final int T_LONG = 11;

	/**
	 * A method the direct sites add to the class.
	 *
	 * @param name its name
	 * @param desc its descriptor
	 * @param maxStack the declared {@code max_stack}
	 * @param maxLocals the declared {@code max_locals}
	 * @param code the body
	 * @param exceptionTable the handlers
	 */
	record Method(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code,
			List<ByteCodeWriter.ExceptionTableEntry> exceptionTable) {
	}

	private final ConstantPool cp;

	private final ClassConstant thisClass;

	private final JavaClassLookup lookup;

	private final MethodrefConstant lispToString;

	private final Map<String, MethodrefConstant> sites = new LinkedHashMap<>();

	private final List<Method> methods = new ArrayList<>();

	private @Nullable MethodrefConstant host;

	private @Nullable MethodrefConstant unmarshal;

	private @Nullable MethodrefConstant arrayToList;

	private @Nullable JvmJavaImplementations implementations;

	/**
	 * @param cp the class's constant pool
	 * @param thisClass the class
	 * @param lookup the classes the sites resolved against
	 * @param lispToString the program's {@code _lispToString}: how a message shows a
	 * value
	 */
	JvmJavaDirectSites(ConstantPool cp, ClassConstant thisClass, JavaClassLookup lookup,
			MethodrefConstant lispToString) {
		this.cp = cp;
		this.thisClass = thisClass;
		this.lookup = lookup;
		this.lispToString = lispToString;
	}

	/**
	 * @return the methods to add to the class, in the order they were made
	 */
	List<Method> methods() {
		return List.copyOf(this.methods);
	}

	/**
	 * Sets the generated classes a function value where an interface is expected becomes.
	 * @param implementations the attempt's implementations
	 */
	void implementations(JvmJavaImplementations implementations) {
		this.implementations = implementations;
	}

	/**
	 * {@code _junm}: the bridge's {@code unmarshal}, made when first asked for.
	 * @return the helper
	 */
	MethodrefConstant unmarshalHelper() {
		return unmarshal();
	}

	/**
	 * The method a resolved site calls, made the first time a site of its shape asks.
	 * @param site the resolved site
	 * @param staticField for a {@code java:field} site, whether its first argument is a
	 * class-name literal (a static read, no object passed)
	 * @param valueCount how many values the call passes: the receiver or object, then the
	 * arguments
	 * @return the method, in the {@code (Object...)Object} shape or, past
	 * {@link #MAX_SPREAD} values, {@code (Object[])Object}
	 */
	MethodrefConstant site(JavaSite site, boolean staticField, int valueCount) {
		boolean packedValues = valueCount > MAX_SPREAD;
		String key = shapeKey(site, staticField, packedValues);
		MethodrefConstant cached = this.sites.get(key);
		if (cached != null) {
			return cached;
		}
		String name = SITE_PREFIX + this.sites.size();
		StringBuilder desc = new StringBuilder("(");
		if (packedValues) {
			desc.append("[Ljava/lang/Object;");
		}
		else {
			desc.append("Ljava/lang/Object;".repeat(valueCount));
		}
		desc.append(")Ljava/lang/Object;");
		Utf8Constant nameUtf = this.cp.addUtf8(name);
		Utf8Constant descUtf = this.cp.addUtf8(desc.toString());
		MethodrefConstant ref = this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(nameUtf, descUtf));
		// Registered before it is built, which may add the shared helpers first.
		this.sites.put(key, ref);
		this.methods.add(new SiteBuilder(site, staticField, valueCount, packedValues).build(nameUtf, descUtf));
		return ref;
	}

	private static String shapeKey(JavaSite site, boolean staticField, boolean packedValues) {
		StringBuilder key = new StringBuilder();
		key.append(site.operator()).append('|').append(site.staticClass()).append('|').append(site.designator());
		key.append('|').append(site.packed()).append('|').append(staticField).append('|').append(packedValues);
		for (JavaSite.Argument argument : site.arguments()) {
			key.append('|');
			for (JavaKind kind : argument.kinds()) {
				key.append(kind instanceof JavaType type ? "~" + type.name() : ((JavaKind.Lisp) kind).name())
					.append(',');
			}
			key.append(argument.declared());
		}
		return key.toString();
	}

	// --- constant-pool shorthands ---

	private ClassConstant cls(String internalName) {
		return this.cp.addClass(this.cp.addUtf8(internalName));
	}

	private ClassConstant cls(JavaType type) {
		return cls(internalName(type));
	}

	private MethodrefConstant method(String owner, String name, String desc) {
		return this.cp.addMethodref(cls(owner), this.cp.addNameAndType(this.cp.addUtf8(name), this.cp.addUtf8(desc)));
	}

	private MethodrefConstant interfaceMethod(String owner, String name, String desc) {
		return this.cp.addInterfaceMethodref(cls(owner),
				this.cp.addNameAndType(this.cp.addUtf8(name), this.cp.addUtf8(desc)));
	}

	private FieldrefConstant field(String owner, String name, String desc) {
		return this.cp.addFieldref(cls(owner), this.cp.addNameAndType(this.cp.addUtf8(name), this.cp.addUtf8(desc)));
	}

	private ConstantPool.StringConstant str(String value) {
		return this.cp.addString(value);
	}

	/** A class constant's name: slashes, and an array's descriptor as it is. */
	static String internalName(JavaType type) {
		return type.name().replace('.', '/');
	}

	/** A field descriptor ({@code I}, {@code Ljava/lang/String;}, {@code [I}). */
	static String descriptor(JavaType type) {
		return switch (type.name()) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			case "void" -> "V";
			default -> type.isArray() ? internalName(type) : "L" + internalName(type) + ";";
		};
	}

	/** The operand slots a value of this type takes: 2 for long and double. */
	private static int width(JavaType type) {
		return "long".equals(type.name()) || "double".equals(type.name()) ? 2 : 1;
	}

	private MethodrefConstant host() {
		MethodrefConstant ref = this.host;
		if (ref == null) {
			Utf8Constant name = this.cp.addUtf8(HOST);
			Utf8Constant desc = this.cp.addUtf8("(Ljava/lang/Object;)Z");
			ref = this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(name, desc));
			this.host = ref;
			this.methods.add(buildHost(name, desc));
		}
		return ref;
	}

	private MethodrefConstant unmarshal() {
		MethodrefConstant ref = this.unmarshal;
		if (ref == null) {
			Utf8Constant name = this.cp.addUtf8(UNMARSHAL);
			Utf8Constant desc = this.cp.addUtf8(OBJECT_DESC);
			ref = this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(name, desc));
			this.unmarshal = ref;
			MethodrefConstant toList = arrayToList();
			this.methods.add(buildUnmarshal(name, desc, toList));
		}
		return ref;
	}

	private MethodrefConstant arrayToList() {
		MethodrefConstant ref = this.arrayToList;
		if (ref == null) {
			Utf8Constant name = this.cp.addUtf8(ARRAY_TO_LIST);
			Utf8Constant desc = this.cp.addUtf8(OBJECT_DESC);
			ref = this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(name, desc));
			this.arrayToList = ref;
			// _jarr and _junm call each other (an Object[] element is unmarshalled): the
			// reference is published before the body naming _junm is built.
			this.methods.add(buildArrayToList(name, desc, unmarshal()));
		}
		return ref;
	}

	// --- shared throws ---

	/** {@code throw new RuntimeException(prefix + _lispToString(local))}. */
	private void throwDescribing(JvmAsm a, String prefix, int slot) {
		a.anew(cls("java/lang/RuntimeException"));
		a.dup();
		a.ldcString(str(prefix));
		a.aload(slot);
		a.invokestatic(this.lispToString);
		a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
		a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
	}

	/** {@code throw new RuntimeException(message)}. */
	private void throwMessage(JvmAsm a, String message) {
		a.anew(cls("java/lang/RuntimeException"));
		a.dup();
		a.ldcString(str(message));
		a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
	}

	/**
	 * One site's method: the receiver checks, each argument's kind dispatch and
	 * conversion, the call, the value's conversion back, and the two handlers.
	 */
	private final class SiteBuilder {

		private final JavaSite site;

		private final boolean staticField;

		private final int valueCount;

		private final boolean packedValues;

		private final JvmAsm a = new JvmAsm();

		// {start, end, handler label, catch type}: a handler's position is known once
		// its label is bound, after the body.
		private final List<int[]> pendingHandlers = new ArrayList<>();

		private final int[] valueSlots;

		private int nextSlot;

		private final int objectTemp;

		private final int longTemp;

		private final JavaType type;

		private final ClassConstant owner;

		private final String operator;

		SiteBuilder(JavaSite site, boolean staticField, int valueCount, boolean packedValues) {
			this.site = site;
			this.staticField = staticField;
			this.valueCount = valueCount;
			this.packedValues = packedValues;
			this.type = Objects.requireNonNull(
					JvmJavaDirectSites.this.lookup.find(Objects.requireNonNull(site.staticClass())), "resolved class");
			this.owner = cls(this.type);
			this.operator = "java:" + site.operator().name().toLowerCase(java.util.Locale.ROOT);
			this.valueSlots = new int[valueCount];
			if (packedValues) {
				this.nextSlot = 1;
				for (int i = 0; i < valueCount; i++) {
					this.a.aload(0);
					this.a.iconst(i);
					this.a.aaload();
					this.a.astore(this.nextSlot);
					this.valueSlots[i] = this.nextSlot++;
				}
			}
			else {
				for (int i = 0; i < valueCount; i++) {
					this.valueSlots[i] = i;
				}
				this.nextSlot = valueCount;
			}
			this.objectTemp = this.nextSlot++;
			this.longTemp = this.nextSlot;
			this.nextSlot += 2;
		}

		Method build(Utf8Constant name, Utf8Constant desc) {
			int classMissing = this.a.label();
			int memberFailed = this.a.label();
			boolean hasReceiver = this.site.operator() == JavaSite.Operator.CALL
					|| (this.site.operator() == JavaSite.Operator.FIELD && !this.staticField);
			if (hasReceiver) {
				emitReceiverChecks(classMissing);
			}
			else {
				// The class first, as the bridge's loadClass does: a class the run-time
				// class path lacks is "No such class", before any argument is looked at.
				int start = this.a.pos();
				this.a.ldcClass(this.owner);
				this.a.pop();
				handle(start, this.a.pos(), classMissing, "java/lang/NoClassDefFoundError");
			}
			int stackForCall;
			JavaType resultType;
			String failure;
			JavaField resolvedField = this.site.field();
			if (resolvedField != null) {
				stackForCall = 2;
				resultType = resolvedField.type();
				failure = "error reading field " + resolvedField.name() + ": ";
				emitFieldRead(resolvedField, hasReceiver, memberFailed);
			}
			else {
				JavaExecutable executable = Objects.requireNonNull(this.site.executable());
				int[] converted = emitArguments(executable, hasReceiver ? 1 : 0);
				stackForCall = emitInvoke(executable, converted, hasReceiver, memberFailed);
				resultType = executable.isConstructor() ? this.type : executable.returnType();
				failure = executable.isConstructor() ? "error constructing " + this.type.name() + ": "
						: "error calling " + this.type.name() + "." + executable.name() + ": ";
			}
			emitUnmarshal(resultType);
			this.a.areturn();
			// The handlers.
			this.a.bind(classMissing);
			this.a.pop();
			throwMessage(this.a, "No such class: " + this.type.name());
			this.a.bind(memberFailed);
			this.a.astore(this.objectTemp);
			this.a.anew(cls("java/lang/RuntimeException"));
			this.a.dup();
			this.a.ldcString(str(failure));
			this.a.aload(this.objectTemp);
			this.a.invokestatic(method("java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;"));
			this.a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
			this.a.invokespecial(method("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"));
			this.a.athrow();
			List<Integer> code = this.a.finish();
			List<ByteCodeWriter.ExceptionTableEntry> handlers = new ArrayList<>();
			for (int[] pending : this.pendingHandlers) {
				handlers.add(new ByteCodeWriter.ExceptionTableEntry(pending[0], pending[1], this.a.position(pending[2]),
						pending[3]));
			}
			return new Method(name, desc, Math.max(8, stackForCall + 6), this.nextSlot, code, handlers);
		}

		private void handle(int start, int end, int handlerLabel, String catchType) {
			this.pendingHandlers.add(new int[] { start, end, handlerLabel, cls(catchType).index() });
		}

		// java:call / java:field on an object: a host object (the bridge's
		// isJavaObject), then an instance of the site's class.
		private void emitReceiverChecks(int classMissing) {
			int receiver = this.valueSlots[0];
			boolean call = this.site.operator() == JavaSite.Operator.CALL;
			int hostObject = this.a.label();
			this.a.aload(receiver);
			this.a.invokestatic(host());
			this.a.branch(Opcode.IFNE, hostObject);
			throwDescribing(this.a, call ? "java:call expects a java object as the first argument, got "
					: "java:field expects a class-name string or a java object, got ", receiver);
			this.a.bind(hostObject);
			int instance = this.a.label();
			int start = this.a.pos();
			this.a.aload(receiver);
			this.a.instanceOf(this.owner);
			handle(start, this.a.pos(), classMissing, "java/lang/NoClassDefFoundError");
			this.a.branch(Opcode.IFNE, instance);
			throwDescribing(this.a, this.operator + ": the " + (call ? "receiver" : "object") + " is not a "
					+ this.type.name() + ", got ", receiver);
			this.a.bind(instance);
		}

		private void emitFieldRead(JavaField resolvedField, boolean hasReceiver, int memberFailed) {
			if (!hasReceiver && !resolvedField.isStatic()) {
				throw new IllegalStateException("a class-name java:field resolved to an instance field");
			}
			FieldrefConstant ref = field(internalName(this.type), resolvedField.name(),
					descriptor(resolvedField.type()));
			if (resolvedField.isStatic()) {
				int start = this.a.pos();
				this.a.getstatic(ref);
				handle(start, this.a.pos(), memberFailed, "java/lang/Throwable");
			}
			else {
				this.a.aload(this.valueSlots[0]);
				this.a.checkcast(this.owner);
				int start = this.a.pos();
				this.a.getfield(ref);
				handle(start, this.a.pos(), memberFailed, "java/lang/Throwable");
			}
		}

		/**
		 * Converts every argument into a local of its parameter type (a varargs tail into
		 * a fresh array), returning the locals in parameter order.
		 */
		private int[] emitArguments(JavaExecutable executable, int firstValue) {
			List<? extends JavaType> params = executable.parameterTypes();
			List<JavaSite.Argument> arguments = this.site.arguments();
			boolean packed = this.site.packed();
			int fixed = packed ? params.size() - 1 : params.size();
			int[] converted = new int[params.size()];
			for (int i = 0; i < fixed; i++) {
				JavaType param = params.get(i);
				emitArgument(i, this.valueSlots[firstValue + i], param, arguments.get(i));
				converted[i] = this.nextSlot;
				this.nextSlot += width(param);
				store(param, converted[i]);
			}
			if (packed) {
				JavaType arrayType = params.get(fixed);
				JavaType component = Objects.requireNonNull(arrayType.componentType());
				int tail = arguments.size() - fixed;
				int array = this.nextSlot++;
				int element = this.nextSlot;
				this.nextSlot += width(component);
				this.a.iconst(tail);
				newArray(component);
				this.a.astore(array);
				for (int j = 0; j < tail; j++) {
					emitArgument(fixed + j, this.valueSlots[firstValue + fixed + j], component,
							arguments.get(fixed + j));
					store(component, element);
					this.a.aload(array);
					this.a.iconst(j);
					load(component, element);
					this.a.op(arrayStore(component));
				}
				converted[fixed] = array;
			}
			return converted;
		}

		/**
		 * Leaves the converted argument on the stack, or throws for a value of no kind it
		 * counted on.
		 */
		private void emitArgument(int index, int slot, JavaType target, JavaSite.Argument argument) {
			int done = this.a.label();
			List<JavaKind> kinds = argument.kinds();
			boolean bothStrings = kinds.contains(JavaKind.Lisp.STRING) && kinds.contains(JavaKind.Lisp.STRING_1);
			for (JavaKind kind : kinds) {
				if (kind == JavaKind.Lisp.STRING_1 && bothStrings) {
					continue; // the STRING test covers both, converted alike
				}
				if (kind instanceof JavaType host && !isHostKind(host)) {
					continue; // the bridge's kindOf never answers it (a bignum's class)
				}
				int next = this.a.label();
				emitKindTest(slot, kind, bothStrings, next);
				emitConvert(slot, kind, target);
				this.a.branch(Opcode.GOTO, done);
				this.a.bind(next);
			}
			throwDescribing(this.a,
					this.operator + ": argument " + (index + 1) + " is not " + argument.expected() + ", got ", slot);
			this.a.bind(done);
			// The arms answer different reference types, which merge to their common
			// superclass or Object: a class parameter needs its own type back.
			if (!target.isPrimitive() && !target.isInterface() && !target.isArray()
					&& !"java.lang.Object".equals(target.name())) {
				this.a.checkcast(cls(target));
			}
		}

		// A class a value's kind can be: the bridge's kindOf answers the exact class of
		// anything outside the compiled representation, and never a bignum's.
		private boolean isHostKind(JavaType host) {
			return !"java.math.BigInteger".equals(host.name());
		}

		/**
		 * Falls through when the local holds a value of this kind, else jumps to fail.
		 */
		private void emitKindTest(int slot, JavaKind kind, boolean bothStrings, int fail) {
			JvmAsm a = this.a;
			if (kind instanceof JavaImplementationType implementation) {
				// An object a java:reify / java:proxy of the interface made: of a class
				// generated for one (JvmJavaImplementations), implementing it.
				a.aload(slot);
				a.instanceOf(cls(
						Objects.requireNonNull(JvmJavaDirectSites.this.implementations, "the attempt's implementations")
							.baseClass()));
				a.branch(Opcode.IFEQ, fail);
				a.aload(slot);
				a.instanceOf(cls(implementation.iface()));
				a.branch(Opcode.IFEQ, fail);
				return;
			}
			if (kind instanceof JavaType host) {
				a.aload(slot);
				a.branch(Opcode.IFNULL, fail);
				a.aload(slot);
				a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
				a.ldcClass(cls(host));
				a.branch(Opcode.IF_ACMPNE, fail);
				if ("java.util.ArrayList".equals(host.name())) {
					// A non-empty ArrayList whose first element is an Object[] is a Lisp
					// array in the compiled representation, not a host object.
					int hostObject = a.label();
					a.aload(slot);
					a.checkcast(cls("java/util/ArrayList"));
					a.invokevirtual(method("java/util/ArrayList", "isEmpty", "()Z"));
					a.branch(Opcode.IFNE, hostObject);
					a.aload(slot);
					a.checkcast(cls("java/util/ArrayList"));
					a.iconst(0);
					a.invokevirtual(method("java/util/ArrayList", "get", "(I)Ljava/lang/Object;"));
					a.instanceOf(cls("[Ljava/lang/Object;"));
					a.branch(Opcode.IFNE, fail);
					a.bind(hostObject);
				}
				return;
			}
			switch ((JavaKind.Lisp) kind) {
				case NIL -> {
					a.aload(slot);
					a.branch(Opcode.IFNONNULL, fail);
				}
				case T -> {
					a.ldcString(str("T"));
					a.aload(slot);
					a.invokevirtual(method("java/lang/String", "equals", "(Ljava/lang/Object;)Z"));
					a.branch(Opcode.IFEQ, fail);
				}
				case INTEGER -> {
					a.aload(slot);
					a.instanceOf(cls("java/lang/Long"));
					a.branch(Opcode.IFEQ, fail);
				}
				case FLOAT -> {
					a.aload(slot);
					a.instanceOf(cls("java/lang/Double"));
					a.branch(Opcode.IFEQ, fail);
				}
				case CHAR, SUPPLEMENTARY_CHAR -> {
					a.aload(slot);
					a.instanceOf(cls("[I"));
					a.branch(Opcode.IFEQ, fail);
					a.aload(slot);
					a.checkcast(cls("[I"));
					a.arraylength();
					a.iconst(1);
					a.branch(Opcode.IF_ICMPNE, fail);
					codePoint(slot);
					a.invokestatic(method("java/lang/Character", "isBmpCodePoint", "(I)Z"));
					a.branch(kind == JavaKind.Lisp.CHAR ? Opcode.IFEQ : Opcode.IFNE, fail);
				}
				case STRING, STRING_1 -> {
					ClassConstant string = cls("java/lang/String");
					MethodrefConstant length = method("java/lang/String", "length", "()I");
					a.aload(slot);
					a.instanceOf(string);
					a.branch(Opcode.IFEQ, fail);
					a.aload(slot);
					a.checkcast(string);
					a.invokevirtual(length);
					a.branch(Opcode.IFEQ, fail);
					a.aload(slot);
					a.checkcast(string);
					a.iconst(0);
					a.invokevirtual(method("java/lang/String", "charAt", "(I)C"));
					a.iconst('"');
					a.branch(Opcode.IF_ICMPNE, fail);
					if (!bothStrings) {
						// A one-character string is the quote-framed length 3.
						a.aload(slot);
						a.checkcast(string);
						a.invokevirtual(length);
						a.iconst(3);
						a.branch(kind == JavaKind.Lisp.STRING_1 ? Opcode.IF_ICMPNE : Opcode.IF_ICMPEQ, fail);
					}
				}
				case FUNCTION -> {
					ClassConstant objects = cls("[Ljava/lang/Object;");
					a.aload(slot);
					a.branch(Opcode.IFNULL, fail);
					a.aload(slot);
					a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
					a.ldcClass(objects);
					a.branch(Opcode.IF_ACMPNE, fail);
					a.aload(slot);
					a.checkcast(objects);
					a.arraylength();
					a.branch(Opcode.IFEQ, fail);
					a.aload(slot);
					a.checkcast(objects);
					a.iconst(0);
					a.aaload();
					a.instanceOf(cls("java/lang/Integer"));
					a.branch(Opcode.IFEQ, fail);
				}
			}
		}

		/**
		 * Pushes the value of a local of this kind as the target type (the bridge's
		 * convert).
		 */
		private void emitConvert(int slot, JavaKind kind, JavaType target) {
			JvmAsm a = this.a;
			if (kind instanceof JavaType) {
				a.aload(slot);
				return;
			}
			String name = target.name();
			switch ((JavaKind.Lisp) kind) {
				case NIL -> {
					if ("boolean".equals(name)) {
						a.iconst(0);
					}
					else if ("java.lang.Boolean".equals(name)) {
						a.getstatic(field("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;"));
					}
					else {
						a.aconstNull();
					}
				}
				case T -> {
					if ("boolean".equals(name)) {
						a.iconst(1);
					}
					else {
						a.getstatic(field("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;"));
					}
				}
				case INTEGER -> convertInteger(slot, name);
				case FLOAT -> {
					switch (name) {
						case "double" -> doubleValue(slot);
						case "float" -> {
							doubleValue(slot);
							a.d2f();
						}
						case "java.lang.Float" -> {
							doubleValue(slot);
							a.d2f();
							a.invokestatic(method("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;"));
						}
						default -> {
							// The Double itself, as the bridge passes it.
							a.aload(slot);
							a.checkcast(cls("java/lang/Double"));
						}
					}
				}
				case STRING, STRING_1 -> {
					switch (name) {
						case "char" -> firstCharacter(slot);
						case "java.lang.Character" -> {
							firstCharacter(slot);
							a.invokestatic(method("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;"));
						}
						default -> {
							// The unquoted string: substring(1, length - 1).
							ClassConstant string = cls("java/lang/String");
							a.aload(slot);
							a.checkcast(string);
							a.iconst(1);
							a.aload(slot);
							a.checkcast(string);
							a.invokevirtual(method("java/lang/String", "length", "()I"));
							a.iconst(1);
							a.op(Opcode.ISUB);
							a.invokevirtual(method("java/lang/String", "substring", "(II)Ljava/lang/String;"));
						}
					}
				}
				case CHAR, SUPPLEMENTARY_CHAR -> {
					codePoint(slot);
					JavaType character = JvmJavaDirectSites.this.lookup.find("java.lang.Character");
					boolean asChar = kind == JavaKind.Lisp.CHAR && ("char".equals(name)
							|| "java.lang.Character".equals(name)
							|| (!target.isPrimitive() && character != null && target.isAssignableFrom(character)));
					if ("char".equals(name)) {
						a.op(Opcode.I2C);
					}
					else if (asChar) {
						a.op(Opcode.I2C);
						a.invokestatic(method("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;"));
					}
					else if (!"int".equals(name)) {
						a.invokestatic(method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
					}
				}
				case FUNCTION -> {
					// The interface's generated proxy class over the function, as the
					// bridge makes it a Proxy: of(new Object[] { function }).
					a.iconst(1);
					a.anewarray(cls("java/lang/Object"));
					a.dup();
					a.iconst(0);
					a.aload(slot);
					a.aastore();
					a.invokestatic(Objects
						.requireNonNull(JvmJavaDirectSites.this.implementations, "the attempt's implementations")
						.proxyFactory(target));
				}
			}
		}

		// The bridge's convertLong over the integer in the local.
		private void convertInteger(int slot, String target) {
			JvmAsm a = this.a;
			longValue(slot);
			switch (target) {
				case "long" -> {
				}
				case "int" -> a.l2i();
				case "double" -> a.l2d();
				case "float" -> a.op(Opcode.L2F);
				case "short" -> {
					a.l2i();
					a.op(Opcode.I2S);
				}
				case "byte" -> {
					a.l2i();
					a.op(Opcode.I2B);
				}
				case "java.lang.Integer" -> {
					a.l2i();
					a.invokestatic(method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
				}
				case "java.lang.Long" -> a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				case "java.lang.Double" -> {
					a.l2d();
					a.invokestatic(method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				}
				case "java.lang.Float" -> {
					a.op(Opcode.L2F);
					a.invokestatic(method("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;"));
				}
				case "java.lang.Short" -> {
					a.l2i();
					a.op(Opcode.I2S);
					a.invokestatic(method("java/lang/Short", "valueOf", "(S)Ljava/lang/Short;"));
				}
				case "java.lang.Byte" -> {
					a.l2i();
					a.op(Opcode.I2B);
					a.invokestatic(method("java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;"));
				}
				default -> {
					// Boxed to the narrowest type that holds it, like a fixnum.
					int wide = a.label();
					int end = a.label();
					a.lstore(this.longTemp);
					a.lload(this.longTemp);
					a.lload(this.longTemp);
					a.l2i();
					a.i2l();
					a.lcmp();
					a.branch(Opcode.IFNE, wide);
					a.lload(this.longTemp);
					a.l2i();
					a.invokestatic(method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
					a.branch(Opcode.GOTO, end);
					a.bind(wide);
					a.lload(this.longTemp);
					a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
					a.bind(end);
				}
			}
		}

		private void longValue(int slot) {
			this.a.aload(slot);
			this.a.checkcast(cls("java/lang/Long"));
			this.a.invokevirtual(method("java/lang/Long", "longValue", "()J"));
		}

		private void doubleValue(int slot) {
			this.a.aload(slot);
			this.a.checkcast(cls("java/lang/Double"));
			this.a.invokevirtual(method("java/lang/Double", "doubleValue", "()D"));
		}

		private void codePoint(int slot) {
			this.a.aload(slot);
			this.a.checkcast(cls("[I"));
			this.a.iconst(0);
			this.a.iaload();
		}

		// The one character of a one-character string: charAt(1) of its quote frame.
		private void firstCharacter(int slot) {
			this.a.aload(slot);
			this.a.checkcast(cls("java/lang/String"));
			this.a.iconst(1);
			this.a.invokevirtual(method("java/lang/String", "charAt", "(I)C"));
		}

		/** The call itself; answers the operand slots it needs. */
		private int emitInvoke(JavaExecutable executable, int[] converted, boolean hasReceiver, int memberFailed) {
			JvmAsm a = this.a;
			List<? extends JavaType> params = executable.parameterTypes();
			StringBuilder desc = new StringBuilder("(");
			int slots = 0;
			for (JavaType param : params) {
				desc.append(descriptor(param));
				slots += width(param);
			}
			desc.append(')').append(executable.isConstructor() ? "V" : descriptor(executable.returnType()));
			String internal = internalName(this.type);
			if (executable.isConstructor()) {
				a.anew(this.owner);
				a.dup();
				loadArguments(params, converted);
				int start = a.pos();
				a.invokespecial(method(internal, "<init>", desc.toString()));
				handle(start, a.pos(), memberFailed, "java/lang/Throwable");
				return slots + 2;
			}
			boolean ownerIsInterface = this.type.isInterface();
			if (executable.isStatic()) {
				loadArguments(params, converted);
				int start = a.pos();
				a.invokestatic(ownerIsInterface ? interfaceMethod(internal, executable.name(), desc.toString())
						: method(internal, executable.name(), desc.toString()));
				handle(start, a.pos(), memberFailed, "java/lang/Throwable");
				return slots;
			}
			if (!hasReceiver) {
				throw new IllegalStateException("an instance method resolved for java:static");
			}
			a.aload(this.valueSlots[0]);
			a.checkcast(this.owner);
			loadArguments(params, converted);
			int start = a.pos();
			if (ownerIsInterface) {
				a.invokeinterface(interfaceMethod(internal, executable.name(), desc.toString()), slots + 1);
			}
			else {
				a.invokevirtual(method(internal, executable.name(), desc.toString()));
			}
			handle(start, a.pos(), memberFailed, "java/lang/Throwable");
			return slots + 1;
		}

		private void loadArguments(List<? extends JavaType> params, int[] converted) {
			for (int i = 0; i < params.size(); i++) {
				load(params.get(i), converted[i]);
			}
		}

		private void store(JavaType type, int slot) {
			switch (type.name()) {
				case "long" -> this.a.lstore(slot);
				case "double" -> this.a.dstore(slot);
				case "float" -> this.a.fstore(slot);
				case "boolean", "byte", "char", "short", "int" -> this.a.istore(slot);
				default -> this.a.astore(slot);
			}
		}

		private void load(JavaType type, int slot) {
			switch (type.name()) {
				case "long" -> this.a.lload(slot);
				case "double" -> this.a.dload(slot);
				case "float" -> this.a.fload(slot);
				case "boolean", "byte", "char", "short", "int" -> this.a.iload(slot);
				default -> this.a.aload(slot);
			}
		}

		private void newArray(JavaType component) {
			switch (component.name()) {
				case "boolean" -> this.a.newarray(T_BOOLEAN);
				case "char" -> this.a.newarray(T_CHAR);
				case "float" -> this.a.newarray(T_FLOAT);
				case "double" -> this.a.newarray(T_DOUBLE);
				case "byte" -> this.a.newarray(T_BYTE);
				case "short" -> this.a.newarray(T_SHORT);
				case "int" -> this.a.newarray(T_INT);
				case "long" -> this.a.newarray(T_LONG);
				default -> this.a.anewarray(cls(component));
			}
		}

		private static int arrayStore(JavaType component) {
			return switch (component.name()) {
				case "boolean", "byte" -> Opcode.BASTORE;
				case "char" -> Opcode.CASTORE;
				case "short" -> Opcode.SASTORE;
				case "int" -> Opcode.IASTORE;
				case "long" -> Opcode.LASTORE;
				case "float" -> Opcode.FASTORE;
				case "double" -> Opcode.DASTORE;
				default -> Opcode.AASTORE;
			};
		}

		/**
		 * Replaces the member's value on the stack by the Lisp value the bridge's
		 * unmarshal makes of it, specialized to the declared type.
		 */
		private void emitUnmarshal(JavaType declared) {
			JvmAsm a = this.a;
			switch (declared.name()) {
				case "void" -> a.aconstNull();
				case "boolean" -> {
					int nil = a.label();
					int end = a.label();
					a.branch(Opcode.IFEQ, nil);
					a.ldcString(str("T"));
					a.branch(Opcode.GOTO, end);
					a.bind(nil);
					a.aconstNull();
					a.bind(end);
				}
				case "byte", "short", "int" -> {
					a.i2l();
					a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				}
				case "long" -> a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				case "float" -> {
					a.f2d();
					a.invokestatic(method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				}
				case "double" -> a.invokestatic(method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				case "char" -> {
					int charTemp = this.longTemp;
					a.istore(charTemp);
					a.iconst(1);
					a.newarrayInt();
					a.dup();
					a.iconst(0);
					a.iload(charTemp);
					a.iastore();
				}
				case "java.lang.Long", "java.lang.Double" -> {
					// The object itself, as the bridge answers it.
				}
				case "java.lang.String" -> nullOr(() -> {
					a.ldcString(str("\""));
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/String"));
					a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
					a.ldcString(str("\""));
					a.invokevirtual(method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
				});
				case "java.lang.Boolean" -> {
					int nil = a.label();
					int end = a.label();
					a.astore(this.objectTemp);
					a.aload(this.objectTemp);
					a.branch(Opcode.IFNULL, nil);
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Boolean"));
					a.invokevirtual(method("java/lang/Boolean", "booleanValue", "()Z"));
					a.branch(Opcode.IFEQ, nil);
					a.ldcString(str("T"));
					a.branch(Opcode.GOTO, end);
					a.bind(nil);
					a.aconstNull();
					a.bind(end);
				}
				case "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> nullOr(() -> {
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Number"));
					a.invokevirtual(method("java/lang/Number", "longValue", "()J"));
					a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				});
				case "java.lang.Float" -> nullOr(() -> {
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Float"));
					a.invokevirtual(method("java/lang/Float", "floatValue", "()F"));
					a.f2d();
					a.invokestatic(method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				});
				case "java.lang.Character" -> nullOr(() -> {
					a.iconst(1);
					a.newarrayInt();
					a.dup();
					a.iconst(0);
					a.aload(this.objectTemp);
					a.checkcast(cls("java/lang/Character"));
					a.invokevirtual(method("java/lang/Character", "charValue", "()C"));
					a.iastore();
				});
				default -> {
					if (declared.isArray() || mayHideALispValue(declared)) {
						a.invokestatic(unmarshal());
					}
					// Any other class holds a host object, which stays itself.
				}
			}
		}

		// null stays nil; anything else is what the body leaves (reading the value from
		// objectTemp).
		private void nullOr(Runnable body) {
			int nil = this.a.label();
			int end = this.a.label();
			this.a.astore(this.objectTemp);
			this.a.aload(this.objectTemp);
			this.a.branch(Opcode.IFNULL, nil);
			body.run();
			this.a.branch(Opcode.GOTO, end);
			this.a.bind(nil);
			this.a.aconstNull();
			this.a.bind(end);
		}

		// A supertype of a box, of String or of an array: a value declared so may be one
		// of those at run time, which unmarshal turns into a Lisp value
		// (compiler/JavaStaticType.ofDeclared's rule).
		private boolean mayHideALispValue(JavaType declared) {
			for (String name : new String[] { "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
					"java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character",
					"java.lang.String", "[I" }) {
				JavaType unmarshalled = JvmJavaDirectSites.this.lookup.find(name);
				if (unmarshalled != null && declared.isAssignableFrom(unmarshalled)) {
					return true;
				}
			}
			return false;
		}

	}

	// --- the shared helpers ---

	// _jhost(Object)Z: the bridge's isJavaObject -- anything outside the compiled Lisp
	// representation (Long/Double/BigInteger integers, BigInteger[] ratios, String
	// symbols and strings, int[] characters, exact Object[] conses and function values).
	private Method buildHost(Utf8Constant name, Utf8Constant desc) {
		JvmAsm a = new JvmAsm();
		int no = a.label();
		a.aload(0);
		a.branch(Opcode.IFNULL, no);
		for (String excluded : new String[] { "java/lang/Long", "java/lang/Double", "java/math/BigInteger",
				"[Ljava/math/BigInteger;", "java/lang/String", "[I" }) {
			a.aload(0);
			a.instanceOf(cls(excluded));
			a.branch(Opcode.IFNE, no);
		}
		a.aload(0);
		a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
		a.ldcClass(cls("[Ljava/lang/Object;"));
		a.branch(Opcode.IF_ACMPEQ, no);
		a.iconst(1);
		a.ireturn();
		a.bind(no);
		a.iconst(0);
		a.ireturn();
		return new Method(name, desc, 2, 1, a.finish(), List.of());
	}

	// _junm(Object)Object: the bridge's unmarshal.
	private Method buildUnmarshal(Utf8Constant name, Utf8Constant desc, MethodrefConstant toList) {
		JvmAsm a = new JvmAsm();
		MethodrefConstant longValueOf = method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
		MethodrefConstant doubleValueOf = method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
		MethodrefConstant numberLongValue = method("java/lang/Number", "longValue", "()J");
		MethodrefConstant concat = method("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		int notNull = a.label();
		a.aload(0);
		a.branch(Opcode.IFNONNULL, notNull);
		a.aconstNull();
		a.areturn();
		a.bind(notNull);
		// Boolean -> t / nil
		int notBoolean = a.label();
		int falseValue = a.label();
		a.aload(0);
		a.instanceOf(cls("java/lang/Boolean"));
		a.branch(Opcode.IFEQ, notBoolean);
		a.aload(0);
		a.checkcast(cls("java/lang/Boolean"));
		a.invokevirtual(method("java/lang/Boolean", "booleanValue", "()Z"));
		a.branch(Opcode.IFEQ, falseValue);
		a.ldcString(str("T"));
		a.areturn();
		a.bind(falseValue);
		a.aconstNull();
		a.areturn();
		a.bind(notBoolean);
		// Integer / Short / Byte -> the long; Long / Double -> themselves
		for (String box : new String[] { "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
				"java/lang/Double" }) {
			int next = a.label();
			a.aload(0);
			a.instanceOf(cls(box));
			a.branch(Opcode.IFEQ, next);
			a.aload(0);
			if (!"java/lang/Long".equals(box) && !"java/lang/Double".equals(box)) {
				a.checkcast(cls("java/lang/Number"));
				a.invokevirtual(numberLongValue);
				a.invokestatic(longValueOf);
			}
			a.areturn();
			a.bind(next);
		}
		// Float -> the double
		int notFloat = a.label();
		a.aload(0);
		a.instanceOf(cls("java/lang/Float"));
		a.branch(Opcode.IFEQ, notFloat);
		a.aload(0);
		a.checkcast(cls("java/lang/Float"));
		a.invokevirtual(method("java/lang/Float", "floatValue", "()F"));
		a.f2d();
		a.invokestatic(doubleValueOf);
		a.areturn();
		a.bind(notFloat);
		// Character -> int[]{code unit}
		int notCharacter = a.label();
		a.aload(0);
		a.instanceOf(cls("java/lang/Character"));
		a.branch(Opcode.IFEQ, notCharacter);
		a.iconst(1);
		a.newarrayInt();
		a.dup();
		a.iconst(0);
		a.aload(0);
		a.checkcast(cls("java/lang/Character"));
		a.invokevirtual(method("java/lang/Character", "charValue", "()C"));
		a.iastore();
		a.areturn();
		a.bind(notCharacter);
		// String -> the quote-framed Lisp string
		int notString = a.label();
		a.aload(0);
		a.instanceOf(cls("java/lang/String"));
		a.branch(Opcode.IFEQ, notString);
		a.ldcString(str("\""));
		a.aload(0);
		a.checkcast(cls("java/lang/String"));
		a.invokevirtual(concat);
		a.ldcString(str("\""));
		a.invokevirtual(concat);
		a.areturn();
		a.bind(notString);
		// An array -> a list; any other object stays itself.
		a.aload(0);
		a.invokestatic(toList);
		a.areturn();
		return new Method(name, desc, 5, 1, a.finish(), List.of());
	}

	// _jarr(Object)Object: the bridge's arrayToList over every array type (elements
	// unmarshalled as Array.get would box them), or the value itself when it is no array.
	private Method buildArrayToList(Utf8Constant name, Utf8Constant desc, MethodrefConstant unmarshal) {
		JvmAsm a = new JvmAsm();
		MethodrefConstant longValueOf = method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
		MethodrefConstant doubleValueOf = method("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
		ClassConstant objectClass = cls("java/lang/Object");
		String[] arrays = { "[Ljava/lang/Object;", "[I", "[J", "[D", "[F", "[S", "[B", "[C", "[Z" };
		for (String array : arrays) {
			int next = a.label();
			a.aload(0);
			a.instanceOf(cls(array));
			a.branch(Opcode.IFEQ, next);
			// result = nil; array = (T[]) value; i = array.length
			a.aconstNull();
			a.astore(1);
			a.aload(0);
			a.checkcast(cls(array));
			a.astore(2);
			a.aload(2);
			a.arraylength();
			a.istore(3);
			int loop = a.label();
			int done = a.label();
			a.bind(loop);
			a.iinc(3, -1);
			a.iload(3);
			a.branch(Opcode.IFLT, done);
			// result = new Object[] { element(i), result }
			a.iconst(2);
			a.anewarray(objectClass);
			a.dup();
			a.iconst(0);
			a.aload(2);
			a.iload(3);
			switch (array) {
				case "[Ljava/lang/Object;" -> {
					a.aaload();
					a.invokestatic(unmarshal);
				}
				case "[I" -> {
					a.iaload();
					a.i2l();
					a.invokestatic(longValueOf);
				}
				case "[J" -> {
					a.laload();
					a.invokestatic(longValueOf);
				}
				case "[D" -> {
					a.daload();
					a.invokestatic(doubleValueOf);
				}
				case "[F" -> {
					a.faload();
					a.f2d();
					a.invokestatic(doubleValueOf);
				}
				case "[S" -> {
					a.saload();
					a.i2l();
					a.invokestatic(longValueOf);
				}
				case "[B" -> {
					a.baload();
					a.i2l();
					a.invokestatic(longValueOf);
				}
				case "[C" -> {
					// A char element is a Character, unmarshalled to int[]{c}.
					a.caload();
					a.istore(4);
					a.iconst(1);
					a.newarrayInt();
					a.dup();
					a.iconst(0);
					a.iload(4);
					a.iastore();
				}
				default -> {
					// boolean[]: t or nil.
					int falseValue = a.label();
					int stored = a.label();
					a.baload();
					a.branch(Opcode.IFEQ, falseValue);
					a.ldcString(str("T"));
					a.branch(Opcode.GOTO, stored);
					a.bind(falseValue);
					a.aconstNull();
					a.bind(stored);
				}
			}
			a.aastore();
			a.dup();
			a.iconst(1);
			a.aload(1);
			a.aastore();
			a.astore(1);
			a.branch(Opcode.GOTO, loop);
			a.bind(done);
			a.aload(1);
			a.areturn();
			a.bind(next);
		}
		a.aload(0);
		a.areturn();
		return new Method(name, desc, 8, 5, a.finish(), List.of());
	}

}
