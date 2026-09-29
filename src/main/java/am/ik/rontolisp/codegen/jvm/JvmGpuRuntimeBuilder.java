package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

/**
 * Builds the {@code --gpu} device bridge for the generated {@code .class}: the
 * {@link JvmSimdRuntimeBuilder} / {@link JvmBlasRuntimeBuilder} mechanism, extended from
 * ONE shipped class to a CLOSURE of them plus one data resource.
 *
 * <h2>Why the whole library travels, instead of a flattened copy of it</h2>
 *
 * {@code --blas} ships {@link JvmBlasTemplate}, a flat 375-line COPY of
 * {@code eval/LinalgBlasKernels} that the two must be kept in sync by hand. A GPU binding
 * is not 375 lines: {@code am.ik.gpu} is ~1700 across four classes, and the parts that
 * took the longest to get right are exactly the parts a copy would fork -- a decline that
 * must cost the device nothing (three calls, in one order), the 101-entry
 * {@code CUresult} table and which seventeen of its statuses are sticky, the per-device
 * safepoint threshold, the chunked critical copies. So the program carries the library's
 * own class files instead, renamed by one prefix rule ({@code am/ik/gpu/} ->
 * {@code <Program>}{@value #GPU_SUFFIX}, so {@code Gpu} becomes {@code <Program>$GpuGpu})
 * and shipped BESIDE the program ({@link GpuRuntime#classFiles()}, joined into
 * {@link JvmLispCompiler#runtimeClassFiles()}) -- never defined at run time, which a
 * GraalVM native image refuses ({@code .kb/template-class-embedding.md}) -- and the
 * compiled backend runs the very bytes the interpreter runs. {@link JvmGpuTemplate} rides
 * along as the call site's glue ({@code <Program>$GpuBridge}) and is renamed the same
 * way, which is what lets it be written against {@code am.ik.gpu} and type-checked by
 * javac. Named after the program because the library holds per-program static state (the
 * residency cache, the probed device).
 *
 * <p>
 * The PTX the program carries is 85 KB. Two thirds of it is the element-wise tier and
 * half of THAT is {@code sin} / {@code cos} / {@code tan}, whose argument reduction is
 * enormous; the measurement that says they earn it anyway is in {@code .kb/gpu.md},
 * beside {@code .kb/template-class-embedding.md} for the demerits every template shares.
 *
 * <h2>The kernels cannot be a resource on the other side</h2>
 *
 * {@code CudaGemm} normally reads {@code gemm.ptx} from beside itself on the classpath,
 * and {@code MetalGemm} reads {@code gemm.metal}. Renamed after a compiled program there
 * is no such resource, so both texts are embedded in the program as ordinary string
 * constants and handed to {@code Gpu.useKernels} / {@code useMetalKernels} by the emitted
 * {@code _gpuInit}, before anything can probe. BOTH travel in every class: the machine
 * that compiled the program is not necessarily the machine that runs it, and a compiled
 * program that accelerated only on its birthplace would not be portable.
 */
final class JvmGpuRuntimeBuilder {

	/**
	 * Appended to the generated program's internal name to form the prefix the library's
	 * classes are renamed onto ({@code am/ik/gpu/X} -> {@code <Program>$GpuX}).
	 */
	static final String GPU_SUFFIX = "$Gpu";

	/**
	 * Appended to the generated program's internal name to name the call-site glue
	 * ({@link JvmGpuTemplate}).
	 */
	static final String BRIDGE_SUFFIX = "$GpuBridge";

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmGpuTemplate";

	/** The package prefix of the embedded library, before renaming. */
	private static final String GPU_INTERNAL_PREFIX = "am/ik/gpu/";

	/**
	 * Every class file of {@code am.ik.gpu} that the bridge needs at run time, nested
	 * classes included -- there is no way to enumerate a package from the classpath (let
	 * alone from inside a native image), so the list is written down and
	 * {@code JvmLinalgGpuAccelCompilerTest} pins it against what the build actually
	 * produced. {@code package-info} carries only annotations and is left behind. The
	 * order is free: every file is on disk before any of them loads.
	 */
	private static final List<String> GPU_CLASSES = List.of("GpuDevice", "GpuDevice$Thresholds", "CudaDriver",
			"CuResult", "CudaGemm", "CudaGemm$Probe", "CudaGemm$Tile", "DeviceResidency", "DeviceResidency$Entry",
			"DeviceResidency$Flush", "DeviceResidency$Claim", "DeviceResidency$Recent", "DeviceResidency$Key",
			"DeviceResidency$Lookup", "MetalDriver", "MetalGemm", "MetalGemm$Probe", "MetalGemm$Slab", "MetalGemm$Call",
			"MetalGemm$Inflight", "Gpu", "Gpu$Probe");

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_gpuInit";

