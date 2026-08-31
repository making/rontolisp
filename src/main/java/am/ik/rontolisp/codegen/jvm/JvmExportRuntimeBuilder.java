package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.rontolisp.compiler.JvmExportDirective;

/**
 * Builds the typed, Java-callable wrapper methods a {@code rontolisp:jvm-export}
 * directive declares, plus the small marshalling helpers they share — the JVM twin of
 * {@code codegen.wasm.WasmExportCompiler}.
 *
 * <p>
 * Each wrapper is a {@code public static} method under the directive's Java name whose
 * signature is derived from the declared {@link BoundaryType}s: it converts each argument
 * into the internal representation (a boxed {@code Long}/{@code Double}, {@code null} /
 * {@code "T"} for booleans, the quote-framed {@code String} a Lisp string is stored as,
 * the {@code long[]}-with-width-header packed octet vector), calls the untyped
 * {@code (Object...)Object} defun method, and converts the result back. Without the
 * wrapper a Java caller can only reach the untyped method, whose argument and result
 * representations no Java code can safely construct ({@code .kb/jvm-export.md}).
 *
 * <p>
 * The conversion rule is {@code wasm-export}'s, verbatim: <strong>the boundary carries
 * the value exactly, or it throws</strong>. An unsigned argument outside its declared
 * range throws {@code IllegalArgumentException}; a result the declared type cannot state
 * throws {@code ArithmeticException}; a result of the wrong representation throws
 * {@code ClassCastException}. Nothing is masked or wrapped.
 *
 * <p>
 * The Java parameter/return types per designator:
 * <ul>
 * <li>{@code :s8} / {@code :s16} / {@code :s32} / {@code :s64} —
 * {@code byte}/{@code short}/{@code int}/{@code long} (the ranges coincide, so no guard
 * is needed on the way in)</li>
 * <li>{@code :u8} / {@code :u16} — {@code int}, {@code :u32} / {@code :u64} —
 * {@code long}: the smallest conventional Java carrier that states the whole declared
 * range ({@code :u64}'s values at or above 2^63 have no exact representation in the
 * signed 64-bit integers the backend computes with and throw, exactly as they trap on the
 * WASM boundary)</li>
 * <li>{@code :float} — {@code double}; {@code :bool} — {@code boolean}</li>
 * <li>{@code :string} — {@code String} (the wrapper adds/strips the frame quotes the
 * stored representation carries)</li>
 * <li>{@code :s-expr} — {@code String} (read through the embedded reader on the way in,
 * printed on the way out)</li>
 * <li>{@code :bytes} — {@code byte[]} (copied to/from the packed
 * {@code (unsigned-byte 8)} vector)</li>
 * </ul>
 */
final class JvmExportRuntimeBuilder {

