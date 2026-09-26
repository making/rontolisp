package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaKind;
import am.ik.rontolisp.compiler.JavaOverloads;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;

/**
 * Converts a Lisp value of any kind to a Java type fixed at compile time, exactly as the
 * bridge's {@code marshal} converts a value a {@code java:reify} / {@code java:proxy}
 * function answers: what a compiled program does with that value before its generated
 * class returns it ({@link JvmJavaImplementations}). A function is never made a proxy on
 * the way back (the interpreter's and the bridge's rule for a returned value), so no
 * conversion here needs a class of its own -- a proxy of an interface return type would
 * need the proxies of its methods' interface return types in turn, a closure that reached
 * nineteen classes for one {@code CharSequence} (measured 2026-09-26).
 * <p>
 * Two helpers per type, emitted into the program class the first time the type is asked
 * for: {@code _jfit$N(Object)Z}, whether the bridge's cost of the value for the type is
 * not {@code NO_MATCH} -- the value's kind tested as the bridge's {@code kindOf} tests
 * it, each kind's answer {@link JavaOverloads#kindCost} fixed at compile time, a
 * sequence's elements tested for the component type -- and {@code _jto$N(Object)T}, the
 * value converted as {@code convert} converts it (a sequence to a fresh array or
 * {@code ArrayList}), for a value that fits. A mutable character vector is rendered to
 * the string it spells first ({@code _strv}), as the bridge renders it.
 * {@code _jseq(Object)[Ljava/lang/Object;} lists a proper list's or a rank-1 Lisp array's
 * elements (the bridge's {@code properListElements} and array arm), or answers
 * {@code null}.
 */
final class JvmJavaMarshal {

	/** The prefix of a type's fit test. */
	static final String FIT_PREFIX = "_jfit$";

	/** The prefix of a type's conversion. */
	static final String TO_PREFIX = "_jto$";

	/** {@code _jseq(Object)Object[]}: a sequence's elements, or {@code null}. */
	static final String SEQUENCE = "_jseq";

	private static final String OBJECT_ARRAY = "[Ljava/lang/Object;";

	private final ConstantPool cp;

	private final ClassConstant thisClass;

	private final JavaClassLookup lookup;

	private @Nullable MethodrefConstant strv;

	private final Map<String, MethodrefConstant> fits = new LinkedHashMap<>();

	private final Map<String, MethodrefConstant> converts = new LinkedHashMap<>();

	private @Nullable MethodrefConstant sequence;

	private final List<JvmJavaDirectSites.Method> methods = new ArrayList<>();

	/**
	 * @param cp the program class's constant pool
	 * @param thisClass the program class
	 * @param lookup the classes the program resolves against
	 */
	JvmJavaMarshal(ConstantPool cp, ClassConstant thisClass, JavaClassLookup lookup) {
		this.cp = cp;
		this.thisClass = thisClass;
		this.lookup = lookup;
	}

	/**
	 * Sets the program's {@code _strv}, or {@code null} when the program carries no array
	 * runtime (and so no mutable character vector).
	 * @param strv the method
	 */
	void strv(@Nullable MethodrefConstant strv) {
		this.strv = strv;
	}

	/**
	 * @return the helper methods to add to the program class, in the order they were made
	 */
	List<JvmJavaDirectSites.Method> methods() {
		return List.copyOf(this.methods);
	}