	/**
	 * The emitted guard every in-place write to a packed float array calls under
	 * {@code --gpu}:
	 * {@code _gpuInited != 0 ? <Program>$GpuBridge.gpuWritten(array) : array} -- and
	 * writes into what it ANSWERS, which is the array itself or, for a result stub, the
	 * backing the library holds its elements in ({@code .kb/gpu.md}, "A lazy result
	 * allocates no host array"). Before the first device member nothing can be resident,
	 * so a write before {@code _gpuInit} has run needs no bridge at all -- the guard
	 * keeps such a program from loading the library for nothing, and it is the fast path:
	 * a write before the first device call costs a {@code getstatic} and a branch.
	 */
	static final String WRITTEN_METHOD = "_gpuWritten";

	/** The {@code ops} key of {@link #WRITTEN_METHOD}'s method reference. */
	static final String WRITTEN = "written";

	/**
	 * The emitted guard every HOST READ of a packed float array's storage calls under
	 * {@code --gpu}:
	 * {@code _gpuInited != 0 ? <Program>$GpuBridge.gpuMaterialize(array) : array} -- the
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
	 * {@code _gpuInited != 0 ? <Program>$GpuBridge.gpuUnswap(result, original, handed) : result}
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
	 * ({@code g, x, eps, old}) and the dropout mask ({@code shape, p, st, width}).
	 */
	private static final List<String> FUSED4_KERNELS = List.of("gpuLayerNormGrad", "gpuDropoutMask",
			// Layer-norm's affine forward ({@code x, w, b, eps}), todo-634.
			"gpuLayerNormAffine");

	/**
	 * The fused tier's five-argument members (2026-09-02): the scaled-masked softmax
	 * ({@code x, scale, mask, fill, axis}) and its adjoint
	 * ({@code g, out, axis, scale, mask}).
	 */
	private static final List<String> FUSED5_KERNELS = List.of("gpuScaledMaskedSoftmax", "gpuScaledMaskedSoftmaxGrad",
			// Layer-norm's affine adjoint ({@code g, x, w, eps, old}), todo-634.
			"gpuLayerNormAffineGrad");

	/**
	 * The library's native-image downcall registration, beside its classes. It travels
	 * with them to {@link #nativeImageMetadataPath}.
	 */
	private static final String NATIVE_IMAGE_METADATA = "reachability-metadata.json";

	/** Keeps each kernel-text string constant well under the 65535-byte Utf8 limit. */
	private static final int CHUNK_SIZE = 40000;

	private JvmGpuRuntimeBuilder() {
	}

	/**
	 * The ready-to-emit {@code _gpuInit} method, its guard field, the constant-pool
	 * references the accelerated call sites need ({@code ops} keys: {@code init},
	 * {@value #DOT} and {@value #MATMUL_ND}), and the class files that travel beside the
	 * program -- plus the library's native-image downcall registration -- keyed by their
	 * paths within an output tree.
	 */
	record GpuRuntime(Utf8Entry initName, Utf8Entry initDesc, MethodCode initCode, Utf8Entry initedFieldName,
			Utf8Entry initedFieldDesc, Map<String, MethodRefEntry> ops, Utf8Entry writtenName, Utf8Entry writtenDesc,
			MethodCode writtenCode, Utf8Entry materializeName, Utf8Entry materializeDesc, MethodCode materializeCode,
			Utf8Entry unswapName, Utf8Entry unswapDesc, MethodCode unswapCode, Map<String, byte[]> classFiles) {
	}

	/**
	 * The internal name of a program's call-site glue class.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the bridge's internal name, in the program's own package
	 */
	static String bridgeName(String programInternalName) {
		return programInternalName + BRIDGE_SUFFIX;
	}

	/**
	 * The prefix a program's copy of {@code am.ik.gpu} is renamed onto.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the prefix, e.g. {@code com/example/Prog$Gpu} ({@code Gpu} becomes
	 * {@code com/example/Prog$GpuGpu})
	 */
	static String gpuPrefix(String programInternalName) {
		return programInternalName + GPU_SUFFIX;
	}

	/**
	 * Where a program's copy of the native-image downcall registration travels: under
	 * {@code META-INF/native-image/}, which native-image reads from every class path
	 * entry (a jar, {@code target/classes}, the directory beside {@code -o X.class}), in
	 * a directory named after the program so two programs in one tree keep two files.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the path within an output tree
	 */
	static String nativeImageMetadataPath(String programInternalName) {
		return "META-INF/native-image/rontolisp-gpu/" + programInternalName.replace('/', '.') + "/"
				+ NATIVE_IMAGE_METADATA;
	}

