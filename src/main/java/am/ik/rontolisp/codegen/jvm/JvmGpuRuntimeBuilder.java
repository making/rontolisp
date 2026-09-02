package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the {@code --gpu} device bridge for the generated standalone {@code .class}: the
 * {@link JvmSimdRuntimeBuilder} / {@link JvmBlasRuntimeBuilder} mechanism, extended from
 * ONE embedded class to a CLOSURE of them plus one data resource.
 *
 * <h2>Why the whole library travels, instead of a flattened copy of it</h2>
 *
 * {@code --blas} embeds {@link JvmBlasTemplate}, a flat 375-line COPY of
 * {@code eval/LinalgBlasKernels} that the two must be kept in sync by hand. A GPU binding
 * is not 375 lines: {@code am.ik.gpu} is ~1700 across four classes, and the parts that
 * took the longest to get right are exactly the parts a copy would fork -- a decline that
 * must cost the device nothing (three calls, in one order), the 101-entry
 * {@code CUresult} table and which seventeen of its statuses are sticky, the per-device
 * safepoint threshold, the chunked critical copies. So the blob carries the library's own
 * class files instead, renamed by one prefix rule ({@code am/ik/gpu/} -> the emitted
 * program's own package plus {@value #GPU_PREFIX}), and the compiled backend runs the
 * very bytes the interpreter runs. {@link JvmGpuTemplate} rides along as the call site's
 * glue and is renamed the same way, which is what lets it be written against
 * {@code am.ik.gpu} and type-checked by javac.
 *
 * <p>
 * It costs no more than the mechanism it generalizes: the six class files come to 48 KB
 * (base64 65 KB) and the PTX to 85 KB, against the {@code --simd} template's 62 KB class
 * (~83 KB base64) that any {@code linalg} program under that flag already carries. Two
 * thirds of the PTX is the element-wise tier and half of THAT is {@code sin} /
 * {@code cos} / {@code tan}, whose argument reduction is enormous; the measurement that
 * says they earn it anyway is in {@code .kb/gpu.md}, beside
 * {@code .kb/template-class-embedding.md} for the demerits every template shares.
 *
 * <h2>The kernels cannot be a resource on the other side</h2>
 *
 * {@code CudaGemm} normally reads {@code gemm.ptx} from beside itself on the classpath,
 * and {@code MetalGemm} reads {@code gemm.metal}. Renamed into a compiled program's own
 * package there is no such resource and never can be, so both texts are embedded as
 * ordinary string constants and handed to {@code Gpu.useKernels} /
 * {@code useMetalKernels} by the emitted {@code _gpuInit}, before anything can probe.
 * BOTH travel in every class: the machine that compiled the program is not necessarily
 * the machine that runs it, and a standalone class that accelerated only on its
 * birthplace would not be one.
 */
final class JvmGpuRuntimeBuilder {

	/**
	 * The prefix the library's classes are renamed onto ({@code am/ik/gpu/X} -> ...X),
	 * relative to the generated program's own package (see {@link #build}).
	 */
	static final String GPU_PREFIX = "RontoLispGpu";

	/**
	 * The name the embedded call-site glue is defined under, relative to the generated
	 * program's own package (see {@link #build}).
	 */
	static final String BRIDGE_NAME = "RontoLispGpuBridge";

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmGpuTemplate";

	/** The package prefix of the embedded library, before renaming. */
	private static final String GPU_INTERNAL_PREFIX = "am/ik/gpu/";

	/**
	 * Every class file of {@code am.ik.gpu} that the bridge needs at run time, nested
	 * classes included -- there is no way to enumerate a package from the classpath (let
	 * alone from inside a native image), so the list is written down and
	 * {@code JvmLinalgGpuAccelCompilerTest} pins it against what the build actually
	 * produced. {@code package-info} carries only annotations and is left behind.
	 */
	private static final List<String> GPU_CLASSES = List.of("GpuDevice", "GpuDevice$Thresholds", "CudaDriver",
			"CuResult", "CudaGemm", "CudaGemm$Probe", "CudaGemm$Tile", "DeviceResidency", "DeviceResidency$Entry",
			"DeviceResidency$Flush", "DeviceResidency$Claim", "DeviceResidency$Recent", "DeviceResidency$Key",
			"DeviceResidency$Lookup", "MetalDriver", "MetalGemm", "MetalGemm$Probe", "MetalGemm$Slab", "MetalGemm$Call",
			"Gpu", "Gpu$Probe");

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_gpuInit";

	/**
	 * The emitted guard every in-place write to a packed float array calls under
	 * {@code --gpu}:
	 * {@code _gpuInited != 0 ? RontoLispGpuBridge.gpuWritten(array) : array} -- and
	 * writes into what it ANSWERS, which is the array itself or, for a result stub, the
	 * backing the library holds its elements in ({@code .kb/gpu.md}, "A lazy result
	 * allocates no host array"). The guard is what lets {@code _fvAset1} be emitted
	 * before the bridge class is defined -- a setter that ran before any device member
	 * would otherwise resolve a class that does not exist yet -- and it is the fast path:
	 * a write before the first device call costs a {@code getstatic} and a branch.
	 */
	static final String WRITTEN_METHOD = "_gpuWritten";

	/** The {@code ops} key of {@link #WRITTEN_METHOD}'s method reference. */
	static final String WRITTEN = "written";

	/**
	 * The emitted guard every HOST READ of a packed float array's storage calls under
	 * {@code --gpu}:
	 * {@code _gpuInited != 0 ? RontoLispGpuBridge.gpuMaterialize(array) : array} -- the
	 * other half of {@link #WRITTEN_METHOD}, for lazy results: a result the device still
	 * holds the only copy of comes home before the read, and the reader reads what the
	 * guard ANSWERS (the array, or a stub's backing). Same guard, same reason.
	 */
	static final String MATERIALIZE_METHOD = "_gpuMaterialize";

	/** The {@code ops} key of {@link #MATERIALIZE_METHOD}'s method reference. */
	static final String MATERIALIZE = "materialize";

	/**
	 * The emitted guard a call site runs over a HOST RUNG's answer, once per argument it
	 * handed over through {@link #MATERIALIZE_METHOD} or {@link #WRITTEN_METHOD}:
	 * {@code _gpuInited != 0 ? RontoLispGpuBridge.gpuUnswap(result, original, handed) : result}
	 * -- a rung that answered the argument it was handed (the in-place kernels, an
	 * {@code -into} form, a defun that answers its operand) answers the caller's own
	 * object instead of the backing, so the program never holds a backing beside its
	 * stub.
	 */
	static final String UNSWAP_METHOD = "_gpuUnswap";

	/** The {@code ops} key of {@link #UNSWAP_METHOD}'s method reference. */
	static final String UNSWAP = "unswap";

	/** The {@code ops} key of the Adam update ({@code linalg::%la-adam-step}). */
	static final String ADAM_STEP = "adamStep";

	/** The {@code ops} key of the three-way select ({@code linalg:where}). */
	static final String WHERE = "where";

	/** The {@code ops} key of the strided gather behind {@code linalg:slice}. */
	static final String GATHER_STRIDED = "gatherStrided";

	/**
	 * The {@code ops} key of the scatter-add adjoint, the index tier's three-argument
	 * member.
	 */
	static final String SCATTER_ROWS = "scatterRows";

	/** The {@code ops} key of the rank-2 kernel. */
	static final String DOT = "dot";

	/** The {@code ops} key of the STACKED (rank &gt;= 3) kernel. */
	static final String MATMUL_ND = "matmulNd";

	/**
	 * The {@code ops} keys of the two TRANSPOSED stacked kernels -- the same product with
	 * one operand read in the orientation it is already stored in, which is what the two
	 * matmul adjoints ask for.
	 */
	static final String MATMUL_ND_TA = "matmulNdTa";

	/** The right-operand sibling of {@link #MATMUL_ND_TA}. */
	static final String MATMUL_ND_TB = "matmulNdTb";

	/** The {@code ops} key of the generator fill ({@code linalg::%la-rng-fill}). */
	static final String RNG_FILL = "rngFill";

	/**
	 * The {@code ops} key of the matrix-by-vector product ({@code vec:matvec}) -- the one
	 * device member outside {@code linalg:}, whose call site {@link JvmSimdCompiler}
	 * chains over the lane kernel or the defun.
	 */
	static final String MATVEC = "matvec";

	/**
	 * The {@code ops} keys of the ELEMENT-WISE kernels, one per bridge method. The key is
	 * the method name, which is what {@link JvmLinalgGpu#kernelKey} composes from the
	 * member, so the two need no table between them.
	 */
	private static final List<String> MAP_KERNELS = List.of("gpuExp", "gpuLog", "gpuTanh", "gpuSin", "gpuCos", "gpuTan",
			"gpuAsin", "gpuAcos", "gpuAtan", "gpuSinh", "gpuCosh", "gpuErf", "gpuSqrt", "gpuAbs", "gpuNegative",
			"gpuSign",
			// The fused tier's one-argument member (todo-499).
			"gpuGelu");

	/**
	 * The {@code ops} keys of the STRIDED tier's two-argument kernels: the broadcast
	 * binary ops and the axes transpose. Same convention as {@link #MAP_KERNELS} -- the
	 * key IS the bridge method name.
	 */
	private static final List<String> BINARY_KERNELS = List.of("gpuAdd", "gpuSub", "gpuMul", "gpuDiv", "gpuMaximum",
			"gpuMinimum", "gpuGreater", "gpuGreaterEqual", "gpuLess", "gpuLessEqual", "gpuEqual", "gpuTransposeAxes",
			"gpuReshape", "gpuConcatenate", "gpuScale", "gpuTakeRows", "gpuPick", "gpuSumSquares",
			// The fused tier's two-argument members (todo-499): softmax over its axis,
			// layer-norm's normalization over its epsilon.
			"gpuSoftmaxAxis", "gpuLayerNorm",
			// log-softmax over its axis (todo-629).
			"gpuLogSoftmaxAxis");

	/**
	 * The strided tier's three-argument kernels: the axis folds
	 * ({@code a, axis, keepdims}).
	 */
	private static final List<String> FOLD_KERNELS = List.of("gpuSumAxis", "gpuAmaxAxis", "gpuAminAxis",
			// The fused tier's three-argument adjoints (todo-499).
			"gpuGeluGrad", "gpuSoftmaxGrad",
			// log-softmax's adjoint (todo-629).
			"gpuLogSoftmaxGrad");

	/**
	 * The fused tier's four-argument members: layer-norm's adjoint
	 * ({@code g, x, eps, old}) and the dropout mask ({@code shape, p, st, single}).
	 */
	private static final List<String> FUSED4_KERNELS = List.of("gpuLayerNormGrad", "gpuDropoutMask");

	/**
	 * The fused tier's five-argument members (2026-09-02): the scaled-masked softmax
	 * ({@code x, scale, mask, fill, axis}) and its adjoint
	 * ({@code g, out, axis, scale, mask}).
	 */
	private static final List<String> FUSED5_KERNELS = List.of("gpuScaledMaskedSoftmax", "gpuScaledMaskedSoftmaxGrad");

	/** Keeps each base64 string constant well under the 65535-byte Utf8 limit. */
	private static final int CHUNK_SIZE = 40000;

	private JvmGpuRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _gpuInit} method, its guard field, and the constant-pool
	 * references the accelerated call sites need ({@code ops} keys: {@code init},
	 * {@value #DOT} and {@value #MATMUL_ND}).
	 */
	record GpuRuntime(Utf8Constant initName, Utf8Constant initDesc, List<Integer> initCode, int maxStack, int maxLocals,
			Utf8Constant initedFieldName, Utf8Constant initedFieldDesc, Map<String, MethodrefConstant> ops,
			Utf8Constant writtenName, Utf8Constant writtenDesc, List<Integer> writtenCode, Utf8Constant materializeName,
			Utf8Constant materializeDesc, List<Integer> materializeCode, Utf8Constant unswapName,
			Utf8Constant unswapDesc, List<Integer> unswapCode) {
	}

	/**
	 * Builds the {@code _gpuInit} method body and registers the bridge references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @param packagePrefix the generated class's package as an internal-name prefix
	 * ({@code ""} for the default package, otherwise e.g. {@code "com/example/"}) --
	 * {@code Lookup.defineClass(byte[])} requires the defined class to share the lookup
	 * class's package, so the whole embedded library is renamed into this one too
	 * @return the runtime pieces
	 */
	static GpuRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant stringConcat,
			String packagePrefix) {
		String bridgeName = packagePrefix + BRIDGE_NAME;
		String gpuPrefix = packagePrefix + GPU_PREFIX;
		List<List<ConstantPool.StringConstant>> blobs = new ArrayList<>();
		for (String name : GPU_CLASSES) {
			blobs.add(chunks(cp, rename(loadResource(GPU_INTERNAL_PREFIX + name + ".class"), bridgeName, gpuPrefix)));
		}
		blobs.add(chunks(cp, rename(loadResource(TEMPLATE_INTERNAL_NAME + ".class"), bridgeName, gpuPrefix)));
		// The PTX is text, not bytecode: it is embedded verbatim rather than base64'd,
		// and goes to Gpu.useKernels instead of to defineClass.
		List<ConstantPool.StringConstant> ptx = chunks(cp,
				new String(loadResource(GPU_INTERNAL_PREFIX + "gemm.ptx"), StandardCharsets.ISO_8859_1));
		// ... and the MSL beside it, for the same reason: a class emitted on one machine
		// has to accelerate on the other kind. Both texts travel in every --gpu class.
		List<ConstantPool.StringConstant> msl = chunks(cp,
				new String(loadResource(GPU_INTERNAL_PREFIX + "gemm.metal"), StandardCharsets.ISO_8859_1));

		Utf8Constant initedFieldName = cp.addUtf8("_gpuInited");
		Utf8Constant initedFieldDesc = cp.addUtf8("I");
		FieldrefConstant initedField = cp.addFieldref(thisClass, cp.addNameAndType(initedFieldName, initedFieldDesc));

		ClassConstant base64Class = cp.addClass(cp.addUtf8("java/util/Base64"));
		MethodrefConstant getDecoder = cp.addMethodref(base64Class,
				cp.addNameAndType(cp.addUtf8("getDecoder"), cp.addUtf8("()Ljava/util/Base64$Decoder;")));
		ClassConstant decoderClass = cp.addClass(cp.addUtf8("java/util/Base64$Decoder"));
		MethodrefConstant decode = cp.addMethodref(decoderClass,
				cp.addNameAndType(cp.addUtf8("decode"), cp.addUtf8("(Ljava/lang/String;)[B")));
		ClassConstant methodHandlesClass = cp.addClass(cp.addUtf8("java/lang/invoke/MethodHandles"));
		MethodrefConstant lookup = cp.addMethodref(methodHandlesClass,
				cp.addNameAndType(cp.addUtf8("lookup"), cp.addUtf8("()Ljava/lang/invoke/MethodHandles$Lookup;")));
		ClassConstant lookupClass = cp.addClass(cp.addUtf8("java/lang/invoke/MethodHandles$Lookup"));
		MethodrefConstant defineClass = cp.addMethodref(lookupClass,
				cp.addNameAndType(cp.addUtf8("defineClass"), cp.addUtf8("([B)Ljava/lang/Class;")));

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(bridgeName));
		MethodrefConstant kernels = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("gpuKernels"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant metalKernels = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("gpuMetalKernels"), cp.addUtf8("(Ljava/lang/String;)V")));
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		Utf8Constant writtenName = cp.addUtf8(WRITTEN_METHOD);
		Utf8Constant writtenDesc = cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;");
		ops.put(WRITTEN, cp.addMethodref(thisClass, cp.addNameAndType(writtenName, writtenDesc)));
		MethodrefConstant bridgeWritten = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("gpuWritten"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		Utf8Constant materializeName = cp.addUtf8(MATERIALIZE_METHOD);
		Utf8Constant materializeDesc = cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;");
		ops.put(MATERIALIZE, cp.addMethodref(thisClass, cp.addNameAndType(materializeName, materializeDesc)));
		MethodrefConstant bridgeMaterialize = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("gpuMaterialize"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		Utf8Constant unswapName = cp.addUtf8(UNSWAP_METHOD);
		Utf8Constant unswapDesc = cp
			.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		ops.put(UNSWAP, cp.addMethodref(thisClass, cp.addNameAndType(unswapName, unswapDesc)));
		MethodrefConstant bridgeUnswap = cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuUnswap"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
		ops.put(WHERE, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuWhere"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(ADAM_STEP, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuAdamStep"), cp.addUtf8(
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(GATHER_STRIDED, cp
			.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuGatherStrided"), cp.addUtf8(
					"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(SCATTER_ROWS, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuScatterRows"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put("gpuTranspose", cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("gpuTranspose"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(DOT, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuDot"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATMUL_ND, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuMatmulNd"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATMUL_ND_TA, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuMatmulNdTa"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATMUL_ND_TB, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuMatmulNdTb"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(RNG_FILL, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuRngFill"), cp.addUtf8(
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATVEC, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuMatvec"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		for (String kernel : MAP_KERNELS) {
			ops.put(kernel, cp.addMethodref(bridgeClass,
					cp.addNameAndType(cp.addUtf8(kernel), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;"))));
		}
		for (String kernel : BINARY_KERNELS) {
			ops.put(kernel, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8(kernel),
					cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		}
		for (String kernel : FOLD_KERNELS) {
			ops.put(kernel, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8(kernel),
					cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		}
		for (String kernel : FUSED4_KERNELS) {
			ops.put(kernel, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8(kernel), cp.addUtf8(
					"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		}
		for (String kernel : FUSED5_KERNELS) {
			ops.put(kernel, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8(kernel), cp.addUtf8(
					"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		}

		// --- _gpuInit body ---------------------------------------------------------
		// if (_gpuInited != 0) return;
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int guardPos = code.size();
		code.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(code, 0);
		// One MethodHandles.lookup().defineClass(...) per embedded class. The order is
		// free -- a class file's references to its siblings resolve lazily, on the first
		// instruction that uses one, which is long after all of them are defined.
		for (List<ConstantPool.StringConstant> blob : blobs) {
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, lookup.index()); // [lookup]
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, getDecoder.index()); // [lookup, decoder]
			emitConcatenated(code, blob, stringConcat); // [lookup, decoder, str]
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, decode.index()); // [lookup, bytes]
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, defineClass.index()); // [class]
			code.add(Opcode.POP);
		}
		// RontoLispGpuBridge.gpuKernels(<the PTX text>) -- resolved only now that the
		// class it names exists ([[template-class-embedding]] demerit (c)).
		emitConcatenated(code, ptx, stringConcat);
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, kernels.index());
		emitConcatenated(code, msl, stringConcat);
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, metalKernels.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		JvmRuntimeBuilder.patchBranch(code, guardPos, code.size());
		code.add(Opcode.RETURN);

		// --- _gpuWritten body ------------------------------------------------------
		// return _gpuInited != 0 ? RontoLispGpuBridge.gpuWritten(array) : array;
		List<Integer> written = guard(initedField, bridgeWritten, 1);

		// --- _gpuMaterialize body --------------------------------------------------
		// return _gpuInited != 0 ? RontoLispGpuBridge.gpuMaterialize(array) : array;
		List<Integer> materialize = guard(initedField, bridgeMaterialize, 1);

		// --- _gpuUnswap body -------------------------------------------------------
		// return _gpuInited != 0 ? RontoLispGpuBridge.gpuUnswap(result, original, handed)
		// : result;
		List<Integer> unswap = guard(initedField, bridgeUnswap, 3);

		// The deepest stack is [lookup, decoder, chunk, chunk] inside a class blob.
		return new GpuRuntime(initName, initDesc, code, 4, 1, initedFieldName, initedFieldDesc, ops, writtenName,
				writtenDesc, written, materializeName, materializeDesc, materialize, unswapName, unswapDesc, unswap);
	}

	/**
	 * The body of one guard: {@code _gpuInited != 0 ? bridge(args...) : args[0]}. The
	 * bridge is only named once the field says it has been defined; before that the first
	 * argument is answered untouched, which is the right answer for all three guards
	 * (nothing can be resident, so nothing is swapped).
	 */
	private static List<Integer> guard(FieldrefConstant initedField, MethodrefConstant bridge, int arity) {
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(code, initedField.index());
		int skip = code.size();
		code.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(code, 0);
		for (int i = 0; i < arity; i++) {
			code.add(Opcode.ALOAD);
			code.add(i);
		}
		code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(code, bridge.index());
		code.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(code, skip, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARETURN);
		return code;
	}

	/** Loads one chunk sequence onto the stack, concatenated back into one string. */
	private static void emitConcatenated(List<Integer> code, List<ConstantPool.StringConstant> chunks,
			MethodrefConstant stringConcat) {
		JvmRuntimeBuilder.emitLdc(code, chunks.get(0).index());
		for (int i = 1; i < chunks.size(); i++) {
			JvmRuntimeBuilder.emitLdc(code, chunks.get(i).index());
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, stringConcat.index());
		}
	}

	/** The base64 of a class file, split into Utf8-sized string constants. */
	private static List<ConstantPool.StringConstant> chunks(ConstantPool cp, byte[] classFile) {
		return chunks(cp, Base64.getEncoder().encodeToString(classFile));
	}

	private static List<ConstantPool.StringConstant> chunks(ConstantPool cp, String text) {
		List<ConstantPool.StringConstant> chunks = new ArrayList<>();
		for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
			chunks.add(cp.addString(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE))));
		}
		return chunks;
	}

	/**
	 * Renames one class file out of its own package and out of {@code am.ik.gpu}, into
	 * the generated program's own package. Both renames run over every file: the glue
	 * names the library, the library names itself, and a name that is not in a given file
	 * simply does not match. The library's rename is a PREFIX rule, so a nested class
	 * ({@code am/ik/gpu/Gpu$Probe}) follows its outer one without being listed.
	 * @param bridgeName {@code packagePrefix + }{@link #BRIDGE_NAME}
	 * @param gpuPrefix {@code packagePrefix + }{@link #GPU_PREFIX}
	 */
	private static byte[] rename(byte[] classFile, String bridgeName, String gpuPrefix) {
		byte[] renamed = JvmJavaRuntimeBuilder.renameClass(classFile, TEMPLATE_INTERNAL_NAME, bridgeName);
		return JvmJavaRuntimeBuilder.renameClass(renamed, GPU_INTERNAL_PREFIX, gpuPrefix);
	}

	/** Reads one of the embedded files from the compiler's own classpath. */
	private static byte[] loadResource(String path) {
		try (InputStream in = JvmGpuRuntimeBuilder.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException(path + " not found on the classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The class files this builder embeds, for the test that pins the list. */
	static List<String> embeddedGpuClasses() {
		return GPU_CLASSES;
	}

}
