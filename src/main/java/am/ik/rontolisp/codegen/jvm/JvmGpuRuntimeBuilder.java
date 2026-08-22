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
 * class files instead, renamed by one prefix rule ({@code am/ik/gpu/} ->
 * {@value #GPU_PREFIX}) into the emitted program's package, and the compiled backend runs
 * the very bytes the interpreter runs. {@link JvmGpuTemplate} rides along as the call
 * site's glue and is renamed the same way, which is what lets it be written against
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
 * and {@code MetalGemm} reads {@code gemm.metal}. Renamed into a compiled program's
 * default package there is no such resource and never can be, so both texts are embedded
 * as ordinary string constants and handed to {@code Gpu.useKernels} /
 * {@code useMetalKernels} by the emitted {@code _gpuInit}, before anything can probe.
 * BOTH travel in every class: the machine that compiled the program is not necessarily
 * the machine that runs it, and a standalone class that accelerated only on its
 * birthplace would not be one.
 */
final class JvmGpuRuntimeBuilder {

	/**
	 * The prefix the library's classes are renamed onto ({@code am/ik/gpu/X} -> ...X).
	 */
	static final String GPU_PREFIX = "RontoLispGpu";

	/** The default-package name the embedded call-site glue is defined under. */
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
			"CuResult", "CudaGemm", "CudaGemm$Probe", "MetalDriver", "MetalGemm", "MetalGemm$Probe", "MetalGemm$Slab",
			"Gpu", "Gpu$Probe");

	/** The emitted init helper method name. */
	static final String INIT_METHOD = "_gpuInit";

	/** The {@code ops} key of the rank-2 kernel. */
	static final String DOT = "dot";

	/** The {@code ops} key of the STACKED (rank &gt;= 3) kernel. */
	static final String MATMUL_ND = "matmulNd";

	/** The {@code ops} key of the generator fill ({@code linalg::%la-rng-fill}). */
	static final String RNG_FILL = "rngFill";

	/**
	 * The {@code ops} keys of the ELEMENT-WISE kernels, one per bridge method. The key is
	 * the method name, which is what {@link JvmLinalgGpu#kernelKey} composes from the
	 * member, so the two need no table between them.
	 */
	private static final List<String> MAP_KERNELS = List.of("gpuExp", "gpuLog", "gpuTanh", "gpuSin", "gpuCos", "gpuTan",
			"gpuAsin", "gpuAcos", "gpuAtan", "gpuSinh", "gpuCosh", "gpuErf");

	/**
	 * The {@code ops} keys of the STRIDED tier's two-argument kernels: the broadcast
	 * binary ops and the axes transpose. Same convention as {@link #MAP_KERNELS} -- the
	 * key IS the bridge method name.
	 */
	private static final List<String> BINARY_KERNELS = List.of("gpuAdd", "gpuSub", "gpuMul", "gpuDiv", "gpuMaximum",
			"gpuMinimum", "gpuTransposeAxes");

	/**
	 * The strided tier's three-argument kernels: the axis folds
	 * ({@code a, axis, keepdims}).
	 */
	private static final List<String> FOLD_KERNELS = List.of("gpuSumAxis", "gpuAmaxAxis", "gpuAminAxis");

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
			Utf8Constant initedFieldName, Utf8Constant initedFieldDesc, Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Builds the {@code _gpuInit} method body and registers the bridge references.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param stringConcat {@code String.concat(String)}
	 * @return the runtime pieces
	 */
	static GpuRuntime build(ConstantPool cp, ClassConstant thisClass, MethodrefConstant stringConcat) {
		List<List<ConstantPool.StringConstant>> blobs = new ArrayList<>();
		for (String name : GPU_CLASSES) {
			blobs.add(chunks(cp, rename(loadResource(GPU_INTERNAL_PREFIX + name + ".class"))));
		}
		blobs.add(chunks(cp, rename(loadResource(TEMPLATE_INTERNAL_NAME + ".class"))));
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

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(BRIDGE_NAME));
		MethodrefConstant kernels = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("gpuKernels"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant metalKernels = cp.addMethodref(bridgeClass,
				cp.addNameAndType(cp.addUtf8("gpuMetalKernels"), cp.addUtf8("(Ljava/lang/String;)V")));
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		Utf8Constant initName = cp.addUtf8(INIT_METHOD);
		Utf8Constant initDesc = cp.addUtf8("()V");
		ops.put("init", cp.addMethodref(thisClass, cp.addNameAndType(initName, initDesc)));
		ops.put(DOT, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuDot"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATMUL_ND, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuMatmulNd"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(RNG_FILL, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("gpuRngFill"), cp.addUtf8(
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
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

		// The deepest stack is [lookup, decoder, chunk, chunk] inside a class blob.
		return new GpuRuntime(initName, initDesc, code, 4, 1, initedFieldName, initedFieldDesc, ops);
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
	 * Renames one class file out of its own package and out of {@code am.ik.gpu}. Both
	 * renames run over every file: the glue names the library, the library names itself,
	 * and a name that is not in a given file simply does not match. The library's rename
	 * is a PREFIX rule, so a nested class ({@code am/ik/gpu/Gpu$Probe}) follows its outer
	 * one without being listed.
	 */
	private static byte[] rename(byte[] classFile) {
		byte[] renamed = JvmJavaRuntimeBuilder.renameClass(classFile, TEMPLATE_INTERNAL_NAME, BRIDGE_NAME);
		return JvmJavaRuntimeBuilder.renameClass(renamed, GPU_INTERNAL_PREFIX, GPU_PREFIX);
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