	/** An emitted method: name, descriptor, frame sizes, code, and its access level. */
	record BuiltMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code,
			boolean isPublic) {
	}

	private static final String ARG_GUARD = "_exArg";

	private static final String ARG_GUARD_DESC = "(JJJLjava/lang/String;)J";

	private static final String RESULT_GUARD = "_exRes";

	private static final String RESULT_GUARD_DESC = "(Ljava/lang/Object;JJLjava/lang/String;)J";

	private static final String UNFRAME = "_exStr";

	private static final String UNFRAME_DESC = "(Ljava/lang/Object;)Ljava/lang/String;";

	private static final String BYTES_IN = "_exBytesIn";

	private static final String BYTES_IN_DESC = "([B)[J";

	private static final String BYTES_OUT = "_exBytesOut";

	private static final String BYTES_OUT_DESC = "(Ljava/lang/Object;)[B";

	/**
	 * The internal name of the packed float-array handle a compiled library hands out.
	 */
	static final String HANDLE_CLASS = "am/ik/rontolisp/runtime/RontoFloatArray";

	private static final String HANDLE_DESC = "L" + HANDLE_CLASS + ";";

	/** The internal name of the marshalling seam the wrappers call for that handle. */
	static final String BOUNDARY_CLASS = "am/ik/rontolisp/runtime/RontoBoundary";

	private static final String ARRAY_ARG = "floatArrayArgument";

	private static final String ARRAY_ARG_DESC = "(" + HANDLE_DESC + "ILjava/lang/Class;Ljava/lang/String;)"
			+ "Ljava/lang/Object;";

	private static final String ARRAY_RESULT = "floatArrayResult";

	private static final String ARRAY_RESULT_DESC = "(Ljava/lang/Object;ILjava/lang/Class;Ljava/lang/String;)"
			+ HANDLE_DESC;

	private JvmExportRuntimeBuilder() {
	}

	/**
	 * Returns whether any declaration reads an {@code :s-expr} parameter, which the
	 * wrapper parses through the embedded reader ({@code _readFromString}) — the caller
	 * must force the reader runtime on when this answers {@code true}.
	 */
	static boolean needsReader(List<JvmExportDirective> decls) {
		return decls.stream().anyMatch(d -> d.paramTypes().contains(BoundaryType.S_EXPR));
	}

	/**
	 * Returns whether any declaration carries a packed float array across the boundary.
	 * The caller must then force the packed float-array runtime on (a declared handle is
	 * the only thing a library needs to reach {@code aref}/{@code length} over one) and
	 * make the handle's class files travel with the compiled output.
	 * @param decls the parsed directives
	 * @return {@code true} when a {@code :float-vector} / {@code :float-matrix} appears
	 */
	static boolean needsFloatArray(List<JvmExportDirective> decls) {
		return decls.stream()
			.anyMatch(d -> d.returnType().jvmOnly() || d.paramTypes().stream().anyMatch(BoundaryType::jvmOnly));
	}

	/**
	 * The class files of {@code am.ik.rontolisp.runtime} that travel BESIDE a compiled
	 * library that hands out a packed float-array handle. How they travel, and why at
	 * their canonical names: {@link JvmRuntimeClassFiles}.
	 */
	static final List<String> RUNTIME_CLASS_FILES = List.of("am/ik/rontolisp/runtime/RontoBoundary.class",
			"am/ik/rontolisp/runtime/RontoFloatArray.class", "am/ik/rontolisp/runtime/RontoFloatArray$Width.class");

	/**
	 * Reads {@link #RUNTIME_CLASS_FILES} off the compiler's own classpath.
	 * @return each class file's path within an output tree (or jar), mapped to its bytes
	 */
	static Map<String, byte[]> runtimeClassFiles() {
		return JvmRuntimeClassFiles.read(RUNTIME_CLASS_FILES);
	}

	/** The rank a packed float-array designator declares. */
	private static int declaredRank(BoundaryType type) {
		return type == BoundaryType.FLOAT_MATRIX ? 2 : 1;
	}

	/**
	 * The JVM field descriptor of a boundary type ({@code "V"} for
	 * {@link BoundaryType#VOID}).
	 */
	static String javaDesc(BoundaryType type) {
		return switch (type) {
			case S8 -> "B";
			case S16 -> "S";
			case S32 -> "I";
			case S64 -> "J";
			case U8, U16 -> "I";
			case U32, U64 -> "J";
			case FLOAT -> "D";
			case BOOL -> "Z";
			case STRING, S_EXPR -> "Ljava/lang/String;";
			case BYTES -> "[B";
			case FLOAT_VECTOR, FLOAT_MATRIX -> HANDLE_DESC;
			case VOID -> "V";
		};
	}

	/** The method descriptor of a declaration's typed wrapper. */
	static String methodDesc(JvmExportDirective decl) {
		StringBuilder desc = new StringBuilder("(");
		for (BoundaryType t : decl.paramTypes()) {
			desc.append(javaDesc(t));
		}
		return desc.append(')').append(javaDesc(decl.returnType())).toString();
	}

	/**
	 * Builds the wrapper method of every declaration plus the shared helpers the wrappers
	 * reference.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param decls the parsed, validated directives
	 * @param functions the defun name-to-method map (each declaration's target is already
	 * validated to exist with the declared arity)
	 * @return the methods to add to the class, wrappers first
	 */
	static List<BuiltMethod> build(ConstantPool cp, ClassConstant thisClass, List<JvmExportDirective> decls,
			Map<String, JvmLispCompiler.FunctionInfo> functions) {
		return build(cp, thisClass, decls, functions, false);
	}

	/**
	 * {@link #build(ConstantPool, ClassConstant, List, Map)} with the array-runtime flag:
	 * when the array runtime exists, a {@code :string}-returning export can answer a
	 * MUTABLE character vector (a concatenate/subseq/format result), and the
	 * {@code _exStr} unframe renders it through {@code _strv} before its frame check.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param decls the export directives
	 * @param functions the compiled function table
	 * @param arrayRuntime whether the {@code _strv} normalizer is emitted
	 * @return the export bridge methods
	 */
	static List<BuiltMethod> build(ConstantPool cp, ClassConstant thisClass, List<JvmExportDirective> decls,
			Map<String, JvmLispCompiler.FunctionInfo> functions, boolean arrayRuntime) {
		List<BuiltMethod> methods = new ArrayList<>();
		Refs refs = new Refs(cp, thisClass, needsFloatArray(decls));
		refs.strvRef = arrayRuntime ? cp.addMethodref(thisClass, cp
			.addNameAndType(cp.addUtf8(JvmArrayRuntimeBuilder.STRV), cp.addUtf8(JvmArrayRuntimeBuilder.STRV_DESC)))
				: null;
		boolean needArgGuard = false;
		boolean needResultGuard = false;
		boolean needUnframe = false;
		boolean needBytesIn = false;
		boolean needBytesOut = false;
		for (JvmExportDirective decl : decls) {
			MethodrefConstant target = java.util.Objects.requireNonNull(functions.get(decl.name())).methodref();
			methods.add(buildWrapper(cp, decl, target, refs));
			for (BoundaryType t : decl.paramTypes()) {
				needArgGuard |= t == BoundaryType.U8 || t == BoundaryType.U16 || t == BoundaryType.U32
						|| t == BoundaryType.U64;
				needBytesIn |= t == BoundaryType.BYTES;
			}
			needResultGuard |= decl.returnType().isInteger();
			needUnframe |= decl.returnType() == BoundaryType.STRING;
			needBytesOut |= decl.returnType() == BoundaryType.BYTES;
		}
		if (needArgGuard) {
			methods.add(buildArgGuard(cp, refs));
		}
		if (needResultGuard) {
			methods.add(buildResultGuard(cp, refs));
		}
		if (needUnframe) {
			methods.add(buildUnframe(cp, refs));
		}
		if (needBytesIn) {
			methods.add(buildBytesIn(cp));
		}
		if (needBytesOut) {
			methods.add(buildBytesOut(cp, refs));
		}
		return methods;
	}

	/** The constant-pool references every builder below shares. */
	private static final class Refs {

		final MethodrefConstant longValueOf;

		final MethodrefConstant longValue;

		final MethodrefConstant doubleValueOf;

		final MethodrefConstant numberDoubleValue;

		final MethodrefConstant concat;

		final MethodrefConstant valueOfLong;

		final MethodrefConstant charAt;

		final MethodrefConstant length;

		final MethodrefConstant substring;

		final MethodrefConstant lispToString;

		final MethodrefConstant readFromString;

		final MethodrefConstant argGuard;

		final MethodrefConstant resultGuard;

		/**
		 * {@code _strv}, or null without the array runtime: the {@code _exStr} unframe
		 * renders a mutable character vector before its frame check, so a
		 * concatenate/subseq/format-built export result crosses the handle boundary as
		 * the string it spells.
		 */
		@Nullable MethodrefConstant strvRef;

		final MethodrefConstant unframe;

		final MethodrefConstant bytesIn;

		final MethodrefConstant bytesOut;

		final ClassConstant longClass;

		final ClassConstant stringClass;

		final ClassConstant longArrayClass;

		final ClassConstant thisClassConstant;

		final @Nullable MethodrefConstant floatArrayArgument;

		final @Nullable MethodrefConstant floatArrayResult;

		Refs(ConstantPool cp, ClassConstant thisClass, boolean floatArray) {
			this.thisClassConstant = thisClass;
			if (floatArray) {
				ClassConstant boundary = cp.addClass(cp.addUtf8(BOUNDARY_CLASS));
				this.floatArrayArgument = cp.addMethodref(boundary,
						cp.addNameAndType(cp.addUtf8(ARRAY_ARG), cp.addUtf8(ARRAY_ARG_DESC)));
				this.floatArrayResult = cp.addMethodref(boundary,
						cp.addNameAndType(cp.addUtf8(ARRAY_RESULT), cp.addUtf8(ARRAY_RESULT_DESC)));
			}
			else {
				this.floatArrayArgument = null;
				this.floatArrayResult = null;
			}
			this.longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
			this.stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
			this.longArrayClass = cp.addClass(cp.addUtf8("[J"));
			ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
			ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
			this.longValueOf = cp.addMethodref(this.longClass,
					cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
			this.longValue = cp.addMethodref(this.longClass,
					cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
			this.doubleValueOf = cp.addMethodref(doubleClass,
					cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;")));
			this.numberDoubleValue = cp.addMethodref(numberClass,
					cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));
			this.concat = cp.addMethodref(this.stringClass,
					cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
			this.valueOfLong = cp.addMethodref(this.stringClass,
					cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/String;")));
			this.charAt = cp.addMethodref(this.stringClass,
					cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C")));
			this.length = cp.addMethodref(this.stringClass, cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
			this.substring = cp.addMethodref(this.stringClass,
					cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(II)Ljava/lang/String;")));
			Utf8Constant unaryToString = cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;");
			this.lispToString = cp.addMethodref(thisClass,
					cp.addNameAndType(cp.addUtf8("_lispToString"), unaryToString));
			this.readFromString = cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8("_readFromString"),
					cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
			this.argGuard = cp.addMethodref(thisClass,
					cp.addNameAndType(cp.addUtf8(ARG_GUARD), cp.addUtf8(ARG_GUARD_DESC)));
			this.resultGuard = cp.addMethodref(thisClass,
					cp.addNameAndType(cp.addUtf8(RESULT_GUARD), cp.addUtf8(RESULT_GUARD_DESC)));
			this.unframe = cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(UNFRAME), cp.addUtf8(UNFRAME_DESC)));
			this.bytesIn = cp.addMethodref(thisClass,
					cp.addNameAndType(cp.addUtf8(BYTES_IN), cp.addUtf8(BYTES_IN_DESC)));
			this.bytesOut = cp.addMethodref(thisClass,
					cp.addNameAndType(cp.addUtf8(BYTES_OUT), cp.addUtf8(BYTES_OUT_DESC)));
		}

	}

	private static BuiltMethod buildWrapper(ConstantPool cp, JvmExportDirective decl, MethodrefConstant target,
			Refs refs) {
		JvmAsm asm = new JvmAsm();
		int slot = 0;
		List<BoundaryType> params = decl.paramTypes();
		for (int i = 0; i < params.size(); i++) {
			BoundaryType t = params.get(i);
			switch (t) {
				case S8, S16, S32 -> {
					emitLoad(asm, Opcode.ILOAD, slot);
					asm.code.add(Opcode.I2L);
					invoke(asm, Opcode.INVOKESTATIC, refs.longValueOf);
					slot += 1;
				}
				case S64 -> {
					emitLoad(asm, Opcode.LLOAD, slot);
					invoke(asm, Opcode.INVOKESTATIC, refs.longValueOf);
					slot += 2;
				}
				case U8, U16 -> {
					emitLoad(asm, Opcode.ILOAD, slot);
					asm.code.add(Opcode.I2L);
					emitArgGuard(asm, cp, refs, 0L, t == BoundaryType.U8 ? 255L : 65535L, decl, i);
					invoke(asm, Opcode.INVOKESTATIC, refs.longValueOf);
					slot += 1;
				}
				case U32, U64 -> {
					emitLoad(asm, Opcode.LLOAD, slot);
					emitArgGuard(asm, cp, refs, 0L, t == BoundaryType.U32 ? 4294967295L : Long.MAX_VALUE, decl, i);
					invoke(asm, Opcode.INVOKESTATIC, refs.longValueOf);
					slot += 2;
				}
				case FLOAT -> {
					emitLoad(asm, Opcode.DLOAD, slot);
					invoke(asm, Opcode.INVOKESTATIC, refs.doubleValueOf);
					slot += 2;
				}
				case BOOL -> {
					emitLoad(asm, Opcode.ILOAD, slot);
					int elseLabel = asm.label();
					int endLabel = asm.label();
					asm.branch(Opcode.IFEQ, elseLabel);
					emitLdcString(asm, cp, "T");
					asm.branch(Opcode.GOTO, endLabel);
					asm.bind(elseLabel);
					asm.code.add(Opcode.ACONST_NULL);
					asm.bind(endLabel);
					slot += 1;
				}
				case STRING -> {
					emitFrame(asm, cp, refs, slot);
					slot += 1;
				}
				case S_EXPR -> {
					emitFrame(asm, cp, refs, slot);
					invoke(asm, Opcode.INVOKESTATIC, refs.readFromString);
					slot += 1;
				}
				case BYTES -> {
					emitLoad(asm, Opcode.ALOAD, slot);
					invoke(asm, Opcode.INVOKESTATIC, refs.bytesIn);
					slot += 1;
				}
				case FLOAT_VECTOR, FLOAT_MATRIX -> {
					// The handle hands over the packed array it already holds -- no copy,
					// which is this boundary type's whole point (.kb/jvm-export.md).
					emitLoad(asm, Opcode.ALOAD, slot);
					emitIntConst(asm, declaredRank(t));
					emitLdcClass(asm, refs.thisClassConstant);
					emitLdcString(asm, cp, "rontolisp:jvm-export " + decl.methodName() + " argument " + (i + 1) + " ("
							+ t.designator().toLowerCase(Locale.ROOT) + ") ");
					invoke(asm, Opcode.INVOKESTATIC, java.util.Objects.requireNonNull(refs.floatArrayArgument));
					slot += 1;
				}
				case VOID -> throw new IllegalStateException(":void parameter survived parsing: " + decl);
			}
		}
		invoke(asm, Opcode.INVOKESTATIC, target);
		BoundaryType ret = decl.returnType();
		switch (ret) {
			case VOID -> {
				asm.code.add(Opcode.POP);
				asm.code.add(Opcode.RETURN);
			}
			case S8, S16, S32, U8, U16 -> {
				BoundaryType.Range range = java.util.Objects.requireNonNull(ret.range());
				emitResultGuard(asm, cp, refs, range.min().longValueExact(), range.max().longValueExact(), decl);
				asm.code.add(Opcode.L2I);
				asm.code.add(Opcode.IRETURN);
			}
			case S64 -> {
				emitResultGuard(asm, cp, refs, Long.MIN_VALUE, Long.MAX_VALUE, decl);
				asm.code.add(Opcode.LRETURN);
			}
			case U32 -> {
				emitResultGuard(asm, cp, refs, 0L, 4294967295L, decl);
				asm.code.add(Opcode.LRETURN);
			}
			case U64 -> {
				// Values at or above 2^63 do not exist in the signed 64-bit house
				// representation, so [0, Long.MAX_VALUE] is the exactly-representable
				// span of the declared type.
				emitResultGuard(asm, cp, refs, 0L, Long.MAX_VALUE, decl);
				asm.code.add(Opcode.LRETURN);
			}
			case FLOAT -> {
				asm.code.add(Opcode.CHECKCAST);
				JvmRuntimeBuilder.emitU2(asm.code, cp.addClass(cp.addUtf8("java/lang/Number")).index());
				invoke(asm, Opcode.INVOKEVIRTUAL, refs.numberDoubleValue);
				asm.code.add(Opcode.DRETURN);
			}
			case BOOL -> {
				int trueLabel = asm.label();
				asm.branch(Opcode.IFNONNULL, trueLabel);
				asm.code.add(Opcode.ICONST_0);
				asm.code.add(Opcode.IRETURN);
				asm.bind(trueLabel);
				asm.code.add(Opcode.ICONST_1);
				asm.code.add(Opcode.IRETURN);
			}
			case STRING -> {
				invoke(asm, Opcode.INVOKESTATIC, refs.unframe);
				asm.code.add(Opcode.ARETURN);
			}
			case S_EXPR -> {
				invoke(asm, Opcode.INVOKESTATIC, refs.lispToString);
				asm.code.add(Opcode.ARETURN);
			}
			case BYTES -> {
				invoke(asm, Opcode.INVOKESTATIC, refs.bytesOut);
				asm.code.add(Opcode.ARETURN);
			}
			case FLOAT_VECTOR, FLOAT_MATRIX -> {
				// The handle ALIASES the array the function answered: no copy, and under
				// --gpu no materialization until the caller actually reads an element.
				emitIntConst(asm, declaredRank(ret));
				emitLdcClass(asm, refs.thisClassConstant);
				emitLdcString(asm, cp, "rontolisp:jvm-export " + decl.methodName() + " result ("
						+ ret.designator().toLowerCase(Locale.ROOT) + ") ");
				invoke(asm, Opcode.INVOKESTATIC, java.util.Objects.requireNonNull(refs.floatArrayResult));
				asm.code.add(Opcode.ARETURN);
			}
		}
		return new BuiltMethod(cp.addUtf8(decl.methodName()), cp.addUtf8(methodDesc(decl)), params.size() + 8,
				Math.max(1, slot), asm.code, true);
	}

	// "…".concat(arg).concat("…"): a Lisp string stores its frame quotes
	// (.kb/core-representation.md), so the incoming Java String gains them here — this
	// is what keeps GREET("ron") from reading the r and n as the frame.
	private static void emitFrame(JvmAsm asm, ConstantPool cp, Refs refs, int slot) {
		emitLdcString(asm, cp, "\"");
		emitLoad(asm, Opcode.ALOAD, slot);
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.concat);
		emitLdcString(asm, cp, "\"");
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.concat);
	}

	private static void emitArgGuard(JvmAsm asm, ConstantPool cp, Refs refs, long min, long max,
			JvmExportDirective decl, int paramIndex) {
		emitLdc2(asm, cp, min);
		emitLdc2(asm, cp, max);
		emitLdcString(asm, cp,
				"rontolisp:jvm-export " + decl.methodName() + " argument " + (paramIndex + 1) + " ("
						+ decl.paramTypes().get(paramIndex).designator().toLowerCase(Locale.ROOT)
						+ ") cannot carry the value exactly: ");
		invoke(asm, Opcode.INVOKESTATIC, refs.argGuard);
	}

	private static void emitResultGuard(JvmAsm asm, ConstantPool cp, Refs refs, long min, long max,
			JvmExportDirective decl) {
		emitLdc2(asm, cp, min);
		emitLdc2(asm, cp, max);
		emitLdcString(asm, cp, "rontolisp:jvm-export " + decl.methodName() + " result ("
				+ decl.returnType().designator().toLowerCase(Locale.ROOT) + ") cannot carry the value exactly: ");
		invoke(asm, Opcode.INVOKESTATIC, refs.resultGuard);
	}

	// _exArg(v, min, max, label): v when min <= v <= max, else
	// IllegalArgumentException(label + v).
	private static BuiltMethod buildArgGuard(ConstantPool cp, Refs refs) {
		JvmAsm asm = new JvmAsm();
		int throwLabel = asm.label();
		asm.code.add(Opcode.LLOAD_0);
		asm.code.add(Opcode.LLOAD_2);
		asm.code.add(Opcode.LCMP);
		asm.branch(Opcode.IFLT, throwLabel);
		asm.code.add(Opcode.LLOAD_0);
		emitLoad(asm, Opcode.LLOAD, 4);
		asm.code.add(Opcode.LCMP);
		asm.branch(Opcode.IFGT, throwLabel);
		asm.code.add(Opcode.LLOAD_0);
		asm.code.add(Opcode.LRETURN);
		asm.bind(throwLabel);
		ClassConstant iae = cp.addClass(cp.addUtf8("java/lang/IllegalArgumentException"));
		MethodrefConstant iaeInit = cp.addMethodref(iae,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		asm.code.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(asm.code, iae.index());
		asm.code.add(Opcode.DUP);
		emitLoad(asm, Opcode.ALOAD, 6);
		asm.code.add(Opcode.LLOAD_0);
		invoke(asm, Opcode.INVOKESTATIC, refs.valueOfLong);
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.concat);
		invoke(asm, Opcode.INVOKESPECIAL, iaeInit);
		asm.code.add(Opcode.ATHROW);
		return new BuiltMethod(cp.addUtf8(ARG_GUARD), cp.addUtf8(ARG_GUARD_DESC), 6, 7, asm.code, false);
	}

	// _exRes(value, min, max, label): the value's long when it is a Long within
	// [min, max]; ArithmeticException(label + v) when out of range,
	// ClassCastException(label + printed value) when not an integer at all (a
	// BigInteger is out of every declared range that fits a Java long, so it takes
	// the range path's message shape through the type path).
	private static BuiltMethod buildResultGuard(ConstantPool cp, Refs refs) {
		JvmAsm asm = new JvmAsm();
		int typeThrow = asm.label();
		int rangeThrow = asm.label();
		asm.code.add(Opcode.ALOAD_0);
		asm.code.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(asm.code, refs.longClass.index());
		asm.branch(Opcode.IFEQ, typeThrow);
		asm.code.add(Opcode.ALOAD_0);
		asm.code.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(asm.code, refs.longClass.index());
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.longValue);
		emitStore(asm, Opcode.LSTORE, 6);
		emitLoad(asm, Opcode.LLOAD, 6);
		asm.code.add(Opcode.LLOAD_1);
		asm.code.add(Opcode.LCMP);
		asm.branch(Opcode.IFLT, rangeThrow);
		emitLoad(asm, Opcode.LLOAD, 6);
		asm.code.add(Opcode.LLOAD_3);
		asm.code.add(Opcode.LCMP);
		asm.branch(Opcode.IFGT, rangeThrow);
		emitLoad(asm, Opcode.LLOAD, 6);
		asm.code.add(Opcode.LRETURN);
		asm.bind(rangeThrow);
		ClassConstant arith = cp.addClass(cp.addUtf8("java/lang/ArithmeticException"));
		MethodrefConstant arithInit = cp.addMethodref(arith,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		asm.code.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(asm.code, arith.index());
		asm.code.add(Opcode.DUP);
		emitLoad(asm, Opcode.ALOAD, 5);
		emitLoad(asm, Opcode.LLOAD, 6);
		invoke(asm, Opcode.INVOKESTATIC, refs.valueOfLong);
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.concat);
		invoke(asm, Opcode.INVOKESPECIAL, arithInit);
		asm.code.add(Opcode.ATHROW);
		asm.bind(typeThrow);
		ClassConstant cce = cp.addClass(cp.addUtf8("java/lang/ClassCastException"));
		MethodrefConstant cceInit = cp.addMethodref(cce,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		asm.code.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(asm.code, cce.index());
		asm.code.add(Opcode.DUP);
		emitLoad(asm, Opcode.ALOAD, 5);
		asm.code.add(Opcode.ALOAD_0);
		invoke(asm, Opcode.INVOKESTATIC, refs.lispToString);
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.concat);
		invoke(asm, Opcode.INVOKESPECIAL, cceInit);
		asm.code.add(Opcode.ATHROW);
		return new BuiltMethod(cp.addUtf8(RESULT_GUARD), cp.addUtf8(RESULT_GUARD_DESC), 6, 8, asm.code, false);
	}

	// _exStr(value): the content between the frame quotes when the value is a stored
	// Lisp string ("\"...\""), else ClassCastException. A bare (unframed) String is a
	// SYMBOL, not a string, and throws too — answering it verbatim would silently
	// conflate the two representations.
	private static BuiltMethod buildUnframe(ConstantPool cp, Refs refs) {
		JvmAsm asm = new JvmAsm();
		int throwLabel = asm.label();
		// A mutable character vector (a concatenate/subseq/format-built result) renders
		// to its quote-framed string first; everything else passes through unchanged.
		if (refs.strvRef != null) {
			asm.code.add(Opcode.ALOAD_0);
			asm.code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(asm.code, refs.strvRef.index());
			asm.code.add(Opcode.ASTORE_0);
		}
		asm.code.add(Opcode.ALOAD_0);
		asm.code.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(asm.code, refs.stringClass.index());
		asm.branch(Opcode.IFEQ, throwLabel);
		asm.code.add(Opcode.ALOAD_0);
		asm.code.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(asm.code, refs.stringClass.index());
		asm.code.add(Opcode.ASTORE_1);
		asm.code.add(Opcode.ALOAD_1);
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.length);
		asm.code.add(Opcode.ISTORE_2);
		asm.code.add(Opcode.ILOAD_2);
		asm.code.add(Opcode.ICONST_2);
		asm.branch(Opcode.IF_ICMPLT, throwLabel);
		asm.code.add(Opcode.ALOAD_1);
		asm.code.add(Opcode.ICONST_0);
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.charAt);
		asm.code.add(Opcode.BIPUSH);
		asm.code.add(34); // '"'
		asm.branch(Opcode.IF_ICMPNE, throwLabel);
		asm.code.add(Opcode.ALOAD_1);
		asm.code.add(Opcode.ICONST_1);
		asm.code.add(Opcode.ILOAD_2);
		asm.code.add(Opcode.ICONST_1);
		asm.code.add(Opcode.ISUB);
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.substring);
		asm.code.add(Opcode.ARETURN);
		asm.bind(throwLabel);
		emitThrowCce(asm, cp, refs, "rontolisp:jvm-export: the function did not return a string: ");
		return new BuiltMethod(cp.addUtf8(UNFRAME), cp.addUtf8(UNFRAME_DESC), 5, 3, asm.code, false);
	}

	// _exBytesIn(bytes): a fresh packed (unsigned-byte 8) vector — long[]{8, e0, ...},
	// the width-headered representation .kb/packed-integer-vectors.md pins — with each
	// byte widened unsigned.
	private static BuiltMethod buildBytesIn(ConstantPool cp) {
		JvmAsm asm = new JvmAsm();
		// n = bytes.length; r = new long[n + 1]; r[0] = 8;
		asm.code.add(Opcode.ALOAD_0);
		asm.code.add(Opcode.ARRAYLENGTH);
		asm.code.add(Opcode.ISTORE_1);
		asm.code.add(Opcode.ILOAD_1);
		asm.code.add(Opcode.ICONST_1);
		asm.code.add(Opcode.IADD);
		asm.code.add(Opcode.NEWARRAY);
		asm.code.add(11); // T_LONG
		asm.code.add(Opcode.ASTORE_2);
		asm.code.add(Opcode.ALOAD_2);
		asm.code.add(Opcode.ICONST_0);
		asm.code.add(Opcode.LDC2_W);
		JvmRuntimeBuilder.emitU2(asm.code, cp.addLong(8L).index());
		asm.code.add(Opcode.LASTORE);
		// for (i = 0; i < n; i++) r[i + 1] = bytes[i] & 0xFF;
		asm.code.add(Opcode.ICONST_0);
		asm.code.add(Opcode.ISTORE_3);
		int loop = asm.label();
		int end = asm.label();
		asm.bind(loop);
		asm.code.add(Opcode.ILOAD_3);
		asm.code.add(Opcode.ILOAD_1);
		asm.branch(Opcode.IF_ICMPGE, end);
		asm.code.add(Opcode.ALOAD_2);
		asm.code.add(Opcode.ILOAD_3);
		asm.code.add(Opcode.ICONST_1);
		asm.code.add(Opcode.IADD);
		asm.code.add(Opcode.ALOAD_0);
		asm.code.add(Opcode.ILOAD_3);
		asm.code.add(Opcode.BALOAD);
		asm.code.add(Opcode.SIPUSH);
		JvmRuntimeBuilder.emitU2(asm.code, 255);
		asm.code.add(Opcode.IAND);
		asm.code.add(Opcode.I2L);
		asm.code.add(Opcode.LASTORE);
		asm.code.add(Opcode.IINC);
		asm.code.add(3);
		asm.code.add(1);
		asm.branch(Opcode.GOTO, loop);
		asm.bind(end);
		asm.code.add(Opcode.ALOAD_2);
		asm.code.add(Opcode.ARETURN);
		return new BuiltMethod(cp.addUtf8(BYTES_IN), cp.addUtf8(BYTES_IN_DESC), 5, 4, asm.code, false);
	}

	// _exBytesOut(value): the byte[] copy of a packed (unsigned-byte 8) vector
	// (long[]{8, e0, ...}); any other value — including a packed vector of another
	// width — throws ClassCastException.
	private static BuiltMethod buildBytesOut(ConstantPool cp, Refs refs) {
		JvmAsm asm = new JvmAsm();
		int throwLabel = asm.label();
		asm.code.add(Opcode.ALOAD_0);
		asm.code.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(asm.code, refs.longArrayClass.index());
		asm.branch(Opcode.IFEQ, throwLabel);
		asm.code.add(Opcode.ALOAD_0);
		asm.code.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(asm.code, refs.longArrayClass.index());
		asm.code.add(Opcode.ASTORE_1);
		asm.code.add(Opcode.ALOAD_1);
		asm.code.add(Opcode.ARRAYLENGTH);
		asm.code.add(Opcode.ISTORE_2);
		asm.code.add(Opcode.ILOAD_2);
		asm.code.add(Opcode.ICONST_1);
		asm.branch(Opcode.IF_ICMPLT, throwLabel);
		asm.code.add(Opcode.ALOAD_1);
		asm.code.add(Opcode.ICONST_0);
		asm.code.add(Opcode.LALOAD);
		asm.code.add(Opcode.LDC2_W);
		JvmRuntimeBuilder.emitU2(asm.code, cp.addLong(8L).index());
		asm.code.add(Opcode.LCMP);
		asm.branch(Opcode.IFNE, throwLabel);
		// n = value.length - 1; out = new byte[n];
		asm.code.add(Opcode.ILOAD_2);
		asm.code.add(Opcode.ICONST_1);
		asm.code.add(Opcode.ISUB);
		asm.code.add(Opcode.ISTORE_3);
		asm.code.add(Opcode.ILOAD_3);
		asm.code.add(Opcode.NEWARRAY);
		asm.code.add(8); // T_BYTE
		emitStore(asm, Opcode.ASTORE, 4);
		asm.code.add(Opcode.ICONST_0);
		emitStore(asm, Opcode.ISTORE, 5);
		int loop = asm.label();
		int end = asm.label();
		asm.bind(loop);
		emitLoad(asm, Opcode.ILOAD, 5);
		asm.code.add(Opcode.ILOAD_3);
		asm.branch(Opcode.IF_ICMPGE, end);
		emitLoad(asm, Opcode.ALOAD, 4);
		emitLoad(asm, Opcode.ILOAD, 5);
		asm.code.add(Opcode.ALOAD_1);
		emitLoad(asm, Opcode.ILOAD, 5);
		asm.code.add(Opcode.ICONST_1);
		asm.code.add(Opcode.IADD);
		asm.code.add(Opcode.LALOAD);
		asm.code.add(Opcode.L2I);
		asm.code.add(Opcode.I2B);
		asm.code.add(Opcode.BASTORE);
		asm.code.add(Opcode.IINC);
		asm.code.add(5);
		asm.code.add(1);
		asm.branch(Opcode.GOTO, loop);
		asm.bind(end);
		emitLoad(asm, Opcode.ALOAD, 4);
		asm.code.add(Opcode.ARETURN);
		asm.bind(throwLabel);
		emitThrowCce(asm, cp, refs, "rontolisp:jvm-export: the function did not return an (unsigned-byte 8) vector: ");
		return new BuiltMethod(cp.addUtf8(BYTES_OUT), cp.addUtf8(BYTES_OUT_DESC), 6, 6, asm.code, false);
	}

	// new ClassCastException(prefix + _lispToString(value in slot 0)); throw
	private static void emitThrowCce(JvmAsm asm, ConstantPool cp, Refs refs, String prefix) {
		ClassConstant cce = cp.addClass(cp.addUtf8("java/lang/ClassCastException"));
		MethodrefConstant cceInit = cp.addMethodref(cce,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		asm.code.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(asm.code, cce.index());
		asm.code.add(Opcode.DUP);
		emitLdcString(asm, cp, prefix);
		asm.code.add(Opcode.ALOAD_0);
		invoke(asm, Opcode.INVOKESTATIC, refs.lispToString);
		invoke(asm, Opcode.INVOKEVIRTUAL, refs.concat);
		invoke(asm, Opcode.INVOKESPECIAL, cceInit);
		asm.code.add(Opcode.ATHROW);
	}

	private static void invoke(JvmAsm asm, int opcode, MethodrefConstant ref) {
		asm.code.add(opcode);
		JvmRuntimeBuilder.emitU2(asm.code, ref.index());
	}

	private static void emitLoad(JvmAsm asm, int opcode, int slot) {
		asm.code.add(opcode);
		asm.code.add(slot);
	}

	private static void emitStore(JvmAsm asm, int opcode, int slot) {
		asm.code.add(opcode);
		asm.code.add(slot);
	}

	private static void emitLdcString(JvmAsm asm, ConstantPool cp, String value) {
		ConstantPool.StringConstant sc = cp.addString(value);
		if (sc.index() <= 255) {
			asm.code.add(Opcode.LDC);
			asm.code.add(sc.index());
		}
		else {
			asm.code.add(Opcode.LDC_W);
			JvmRuntimeBuilder.emitU2(asm.code, sc.index());
		}
	}

	private static void emitLdcClass(JvmAsm asm, ClassConstant clazz) {
		if (clazz.index() <= 255) {
			asm.code.add(Opcode.LDC);
			asm.code.add(clazz.index());
		}
		else {
			asm.code.add(Opcode.LDC_W);
			JvmRuntimeBuilder.emitU2(asm.code, clazz.index());
		}
	}

	private static void emitIntConst(JvmAsm asm, int value) {
		asm.code.add(Opcode.BIPUSH);
		asm.code.add(value);
	}

	private static void emitLdc2(JvmAsm asm, ConstantPool cp, long value) {
		asm.code.add(Opcode.LDC2_W);
		JvmRuntimeBuilder.emitU2(asm.code, cp.addLong(value).index());
	}

}
