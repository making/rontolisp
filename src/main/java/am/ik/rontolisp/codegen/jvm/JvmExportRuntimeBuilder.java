package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
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

	/** An emitted method: name, descriptor, body, and its access level. */
	record BuiltMethod(Utf8Constant name, Utf8Constant desc, MethodCode code, boolean isPublic) {
	}

	private static final String ARG_GUARD = "_exArg";

	private static final String ARG_GUARD_DESC = "(JJJLjava/lang/String;)J";

	private static final String RESULT_GUARD = "_exRes";

	private static final String RESULT_GUARD_DESC = "(Ljava/lang/Object;JJLjava/lang/String;)J";

	private static final String UNFRAME = "_exStr";

	private static final String UNFRAME_DESC = "(Ljava/lang/Object;)Ljava/lang/String;";

	private static final String BYTES_IN = "_exBytesIn";

	private static final String BYTES_IN_DESC = "([B)[B";

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
			// Unreachable: rontolisp:jvm-export refuses the import-only type.
			case EXTERN -> throw new IllegalStateException("an import-only boundary type reached a JVM export");
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
	static List<BuiltMethod> build(ConstantPool cp, ClassEntry thisClass, List<JvmExportDirective> decls,
			Map<String, JvmLispCompiler.FunctionInfo> functions) {
		return build(cp, thisClass, decls, functions, false);
	}

	/**
	 * {@link #build(ConstantPool, ClassEntry, List, Map)} with the array-runtime flag:
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
	static List<BuiltMethod> build(ConstantPool cp, ClassEntry thisClass, List<JvmExportDirective> decls,
			Map<String, JvmLispCompiler.FunctionInfo> functions, boolean arrayRuntime) {
		List<BuiltMethod> methods = new ArrayList<>();
		Refs refs = new Refs(cp, thisClass, needsFloatArray(decls));
		refs.strvRef = arrayRuntime
				? cp.methodRef(thisClass, JvmArrayRuntimeBuilder.STRV, JvmArrayRuntimeBuilder.STRV_DESC) : null;
		boolean needArgGuard = false;
		boolean needResultGuard = false;
		boolean needUnframe = false;
		boolean needBytesIn = false;
		boolean needBytesOut = false;
		for (JvmExportDirective decl : decls) {
			MethodRefEntry target = java.util.Objects.requireNonNull(functions.get(decl.name()))
				.methodref()
				.methodRefEntry();
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

		final MethodRefEntry longValueOf;

		final MethodRefEntry longValue;

		final MethodRefEntry doubleValueOf;

		final MethodRefEntry numberDoubleValue;

		final MethodRefEntry concat;

		final MethodRefEntry valueOfLong;

		final MethodRefEntry charAt;

		final MethodRefEntry length;

		final MethodRefEntry substring;

		final MethodRefEntry lispToString;

		final MethodRefEntry readFromString;

		final MethodRefEntry argGuard;

		final MethodRefEntry resultGuard;

		/**
		 * {@code _strv}, or null without the array runtime: the {@code _exStr} unframe
		 * renders a mutable character vector before its frame check, so a
		 * concatenate/subseq/format-built export result crosses the handle boundary as
		 * the string it spells.
		 */
		@Nullable MethodRefEntry strvRef;

		final MethodRefEntry unframe;

		final MethodRefEntry bytesIn;

		final MethodRefEntry bytesOut;

		final ClassEntry longClass;

		final ClassEntry stringClass;

		final ClassEntry thisClassConstant;

		final @Nullable MethodRefEntry floatArrayArgument;

		final @Nullable MethodRefEntry floatArrayResult;

		Refs(ConstantPool cp, ClassEntry thisClass, boolean floatArray) {
			this.thisClassConstant = thisClass;
			if (floatArray) {
				ClassEntry boundary = cp.classEntry(BOUNDARY_CLASS);
				this.floatArrayArgument = cp.methodRef(boundary, ARRAY_ARG, ARRAY_ARG_DESC);
				this.floatArrayResult = cp.methodRef(boundary, ARRAY_RESULT, ARRAY_RESULT_DESC);
			}
			else {
				this.floatArrayArgument = null;
				this.floatArrayResult = null;
			}
			this.longClass = cp.classEntry("java/lang/Long");
			this.stringClass = cp.classEntry("java/lang/String");
			ClassEntry doubleClass = cp.classEntry("java/lang/Double");
			ClassEntry numberClass = cp.classEntry("java/lang/Number");
			this.longValueOf = cp.methodRef(this.longClass, "valueOf", "(J)Ljava/lang/Long;");
			this.longValue = cp.methodRef(this.longClass, "longValue", "()J");
			this.doubleValueOf = cp.methodRef(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
			this.numberDoubleValue = cp.methodRef(numberClass, "doubleValue", "()D");
			this.concat = cp.methodRef(this.stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
			this.valueOfLong = cp.methodRef(this.stringClass, "valueOf", "(J)Ljava/lang/String;");
			this.charAt = cp.methodRef(this.stringClass, "charAt", "(I)C");
			this.length = cp.methodRef(this.stringClass, "length", "()I");
			this.substring = cp.methodRef(this.stringClass, "substring", "(II)Ljava/lang/String;");
			this.lispToString = cp.methodRef(thisClass, "_lispToString", "(Ljava/lang/Object;)Ljava/lang/String;");
			this.readFromString = cp.methodRef(thisClass, "_readFromString", "(Ljava/lang/Object;)Ljava/lang/Object;");
			this.argGuard = cp.methodRef(thisClass, ARG_GUARD, ARG_GUARD_DESC);
			this.resultGuard = cp.methodRef(thisClass, RESULT_GUARD, RESULT_GUARD_DESC);
			this.unframe = cp.methodRef(thisClass, UNFRAME, UNFRAME_DESC);
			this.bytesIn = cp.methodRef(thisClass, BYTES_IN, BYTES_IN_DESC);
			this.bytesOut = cp.methodRef(thisClass, BYTES_OUT, BYTES_OUT_DESC);
		}

	}

	private static BuiltMethod buildWrapper(ConstantPool cp, JvmExportDirective decl, MethodRefEntry target,
			Refs refs) {
		MethodCode asm = new MethodCode();
		int slot = 0;
		List<BoundaryType> params = decl.paramTypes();
		for (int i = 0; i < params.size(); i++) {
			BoundaryType t = params.get(i);
			switch (t) {
				case S8, S16, S32 -> {
					asm.iload(slot);
					asm.i2l();
					asm.invokestatic(refs.longValueOf);
					slot += 1;
				}
				case S64 -> {
					asm.lload(slot);
					asm.invokestatic(refs.longValueOf);
					slot += 2;
				}
				case U8, U16 -> {
					asm.iload(slot);
					asm.i2l();
					emitArgGuard(asm, cp, refs, 0L, t == BoundaryType.U8 ? 255L : 65535L, decl, i);
					asm.invokestatic(refs.longValueOf);
					slot += 1;
				}
				case U32, U64 -> {
					asm.lload(slot);
					emitArgGuard(asm, cp, refs, 0L, t == BoundaryType.U32 ? 4294967295L : Long.MAX_VALUE, decl, i);
					asm.invokestatic(refs.longValueOf);
					slot += 2;
				}
				case FLOAT -> {
					asm.dload(slot);
					asm.invokestatic(refs.doubleValueOf);
					slot += 2;
				}
				case BOOL -> {
					asm.iload(slot);
					MethodCode.Label elseLabel = asm.newLabel();
					MethodCode.Label endLabel = asm.newLabel();
					asm.ifeq(elseLabel);
					asm.ldc(cp.stringEntry("T"));
					asm.goto_(endLabel);
					asm.labelBinding(elseLabel);
					asm.aconst_null();
					asm.labelBinding(endLabel);
					slot += 1;
				}
				case STRING -> {
					emitFrame(asm, cp, refs, slot);
					slot += 1;
				}
				case S_EXPR -> {
					emitFrame(asm, cp, refs, slot);
					asm.invokestatic(refs.readFromString);
					slot += 1;
				}
				case BYTES -> {
					asm.aload(slot);
					asm.invokestatic(refs.bytesIn);
					slot += 1;
				}
				case FLOAT_VECTOR, FLOAT_MATRIX -> {
					// The handle hands over the packed array it already holds -- no copy,
					// which is this boundary type's whole point (.kb/jvm-export.md).
					asm.aload(slot);
					asm.loadConstant(declaredRank(t));
					asm.ldc(refs.thisClassConstant);
					asm.ldc(cp.stringEntry("rontolisp:jvm-export " + decl.methodName() + " argument " + (i + 1) + " ("
							+ t.designator().toLowerCase(Locale.ROOT) + ") "));
					asm.invokestatic(java.util.Objects.requireNonNull(refs.floatArrayArgument));
					slot += 1;
				}
				case VOID -> throw new IllegalStateException(":void parameter survived parsing: " + decl);
			}
		}
		asm.invokestatic(target);
		BoundaryType ret = decl.returnType();
		switch (ret) {
			case VOID -> {
				asm.pop();
				asm.return_();
			}
			case S8, S16, S32, U8, U16 -> {
				BoundaryType.Range range = java.util.Objects.requireNonNull(ret.range());
				emitResultGuard(asm, cp, refs, range.min().longValueExact(), range.max().longValueExact(), decl);
				asm.l2i();
				asm.ireturn();
			}
			case S64 -> {
				emitResultGuard(asm, cp, refs, Long.MIN_VALUE, Long.MAX_VALUE, decl);
				asm.lreturn();
			}
			case U32 -> {
				emitResultGuard(asm, cp, refs, 0L, 4294967295L, decl);
				asm.lreturn();
			}
			case U64 -> {
				// Values at or above 2^63 do not exist in the signed 64-bit house
				// representation, so [0, Long.MAX_VALUE] is the exactly-representable
				// span of the declared type.
				emitResultGuard(asm, cp, refs, 0L, Long.MAX_VALUE, decl);
				asm.lreturn();
			}
			case FLOAT -> {
				asm.checkcast(cp.classEntry("java/lang/Number"));
				asm.invokevirtual(refs.numberDoubleValue);
				asm.dreturn();
			}
			case BOOL -> {
				MethodCode.Label trueLabel = asm.newLabel();
				asm.ifnonnull(trueLabel);
				asm.iconst_0();
				asm.ireturn();
				asm.labelBinding(trueLabel);
				asm.iconst_1();
				asm.ireturn();
			}
			case STRING -> {
				asm.invokestatic(refs.unframe);
				asm.areturn();
			}
			case S_EXPR -> {
				asm.invokestatic(refs.lispToString);
				asm.areturn();
			}
			case BYTES -> {
				asm.invokestatic(refs.bytesOut);
				asm.areturn();
			}
			case FLOAT_VECTOR, FLOAT_MATRIX -> {
				// The handle ALIASES the array the function answered: no copy, and under
				// --gpu no materialization until the caller actually reads an element.
				asm.loadConstant(declaredRank(ret));
				asm.ldc(refs.thisClassConstant);
				asm.ldc(cp.stringEntry("rontolisp:jvm-export " + decl.methodName() + " result ("
						+ ret.designator().toLowerCase(Locale.ROOT) + ") "));
				asm.invokestatic(java.util.Objects.requireNonNull(refs.floatArrayResult));
				asm.areturn();
			}
		}
		return new BuiltMethod(cp.addUtf8(decl.methodName()), cp.addUtf8(methodDesc(decl)), asm, true);
	}

	// "…".concat(arg).concat("…"): a Lisp string stores its frame quotes
	// (.kb/core-representation.md), so the incoming Java String gains them here — this
	// is what keeps GREET("ron") from reading the r and n as the frame.
	private static void emitFrame(MethodCode asm, ConstantPool cp, Refs refs, int slot) {
		asm.ldc(cp.stringEntry("\""));
		asm.aload(slot);
		asm.invokevirtual(refs.concat);
		asm.ldc(cp.stringEntry("\""));
		asm.invokevirtual(refs.concat);
	}

	private static void emitArgGuard(MethodCode asm, ConstantPool cp, Refs refs, long min, long max,
			JvmExportDirective decl, int paramIndex) {
		asm.ldc(cp.entries().longEntry(min));
		asm.ldc(cp.entries().longEntry(max));
		asm.ldc(cp.stringEntry("rontolisp:jvm-export " + decl.methodName() + " argument " + (paramIndex + 1) + " ("
				+ decl.paramTypes().get(paramIndex).designator().toLowerCase(Locale.ROOT)
				+ ") cannot carry the value exactly: "));
		asm.invokestatic(refs.argGuard);
	}

	private static void emitResultGuard(MethodCode asm, ConstantPool cp, Refs refs, long min, long max,
			JvmExportDirective decl) {
		asm.ldc(cp.entries().longEntry(min));
		asm.ldc(cp.entries().longEntry(max));
		asm.ldc(cp.stringEntry("rontolisp:jvm-export " + decl.methodName() + " result ("
				+ decl.returnType().designator().toLowerCase(Locale.ROOT) + ") cannot carry the value exactly: "));
		asm.invokestatic(refs.resultGuard);
	}

	// _exArg(v, min, max, label): v when min <= v <= max, else
	// IllegalArgumentException(label + v).
	private static BuiltMethod buildArgGuard(ConstantPool cp, Refs refs) {
		MethodCode asm = new MethodCode();
		MethodCode.Label throwLabel = asm.newLabel();
		asm.lload(0);
		asm.lload(2);
		asm.lcmp();
		asm.iflt(throwLabel);
		asm.lload(0);
		asm.lload(4);
		asm.lcmp();
		asm.ifgt(throwLabel);
		asm.lload(0);
		asm.lreturn();
		asm.labelBinding(throwLabel);
		ClassEntry iae = cp.classEntry("java/lang/IllegalArgumentException");
		MethodRefEntry iaeInit = cp.methodRef(iae, "<init>", "(Ljava/lang/String;)V");
		asm.new_(iae);
		asm.dup();
		asm.aload(6);
		asm.lload(0);
		asm.invokestatic(refs.valueOfLong);
		asm.invokevirtual(refs.concat);
		asm.invokespecial(iaeInit);
		asm.athrow();
		return new BuiltMethod(cp.addUtf8(ARG_GUARD), cp.addUtf8(ARG_GUARD_DESC), asm, false);
	}

	// _exRes(value, min, max, label): the value's long when it is a Long within
	// [min, max]; ArithmeticException(label + v) when out of range,
	// ClassCastException(label + printed value) when not an integer at all (a
	// BigInteger is out of every declared range that fits a Java long, so it takes
	// the range path's message shape through the type path).
	private static BuiltMethod buildResultGuard(ConstantPool cp, Refs refs) {
		MethodCode asm = new MethodCode();
		MethodCode.Label typeThrow = asm.newLabel();
		MethodCode.Label rangeThrow = asm.newLabel();
		asm.aload(0);
		asm.instanceOf(refs.longClass);
		asm.ifeq(typeThrow);
		asm.aload(0);
		asm.checkcast(refs.longClass);
		asm.invokevirtual(refs.longValue);
		asm.lstore(6);
		asm.lload(6);
		asm.lload(1);
		asm.lcmp();
		asm.iflt(rangeThrow);
		asm.lload(6);
		asm.lload(3);
		asm.lcmp();
		asm.ifgt(rangeThrow);
		asm.lload(6);
		asm.lreturn();
		asm.labelBinding(rangeThrow);
		ClassEntry arith = cp.classEntry("java/lang/ArithmeticException");
		MethodRefEntry arithInit = cp.methodRef(arith, "<init>", "(Ljava/lang/String;)V");
		asm.new_(arith);
		asm.dup();
		asm.aload(5);
		asm.lload(6);
		asm.invokestatic(refs.valueOfLong);
		asm.invokevirtual(refs.concat);
		asm.invokespecial(arithInit);
		asm.athrow();
		asm.labelBinding(typeThrow);
		ClassEntry cce = cp.classEntry("java/lang/ClassCastException");
		MethodRefEntry cceInit = cp.methodRef(cce, "<init>", "(Ljava/lang/String;)V");
		asm.new_(cce);
		asm.dup();
		asm.aload(5);
		asm.aload(0);
		asm.invokestatic(refs.lispToString);
		asm.invokevirtual(refs.concat);
		asm.invokespecial(cceInit);
		asm.athrow();
		return new BuiltMethod(cp.addUtf8(RESULT_GUARD), cp.addUtf8(RESULT_GUARD_DESC), asm, false);
	}

	// _exStr(value): the content between the frame quotes when the value is a stored
	// Lisp string ("\"...\""), else ClassCastException. A bare (unframed) String is a
	// SYMBOL, not a string, and throws too — answering it verbatim would silently
	// conflate the two representations.
	private static BuiltMethod buildUnframe(ConstantPool cp, Refs refs) {
		MethodCode asm = new MethodCode();
		MethodCode.Label throwLabel = asm.newLabel();
		// A mutable character vector (a concatenate/subseq/format-built result) renders
		// to its quote-framed string first; everything else passes through unchanged.
		if (refs.strvRef != null) {
			asm.aload(0);
			asm.invokestatic(refs.strvRef);
			asm.astore(0);
		}
		asm.aload(0);
		asm.instanceOf(refs.stringClass);
		asm.ifeq(throwLabel);
		asm.aload(0);
		asm.checkcast(refs.stringClass);
		asm.astore(1);
		asm.aload(1);
		asm.invokevirtual(refs.length);
		asm.istore(2);
		asm.iload(2);
		asm.iconst_2();
		asm.if_icmplt(throwLabel);
		asm.aload(1);
		asm.iconst_0();
		asm.invokevirtual(refs.charAt);
		asm.loadConstant(34);
		asm.if_icmpne(throwLabel);
		asm.aload(1);
		asm.iconst_1();
		asm.iload(2);
		asm.iconst_1();
		asm.isub();
		asm.invokevirtual(refs.substring);
		asm.areturn();
		asm.labelBinding(throwLabel);
		emitThrowCce(asm, cp, refs, "rontolisp:jvm-export: the function did not return a string: ");
		return new BuiltMethod(cp.addUtf8(UNFRAME), cp.addUtf8(UNFRAME_DESC), asm, false);
	}

	// _exBytesIn(bytes): a fresh packed (unsigned-byte 8) vector -- byte[]{8, e0, ...},
	// the width-headered representation .kb/packed-integer-vectors.md pins -- holding a
	// copy of the bytes.
	private static BuiltMethod buildBytesIn(ConstantPool cp) {
		MethodRefEntry arraycopy = cp.methodRef(cp.classEntry("java/lang/System"), "arraycopy",
				"(Ljava/lang/Object;ILjava/lang/Object;II)V");
		MethodCode asm = new MethodCode();
		// n = bytes.length; r = new byte[n + 1]; r[0] = 8;
		asm.aload(0);
		asm.arraylength();
		asm.istore(1);
		asm.iload(1);
		asm.iconst_1();
		asm.iadd();
		asm.newarray(TypeKind.BYTE);
		asm.astore(2);
		asm.aload(2);
		asm.iconst_0();
		asm.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
		asm.bastore();
		// System.arraycopy(bytes, 0, r, 1, n)
		asm.aload(0);
		asm.iconst_0();
		asm.aload(2);
		asm.iconst_1();
		asm.iload(1);
		asm.invokestatic(arraycopy);
		asm.aload(2);
		asm.areturn();
		return new BuiltMethod(cp.addUtf8(BYTES_IN), cp.addUtf8(BYTES_IN_DESC), asm, false);
	}

	// _exBytesOut(value): the byte[] copy of a packed (unsigned-byte 8) vector
	// (byte[]{8, e0, ...}); any other value -- a packed vector of another width, and a
	// quantized matrix, whose byte[] starts with its format code -- throws
	// ClassCastException.
	private static BuiltMethod buildBytesOut(ConstantPool cp, Refs refs) {
		ClassEntry byteArrayClass = cp.classEntry("[B");
		MethodRefEntry copyOfRange = cp.methodRef(cp.classEntry("java/util/Arrays"), "copyOfRange", "([BII)[B");
		MethodCode asm = new MethodCode();
		MethodCode.Label throwLabel = asm.newLabel();
		// if (value instanceof byte[] b && b[0] == 8) return Arrays.copyOfRange(b, 1,
		// b.length)
		asm.aload(0);
		asm.instanceOf(byteArrayClass);
		asm.ifeq(throwLabel);
		asm.aload(0);
		asm.checkcast(byteArrayClass);
		asm.iconst_0();
		asm.baload();
		asm.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
		asm.if_icmpne(throwLabel);
		asm.aload(0);
		asm.checkcast(byteArrayClass);
		asm.dup();
		asm.iconst_1();
		asm.swap();
		asm.arraylength();
		asm.invokestatic(copyOfRange);
		asm.areturn();
		asm.labelBinding(throwLabel);
		emitThrowCce(asm, cp, refs, "rontolisp:jvm-export: the function did not return an (unsigned-byte 8) vector: ");
		return new BuiltMethod(cp.addUtf8(BYTES_OUT), cp.addUtf8(BYTES_OUT_DESC), asm, false);
	}

	// new ClassCastException(prefix + _lispToString(value in slot 0)); throw
	private static void emitThrowCce(MethodCode asm, ConstantPool cp, Refs refs, String prefix) {
		ClassEntry cce = cp.classEntry("java/lang/ClassCastException");
		MethodRefEntry cceInit = cp.methodRef(cce, "<init>", "(Ljava/lang/String;)V");
		asm.new_(cce);
		asm.dup();
		asm.ldc(cp.stringEntry(prefix));
		asm.aload(0);
		asm.invokestatic(refs.lispToString);
		asm.invokevirtual(refs.concat);
		asm.invokespecial(cceInit);
		asm.athrow();
	}

}