	/**
	 * The fit test of a type.
	 * @param target the type
	 * @return {@code _jfit$N(Object)Z}
	 */
	MethodrefConstant fits(JavaType target) {
		MethodrefConstant cached = this.fits.get(target.name());
		if (cached != null) {
			return cached;
		}
		Utf8Constant name = this.cp.addUtf8(FIT_PREFIX + this.fits.size());
		Utf8Constant desc = this.cp.addUtf8("(Ljava/lang/Object;)Z");
		MethodrefConstant ref = this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(name, desc));
		// Registered before its body is built: a sequence type's test calls its
		// component's, which may be its own (Object's elements are Objects).
		this.fits.put(target.name(), ref);
		this.methods.add(new Body(target, true).build(name, desc));
		return ref;
	}

	/**
	 * The conversion to a type, for a value its {@link #fits} test accepts.
	 * @param target the type
	 * @return {@code _jto$N(Object)T}
	 */
	MethodrefConstant convert(JavaType target) {
		MethodrefConstant cached = this.converts.get(target.name());
		if (cached != null) {
			return cached;
		}
		Utf8Constant name = this.cp.addUtf8(TO_PREFIX + this.converts.size());
		Utf8Constant desc = this.cp.addUtf8("(Ljava/lang/Object;)" + JvmJavaDirectSites.descriptor(target));
		MethodrefConstant ref = this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(name, desc));
		this.converts.put(target.name(), ref);
		this.methods.add(new Body(target, false).build(name, desc));
		return ref;
	}

	private MethodrefConstant sequence() {
		MethodrefConstant ref = this.sequence;
		if (ref == null) {
			Utf8Constant name = this.cp.addUtf8(SEQUENCE);
			Utf8Constant desc = this.cp.addUtf8("(Ljava/lang/Object;)[Ljava/lang/Object;");
			ref = this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(name, desc));
			this.sequence = ref;
			this.methods.add(buildSequence(name, desc));
		}
		return ref;
	}

	// --- constant-pool shorthands ---

	private ClassConstant cls(String internalName) {
		return this.cp.addClass(this.cp.addUtf8(internalName));
	}

	private MethodrefConstant method(String owner, String name, String desc) {
		return this.cp.addMethodref(cls(owner), this.cp.addNameAndType(this.cp.addUtf8(name), this.cp.addUtf8(desc)));
	}

	private @Nullable JavaType type(String name) {
		return this.lookup.find(name);
	}

	// Whether a value of a Lisp kind converts to the type (the bridge's kindCost) -- a
	// function never does: it is not made a proxy on the way back.
	private boolean accepts(JavaKind kind, JavaType target) {
		return kind != JavaKind.Lisp.FUNCTION
				&& JavaOverloads.kindCost(kind, target, this.lookup) != JavaOverloads.NO_MATCH;
	}

	// Whether the type takes a sequence: an array, or a supertype of ArrayList.
	private boolean takesSequence(JavaType target) {
		if (target.isArray()) {
			return true;
		}
		JavaType arrayList = type("java.util.ArrayList");
		return !target.isPrimitive() && arrayList != null && target.isAssignableFrom(arrayList);
	}

	/**
	 * One type's fit test or conversion: the bridge's {@code kindOf} tests in its order,
	 * each kind's arm answering (the test) or converting and returning (the conversion).
	 */
	private final class Body {

		private final JavaType target;

		private final boolean fitsOnly;

		private final JvmAsm a = new JvmAsm();

		private final int none;

		Body(JavaType target, boolean fitsOnly) {
			this.target = target;
			this.fitsOnly = fitsOnly;
			this.none = this.a.label();
		}

		JvmJavaDirectSites.Method build(Utf8Constant name, Utf8Constant desc) {
			JvmAsm a = this.a;
			MethodrefConstant strv = JvmJavaMarshal.this.strv;
			if (strv != null) {
				// A mutable character vector is the string it spells: an ArrayList is
				// rendered, as the bridge's rendered() renders it (_strv reads any other
				// value as a character vector).
				int rendered = a.label();
				a.aload(0);
				a.instanceOf(cls("java/util/ArrayList"));
				a.branch(Opcode.IFEQ, rendered);
				a.aload(0);
				a.invokestatic(strv);
				a.astore(0);
				a.bind(rendered);
			}
			int host = a.label();
			int seq = a.label();
			// nil
			int notNil = a.label();
			a.aload(0);
			a.branch(Opcode.IFNONNULL, notNil);
			arm(JavaKind.Lisp.NIL);
			a.bind(notNil);
			// an integer
			int notLong = a.label();
			a.aload(0);
			a.instanceOf(cls("java/lang/Long"));
			a.branch(Opcode.IFEQ, notLong);
			arm(JavaKind.Lisp.INTEGER);
			a.bind(notLong);
			// a float
			int notDouble = a.label();
			a.aload(0);
			a.instanceOf(cls("java/lang/Double"));
			a.branch(Opcode.IFEQ, notDouble);
			arm(JavaKind.Lisp.FLOAT);
			a.bind(notDouble);
			// a character: an int[] of length 1 (any other int[] is a host object)
			int notChars = a.label();
			int supplementary = a.label();
			a.aload(0);
			a.instanceOf(cls("[I"));
			a.branch(Opcode.IFEQ, notChars);
			a.aload(0);
			a.checkcast(cls("[I"));
			a.arraylength();
			a.iconst(1);
			a.branch(Opcode.IF_ICMPNE, host);
			codePoint();
			a.invokestatic(method("java/lang/Character", "isBmpCodePoint", "(I)Z"));
			a.branch(Opcode.IFEQ, supplementary);
			arm(JavaKind.Lisp.CHAR);
			a.bind(supplementary);
			arm(JavaKind.Lisp.SUPPLEMENTARY_CHAR);
			a.bind(notChars);
			// a string, t, or another symbol
			int notString = a.label();
			int symbol = a.label();
			int longer = a.label();
			ClassConstant string = cls("java/lang/String");
			MethodrefConstant length = method("java/lang/String", "length", "()I");
			a.aload(0);
			a.instanceOf(string);
			a.branch(Opcode.IFEQ, notString);
			a.aload(0);
			a.checkcast(string);
			a.invokevirtual(length);
			a.branch(Opcode.IFEQ, symbol);
			a.aload(0);
			a.checkcast(string);
			a.iconst(0);
			a.invokevirtual(method("java/lang/String", "charAt", "(I)C"));
			a.iconst('"');
			a.branch(Opcode.IF_ICMPNE, symbol);
			a.aload(0);
			a.checkcast(string);
			a.invokevirtual(length);
			a.iconst(3);
			a.branch(Opcode.IF_ICMPNE, longer);
			arm(JavaKind.Lisp.STRING_1);
			a.bind(longer);
			arm(JavaKind.Lisp.STRING);
			a.bind(symbol);
			a.ldcString(JvmJavaMarshal.this.cp.addString("T"));
			a.aload(0);
			a.invokevirtual(method("java/lang/String", "equals", "(Ljava/lang/Object;)Z"));
			a.branch(Opcode.IFEQ, this.none);
			arm(JavaKind.Lisp.T);
			a.bind(notString);
			// an exact Object[]: a function value (an Integer first), else a cons
			int notCells = a.label();
			ClassConstant objects = cls(OBJECT_ARRAY);
			a.aload(0);
			a.invokevirtual(method("java/lang/Object", "getClass", "()Ljava/lang/Class;"));
			a.ldcClass(objects);
			a.branch(Opcode.IF_ACMPNE, notCells);
			a.aload(0);
			a.checkcast(objects);
			a.arraylength();
			a.branch(Opcode.IFEQ, seq);
			a.aload(0);
			a.checkcast(objects);
			a.iconst(0);
			a.aaload();
			a.instanceOf(cls("java/lang/Integer"));
			a.branch(Opcode.IFEQ, seq);
			arm(JavaKind.Lisp.FUNCTION);
			a.bind(notCells);
			// a bignum or a ratio: never bridged
			a.aload(0);
			a.instanceOf(cls("java/math/BigInteger"));
			a.branch(Opcode.IFNE, this.none);
			a.aload(0);
			a.instanceOf(cls("[Ljava/math/BigInteger;"));
			a.branch(Opcode.IFNE, this.none);
			// a Lisp array: an ArrayList whose first element is its Object[] header
			ClassConstant arrayList = cls("java/util/ArrayList");
			a.aload(0);
			a.instanceOf(arrayList);
			a.branch(Opcode.IFEQ, host);
			a.aload(0);
			a.checkcast(arrayList);
			a.invokevirtual(method("java/util/ArrayList", "isEmpty", "()Z"));
			a.branch(Opcode.IFNE, host);
			a.aload(0);
			a.checkcast(arrayList);
			a.iconst(0);
			a.invokevirtual(method("java/util/ArrayList", "get", "(I)Ljava/lang/Object;"));
			a.instanceOf(objects);
			a.branch(Opcode.IFNE, seq);
			// any other value is a host object
			a.bind(host);
			hostArm();
			a.bind(seq);
			sequenceArm();
			a.bind(this.none);
			if (this.fitsOnly) {
				a.iconst(0);
				a.ireturn();
			}
			else {
				// A caller converts only what the test accepted.
				ClassConstant illegal = cls("java/lang/IllegalStateException");
				a.anew(illegal);
				a.dup();
				a.ldcString(JvmJavaMarshal.this.cp
					.addString("java interop: the value does not convert to " + this.target.name()));
				a.invokespecial(method("java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V"));
				a.athrow();
			}
			return new JvmJavaDirectSites.Method(name, desc, 8, 5, a.finish(), List.of());
		}

		// The arm of a value of this kind: answer whether it fits, or convert and
		// return it -- or, when the kind never converts to the type, go to none.
		private void arm(JavaKind.Lisp kind) {
			JvmAsm a = this.a;
			if (!accepts(kind, this.target)) {
				a.branch(Opcode.GOTO, this.none);
				return;
			}
			if (this.fitsOnly) {
				a.iconst(1);
				a.ireturn();
				return;
			}
			convertArm(kind);
		}

		private void hostArm() {
			JvmAsm a = this.a;
			if (this.target.isPrimitive()) {
				a.branch(Opcode.GOTO, this.none);
				return;
			}
			if (!"java.lang.Object".equals(this.target.name())) {
				a.aload(0);
				a.instanceOf(cls(JvmJavaDirectSites.internalName(this.target)));
				a.branch(Opcode.IFEQ, this.none);
			}
			if (this.fitsOnly) {
				a.iconst(1);
				a.ireturn();
				return;
			}
			a.aload(0);
			returnReference();
		}

		// A proper list or a rank-1 Lisp array: every element fits the component (Object
		// for a List), and converts to a fresh array or ArrayList of the converted
		// elements.
		private void sequenceArm() {
			JvmAsm a = this.a;
			if (!takesSequence(this.target)) {
				a.branch(Opcode.GOTO, this.none);
				return;
			}
			JavaType element = this.target.isArray() ? Objects.requireNonNull(this.target.componentType())
					: Objects.requireNonNull(type("java.lang.Object"));
			// 1 = the elements, 2 = i, 3 = the result
			a.aload(0);
			a.invokestatic(sequence());
			a.astore(1);
			a.aload(1);
			a.branch(Opcode.IFNULL, this.none);
			int loop = a.label();
			int done = a.label();
			if (this.fitsOnly) {
				MethodrefConstant fit = fits(element);
				a.iconst(0);
				a.istore(2);
				a.bind(loop);
				a.iload(2);
				a.aload(1);
				a.arraylength();
				a.branch(Opcode.IF_ICMPGE, done);
				a.aload(1);
				a.iload(2);
				a.aaload();
				a.invokestatic(fit);
				a.branch(Opcode.IFEQ, this.none);
				a.iinc(2, 1);
				a.branch(Opcode.GOTO, loop);
				a.bind(done);
				a.iconst(1);
				a.ireturn();
				return;
			}
			MethodrefConstant to = convert(element);
			if (this.target.isArray()) {
				a.aload(1);
				a.arraylength();
				newArray(element);
			}
			else {
				ClassConstant arrayList = cls("java/util/ArrayList");
				a.anew(arrayList);
				a.dup();
				a.aload(1);
				a.arraylength();
				a.invokespecial(method("java/util/ArrayList", "<init>", "(I)V"));
			}
			a.astore(3);
			a.iconst(0);
			a.istore(2);
			a.bind(loop);
			a.iload(2);
			a.aload(1);
			a.arraylength();
			a.branch(Opcode.IF_ICMPGE, done);
			a.aload(3);
			if (this.target.isArray()) {
				a.checkcast(cls(JvmJavaDirectSites.internalName(this.target)));
				a.iload(2);
				a.aload(1);
				a.iload(2);
				a.aaload();
				a.invokestatic(to);
				a.op(arrayStore(element));
			}
			else {
				a.checkcast(cls("java/util/ArrayList"));
				a.aload(1);
				a.iload(2);
				a.aaload();
				a.invokestatic(to);
				a.invokevirtual(method("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z"));
				a.pop();
			}
			a.iinc(2, 1);
			a.branch(Opcode.GOTO, loop);
			a.bind(done);
			a.aload(3);
			returnReference();
		}

		// The bridge's convert of a value of this kind to the type; returns it.
		private void convertArm(JavaKind.Lisp kind) {
			JvmAsm a = this.a;
			String name = this.target.name();
			switch (kind) {
				case NIL -> {
					if ("boolean".equals(name)) {
						a.iconst(0);
						a.ireturn();
					}
					else {
						if ("java.lang.Boolean".equals(name)) {
							a.getstatic(JvmJavaMarshal.this.cp.addFieldref(cls("java/lang/Boolean"),
									JvmJavaMarshal.this.cp.addNameAndType(JvmJavaMarshal.this.cp.addUtf8("FALSE"),
											JvmJavaMarshal.this.cp.addUtf8("Ljava/lang/Boolean;"))));
						}
						else {
							a.aconstNull();
						}
						a.areturn();
					}
				}
				case T -> {
					if ("boolean".equals(name)) {
						a.iconst(1);
						a.ireturn();
					}
					else {
						a.getstatic(JvmJavaMarshal.this.cp.addFieldref(cls("java/lang/Boolean"),
								JvmJavaMarshal.this.cp.addNameAndType(JvmJavaMarshal.this.cp.addUtf8("TRUE"),
										JvmJavaMarshal.this.cp.addUtf8("Ljava/lang/Boolean;"))));
						returnReference();
					}
				}
				case INTEGER -> convertInteger(name);
				case FLOAT -> convertFloat(name);
				case STRING, STRING_1 -> convertString(name);
				case CHAR, SUPPLEMENTARY_CHAR -> convertCharacter(kind, name);
				case FUNCTION -> throw new IllegalStateException("a function is not converted on the way back");
			}
		}

		// The bridge's convertLong over the Long in slot 0.
		private void convertInteger(String name) {
			JvmAsm a = this.a;
			a.aload(0);
			a.checkcast(cls("java/lang/Long"));
			a.invokevirtual(method("java/lang/Long", "longValue", "()J"));
			switch (name) {
				case "long" -> {
					a.op(Opcode.LRETURN);
					return;
				}
				case "int" -> {
					a.l2i();
					a.ireturn();
					return;
				}
				case "short" -> {
					a.l2i();
					a.op(Opcode.I2S);
					a.ireturn();
					return;
				}
				case "byte" -> {
					a.l2i();
					a.op(Opcode.I2B);
					a.ireturn();
					return;
				}
				case "double" -> {
					a.l2d();
					a.op(Opcode.DRETURN);
					return;
				}
				case "float" -> {
					a.op(Opcode.L2F);
					a.op(Opcode.FRETURN);
					return;
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
					// Boxed to the narrowest type that holds it, like a fixnum: 3/4 = the
					// long.
					int wide = a.label();
					a.lstore(3);
					a.lload(3);
					a.lload(3);
					a.l2i();
					a.i2l();
					a.lcmp();
					a.branch(Opcode.IFNE, wide);
					a.lload(3);
					a.l2i();
					a.invokestatic(method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
					returnReference();
					a.bind(wide);
					a.lload(3);
					a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				}
			}
			returnReference();
		}

		// A Double: its double, a float, or itself.
		private void convertFloat(String name) {
			JvmAsm a = this.a;
			a.aload(0);
			a.checkcast(cls("java/lang/Double"));
			switch (name) {
				case "double" -> {
					a.invokevirtual(method("java/lang/Double", "doubleValue", "()D"));
					a.op(Opcode.DRETURN);
				}
				case "float" -> {
					a.invokevirtual(method("java/lang/Double", "doubleValue", "()D"));
					a.d2f();
					a.op(Opcode.FRETURN);
				}
				case "java.lang.Float" -> {
					a.invokevirtual(method("java/lang/Double", "doubleValue", "()D"));
					a.d2f();
					a.invokestatic(method("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;"));
					returnReference();
				}
				default -> returnReference();
			}
		}

		// A string: the unquoted string, or (a one-character one for a char) its one
		// character.
		private void convertString(String name) {
			JvmAsm a = this.a;
			ClassConstant string = cls("java/lang/String");
			JavaType stringType = type("java.lang.String");
			if (!this.target.isPrimitive() && stringType != null && this.target.isAssignableFrom(stringType)) {
				// substring(1, length - 1)
				a.aload(0);
				a.checkcast(string);
				a.iconst(1);
				a.aload(0);
				a.checkcast(string);
				a.invokevirtual(method("java/lang/String", "length", "()I"));
				a.iconst(1);
				a.op(Opcode.ISUB);
				a.invokevirtual(method("java/lang/String", "substring", "(II)Ljava/lang/String;"));
				returnReference();
				return;
			}
			a.aload(0);
			a.checkcast(string);
			a.iconst(1);
			a.invokevirtual(method("java/lang/String", "charAt", "(I)C"));
			if ("char".equals(name)) {
				a.ireturn();
				return;
			}
			a.invokestatic(method("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;"));
			returnReference();
		}

		// A character: a char or Character for a type that takes one (a BMP code point
		// only), else its code point as an int or Integer.
		private void convertCharacter(JavaKind.Lisp kind, String name) {
			JvmAsm a = this.a;
			codePoint();
			JavaType character = type("java.lang.Character");
			boolean asChar = kind == JavaKind.Lisp.CHAR && ("char".equals(name) || "java.lang.Character".equals(name)
					|| (!this.target.isPrimitive() && character != null && this.target.isAssignableFrom(character)));
			if ("char".equals(name)) {
				a.op(Opcode.I2C);
				a.ireturn();
				return;
			}
			if (asChar) {
				a.op(Opcode.I2C);
				a.invokestatic(method("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;"));
				returnReference();
				return;
			}
			if ("int".equals(name)) {
				a.ireturn();
				return;
			}
			a.invokestatic(method("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
			returnReference();
		}

		private void codePoint() {
			this.a.aload(0);
			this.a.checkcast(cls("[I"));
			this.a.iconst(0);
			this.a.iaload();
		}

		// Returns the reference on the stack as the type (a class or an array cast; the
		// verifier takes an interface as Object).
		private void returnReference() {
			if (!this.target.isInterface() && !"java.lang.Object".equals(this.target.name())) {
				this.a.checkcast(cls(JvmJavaDirectSites.internalName(this.target)));
			}
			this.a.areturn();
		}

		private void newArray(JavaType component) {
			switch (component.name()) {
				case "boolean" -> this.a.newarray(4);
				case "char" -> this.a.newarray(5);
				case "float" -> this.a.newarray(6);
				case "double" -> this.a.newarray(7);
				case "byte" -> this.a.newarray(8);
				case "short" -> this.a.newarray(9);
				case "int" -> this.a.newarray(10);
				case "long" -> this.a.newarray(11);
				default -> this.a.anewarray(cls(JvmJavaDirectSites.internalName(component)));
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

	}

	// _jseq(Object)Object[]: the elements of a proper list (exact Object[2] cells whose
	// car is no Integer, ending in nil) or of a rank-1 Lisp array (up to its fill
	// pointer; a packed one's longs boxed, Long.MIN_VALUE as nil), else null -- the
	// bridge's properListElements and its array arm.
	private JvmJavaDirectSites.Method buildSequence(Utf8Constant name, Utf8Constant desc) {
		JvmAsm a = new JvmAsm();
		ClassConstant objects = cls(OBJECT_ARRAY);
		ClassConstant arrayList = cls("java/util/ArrayList");
		MethodrefConstant getClass = method("java/lang/Object", "getClass", "()Ljava/lang/Class;");
		int notCells = a.label();
		int improper = a.label();
		// 1 = the cell, 2 = the count, 3 = the elements, 4 = i
		a.aload(0);
		a.invokevirtual(getClass);
		a.ldcClass(objects);
		a.branch(Opcode.IF_ACMPNE, notCells);
		// count the proper list
		int count = a.label();
		int counted = a.label();
		a.aload(0);
		a.astore(1);
		a.iconst(0);
		a.istore(2);
		a.bind(count);
		a.aload(1);
		a.branch(Opcode.IFNULL, counted);
		cellTest(a, objects, getClass, improper);
		a.iinc(2, 1);
		a.aload(1);
		a.checkcast(objects);
		a.iconst(1);
		a.aaload();
		a.astore(1);
		a.branch(Opcode.GOTO, count);
		a.bind(counted);
		// copy the cars
		int copy = a.label();
		int copied = a.label();
		a.iload(2);
		a.anewarray(cls("java/lang/Object"));
		a.astore(3);
		a.aload(0);
		a.astore(1);
		a.iconst(0);
		a.istore(4);
		a.bind(copy);
		a.aload(1);
		a.branch(Opcode.IFNULL, copied);
		a.aload(3);
		a.iload(4);
		a.aload(1);
		a.checkcast(objects);
		a.iconst(0);
		a.aaload();
		a.aastore();
		a.iinc(4, 1);
		a.aload(1);
		a.checkcast(objects);
		a.iconst(1);
		a.aaload();
		a.astore(1);
		a.branch(Opcode.GOTO, copy);
		a.bind(copied);
		a.aload(3);
		a.areturn();
		a.bind(improper);
		a.aconstNull();
		a.areturn();
		// A Lisp array: slot 0 is the {dims, fill pointer, ...} header.
		a.bind(notCells);
		int notArray = a.label();
		a.aload(0);
		a.instanceOf(arrayList);
		a.branch(Opcode.IFEQ, notArray);
		a.aload(0);
		a.checkcast(arrayList);
		a.invokevirtual(method("java/util/ArrayList", "isEmpty", "()Z"));
		a.branch(Opcode.IFNE, notArray);
		a.aload(0);
		a.checkcast(arrayList);
		a.iconst(0);
		a.invokevirtual(method("java/util/ArrayList", "get", "(I)Ljava/lang/Object;"));
		a.instanceOf(objects);
		a.branch(Opcode.IFEQ, notArray);
		// 1 = the header
		a.aload(0);
		a.checkcast(arrayList);
		a.iconst(0);
		a.invokevirtual(method("java/util/ArrayList", "get", "(I)Ljava/lang/Object;"));
		a.checkcast(objects);
		a.astore(1);
		// rank 1 only: header[0] is an Object[] of one dimension
		a.aload(1);
		a.iconst(0);
		a.aaload();
		a.instanceOf(objects);
		a.branch(Opcode.IFEQ, notArray);
		a.aload(1);
		a.iconst(0);
		a.aaload();
		a.checkcast(objects);
		a.arraylength();
		a.iconst(1);
		a.branch(Opcode.IF_ICMPNE, notArray);
		// the packed shape: header.length == 6 && header[5] instanceof long[]
		int boxed = a.label();
		ClassConstant longs = cls("[J");
		a.aload(1);
		a.arraylength();
		a.iconst(6);
		a.branch(Opcode.IF_ICMPNE, boxed);
		a.aload(1);
		a.iconst(5);
		a.aaload();
		a.instanceOf(longs);
		a.branch(Opcode.IFEQ, boxed);
		int packedLoop = a.label();
		int packedDone = a.label();
		int nilElement = a.label();
		int stored = a.label();
		// 5 = the long[]
		a.aload(1);
		a.iconst(5);
		a.aaload();
		a.checkcast(longs);
		a.astore(5);
		a.aload(5);
		a.arraylength();
		a.anewarray(cls("java/lang/Object"));
		a.astore(3);
		a.iconst(0);
		a.istore(4);
		a.bind(packedLoop);
		a.iload(4);
		a.aload(5);
		a.arraylength();
		a.branch(Opcode.IF_ICMPGE, packedDone);
		a.aload(3);
		a.iload(4);
		a.aload(5);
		a.iload(4);
		a.laload();
		a.ldc2Long(this.cp.addLong(Long.MIN_VALUE));
		a.lcmp();
		a.branch(Opcode.IFEQ, nilElement);
		a.aload(5);
		a.iload(4);
		a.laload();
		a.invokestatic(method("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
		a.branch(Opcode.GOTO, stored);
		a.bind(nilElement);
		a.aconstNull();
		a.bind(stored);
		a.aastore();
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, packedLoop);
		a.bind(packedDone);
		a.aload(3);
		a.areturn();
		// the boxed shape: header[1] a Long fill pointer, else every element
		a.bind(boxed);
		int noFill = a.label();
		int sized = a.label();
		a.aload(1);
		a.iconst(1);
		a.aaload();
		a.instanceOf(cls("java/lang/Long"));
		a.branch(Opcode.IFEQ, noFill);
		a.aload(1);
		a.iconst(1);
		a.aaload();
		a.checkcast(cls("java/lang/Long"));
		a.invokevirtual(method("java/lang/Long", "intValue", "()I"));
		a.istore(2);
		a.branch(Opcode.GOTO, sized);
		a.bind(noFill);
		a.aload(0);
		a.checkcast(arrayList);
		a.invokevirtual(method("java/util/ArrayList", "size", "()I"));
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(2);
		a.bind(sized);
		int boxedLoop = a.label();
		int boxedDone = a.label();
		a.iload(2);
		a.anewarray(cls("java/lang/Object"));
		a.astore(3);
		a.iconst(0);
		a.istore(4);
		a.bind(boxedLoop);
		a.iload(4);
		a.iload(2);
		a.branch(Opcode.IF_ICMPGE, boxedDone);
		a.aload(3);
		a.iload(4);
		a.aload(0);
		a.checkcast(arrayList);
		a.iload(4);
		a.iconst(1);
		a.iadd();
		a.invokevirtual(method("java/util/ArrayList", "get", "(I)Ljava/lang/Object;"));
		a.aastore();
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, boxedLoop);
		a.bind(boxedDone);
		a.aload(3);
		a.areturn();
		a.bind(notArray);
		a.aconstNull();
		a.areturn();
		return new JvmJavaDirectSites.Method(name, desc, 8, 6, a.finish(), List.of());
	}

	// Falls through when local 1 is a cons cell: an exact Object[] of length 2 whose car
	// is no Integer (a function value's head); else jumps to improper.
	private void cellTest(JvmAsm a, ClassConstant objects, MethodrefConstant getClass, int improper) {
		a.aload(1);
		a.invokevirtual(getClass);
		a.ldcClass(objects);
		a.branch(Opcode.IF_ACMPNE, improper);
		a.aload(1);
		a.checkcast(objects);
		a.arraylength();
		a.iconst(2);
		a.branch(Opcode.IF_ICMPNE, improper);
		a.aload(1);
		a.checkcast(objects);
		a.iconst(0);
		a.aaload();
		a.instanceOf(cls("java/lang/Integer"));
		a.branch(Opcode.IFNE, improper);
	}

}