	/**
	 * Builds the {@code _gpuInit} method body, registers the bridge references and
	 * renames the class files that travel beside the program.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @param programInternalName the generated class's internal name -- the classes are
	 * named after it and live in its package (their members are package-private)
	 * @return the runtime pieces
	 */
	static GpuRuntime build(ConstantPool cp, ClassEntry thisClass, MethodRefEntry stringConcat,
			String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		String gpuPrefix = gpuPrefix(programInternalName);
		Map<String, byte[]> classFiles = new LinkedHashMap<>();
		for (String name : GPU_CLASSES) {
			classFiles.put(gpuPrefix + name + ".class",
					rename(loadResource(GPU_INTERNAL_PREFIX + name + ".class"), bridgeName, gpuPrefix));
		}
		classFiles.put(bridgeName + ".class",
				rename(loadResource(TEMPLATE_INTERNAL_NAME + ".class"), bridgeName, gpuPrefix));
		// The downcall shapes the classes above bind, where native-image reads them
		// from a class path entry: without them an image built from the output refuses
		// the binding and runs every member on the CPU.
		classFiles.put(nativeImageMetadataPath(programInternalName),
				loadResource(GPU_INTERNAL_PREFIX + NATIVE_IMAGE_METADATA));
		// The PTX is text, not bytecode: it travels verbatim in the program's own
		// constant pool and goes to Gpu.useKernels.
		List<StringEntry> ptx = chunks(cp,
				new String(loadResource(GPU_INTERNAL_PREFIX + "gemm.ptx"), StandardCharsets.ISO_8859_1));
		// ... and the MSL beside it, for the same reason: a class emitted on one machine
		// has to accelerate on the other kind. Both texts travel in every --gpu class.
		List<StringEntry> msl = chunks(cp,
				new String(loadResource(GPU_INTERNAL_PREFIX + "gemm.metal"), StandardCharsets.ISO_8859_1));

		Utf8Entry initedFieldName = cp.utf8Entry("_gpuInited");
		Utf8Entry initedFieldDesc = cp.utf8Entry("I");
		FieldRefEntry initedField = cp.fieldRef(thisClass, initedFieldName, initedFieldDesc);

		ClassEntry bridgeClass = cp.classEntry(bridgeName);
		MethodRefEntry kernels = cp.methodRef(bridgeClass, "gpuKernels", "(Ljava/lang/String;)V");
		MethodRefEntry metalKernels = cp.methodRef(bridgeClass, "gpuMetalKernels", "(Ljava/lang/String;)V");
		Map<String, MethodRefEntry> ops = new LinkedHashMap<>();
		Utf8Entry initName = cp.utf8Entry(INIT_METHOD);
		Utf8Entry initDesc = cp.utf8Entry("()V");
		ops.put("init", cp.methodRef(thisClass, initName, initDesc));
		Utf8Entry writtenName = cp.utf8Entry(WRITTEN_METHOD);
		Utf8Entry writtenDesc = cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/Object;");
		ops.put(WRITTEN, cp.methodRef(thisClass, writtenName, writtenDesc));
		MethodRefEntry bridgeWritten = cp.methodRef(bridgeClass, "gpuWritten",
				"(Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Entry materializeName = cp.utf8Entry(MATERIALIZE_METHOD);
		Utf8Entry materializeDesc = cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/Object;");
		ops.put(MATERIALIZE, cp.methodRef(thisClass, materializeName, materializeDesc));
		MethodRefEntry bridgeMaterialize = cp.methodRef(bridgeClass, "gpuMaterialize",
				"(Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Entry unswapName = cp.utf8Entry(UNSWAP_METHOD);
		Utf8Entry unswapDesc = cp
			.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		ops.put(UNSWAP, cp.methodRef(thisClass, unswapName, unswapDesc));
		MethodRefEntry bridgeUnswap = cp.methodRef(bridgeClass, "gpuUnswap",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		ops.put(WHERE, cp.methodRef(bridgeClass, "gpuWhere",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(ADAM_STEP, cp.methodRef(bridgeClass, "gpuAdamStep",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(GATHER_STRIDED, cp.methodRef(bridgeClass, "gpuGatherStrided",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(SCATTER_ROWS, cp.methodRef(bridgeClass, "gpuScatterRows",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put("gpuTranspose", cp.methodRef(bridgeClass, "gpuTranspose", "(Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(DOT, cp.methodRef(bridgeClass, "gpuDot", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(MATMUL_ND,
				cp.methodRef(bridgeClass, "gpuMatmulNd", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(MATMUL_ND_TA,
				cp.methodRef(bridgeClass, "gpuMatmulNdTa", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(MATMUL_ND_TB,
				cp.methodRef(bridgeClass, "gpuMatmulNdTb", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(RNG_FILL, cp.methodRef(bridgeClass, "gpuRngFill",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		ops.put(MATVEC,
				cp.methodRef(bridgeClass, "gpuMatvec", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		for (String kernel : MAP_KERNELS) {
			ops.put(kernel, cp.methodRef(bridgeClass, kernel, "(Ljava/lang/Object;)Ljava/lang/Object;"));
		}
		for (String kernel : BINARY_KERNELS) {
			ops.put(kernel,
					cp.methodRef(bridgeClass, kernel, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		}
		for (String kernel : FOLD_KERNELS) {
			ops.put(kernel, cp.methodRef(bridgeClass, kernel,
					"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		}
		for (String kernel : FUSED4_KERNELS) {
			ops.put(kernel, cp.methodRef(bridgeClass, kernel,
					"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		}
		for (String kernel : FUSED5_KERNELS) {
			ops.put(kernel, cp.methodRef(bridgeClass, kernel,
					"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		}

		// --- _gpuInit body ---------------------------------------------------------
		// if (_gpuInited != 0) return;
		MethodCode code = new MethodCode();
		code.getstatic(initedField);
		MethodCode.Label guard = code.newLabel();
		code.ifne(guard);
		// <Program>$GpuBridge.gpuKernels(<the PTX text>) -- the bridge loads from the
		// program's own class loader like any other class beside it.
		emitConcatenated(code, ptx, stringConcat);
		code.invokestatic(kernels);
		emitConcatenated(code, msl, stringConcat);
		code.invokestatic(metalKernels);
		code.iconst_1();
		code.putstatic(initedField);
		code.labelBinding(guard);
		code.return_();

		// --- _gpuWritten body ------------------------------------------------------
		// return _gpuInited != 0 ? <Program>$GpuBridge.gpuWritten(array) : array;
		MethodCode written = guard(initedField, bridgeWritten, 1);

		// --- _gpuMaterialize body --------------------------------------------------
		// return _gpuInited != 0 ? <Program>$GpuBridge.gpuMaterialize(array) : array;
		MethodCode materialize = guard(initedField, bridgeMaterialize, 1);

		// --- _gpuUnswap body -------------------------------------------------------
		// return _gpuInited != 0 ? <Program>$GpuBridge.gpuUnswap(result, original,
		// handed)
		// : result;
		MethodCode unswap = guard(initedField, bridgeUnswap, 3);

		return new GpuRuntime(initName, initDesc, code, initedFieldName, initedFieldDesc, ops, writtenName, writtenDesc,
				written, materializeName, materializeDesc, materialize, unswapName, unswapDesc, unswap, classFiles);
	}

	/**
	 * The body of one guard: {@code _gpuInited != 0 ? bridge(args...) : args[0]}. The
	 * bridge is only called once the field says the device half has been set up; before
	 * that the first argument is answered untouched, which is the right answer for all
	 * three guards (nothing can be resident, so nothing is swapped).
	 */
	private static MethodCode guard(FieldRefEntry initedField, MethodRefEntry bridge, int arity) {
		MethodCode code = new MethodCode();
		code.getstatic(initedField);
		MethodCode.Label skip = code.newLabel();
		code.ifeq(skip);
		for (int i = 0; i < arity; i++) {
			code.aload(i);
		}
		code.invokestatic(bridge);
		code.areturn();
		code.labelBinding(skip);
		code.aload(0);
		code.areturn();
		return code;
	}

	/** Loads one chunk sequence onto the stack, concatenated back into one string. */
	private static void emitConcatenated(MethodCode code, List<StringEntry> chunks, MethodRefEntry stringConcat) {
		code.ldc(chunks.get(0));
		for (int i = 1; i < chunks.size(); i++) {
			code.ldc(chunks.get(i));
			code.invokevirtual(stringConcat);
		}
	}

	/** A text split into Utf8-sized string constants. */
	private static List<StringEntry> chunks(ConstantPool cp, String text) {
		List<StringEntry> chunks = new ArrayList<>();
		for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
			chunks.add(cp.stringEntry(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE))));
		}
		return chunks;
	}

	/**
	 * Renames one class file out of its own package and out of {@code am.ik.gpu}, after
	 * the generated program. Both renames run over every file: the glue names the
	 * library, the library names itself, and a name that is not in a given file simply
	 * does not match. The library's rename is a PREFIX rule, so a nested class
	 * ({@code am/ik/gpu/Gpu$Probe}) follows its outer one without being listed.
	 * @param bridgeName {@link #bridgeName}
	 * @param gpuPrefix {@link #gpuPrefix}
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

	/** The library class files this builder ships, for the test that pins the list. */
	static List<String> embeddedGpuClasses() {
		return GPU_CLASSES;
	}

}
