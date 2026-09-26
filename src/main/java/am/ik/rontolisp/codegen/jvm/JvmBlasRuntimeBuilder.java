package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;

/**
 * Builds the {@code --blas} CBLAS bridge for the generated {@code .class}: the logic
 * lives in {@link JvmBlasTemplate} (plain Java, compiled by the project build), whose
 * bytecode is read from the classpath at compile time, renamed after the generated
 * program ({@code <Program>$BlasBridge}, in its package) and shipped BESIDE it as an
 * ordinary class file ({@link BlasRuntime#classFiles()}, joined into
 * {@link JvmLispCompiler#runtimeClassFiles()}) -- never defined at run time, which a
 * GraalVM native image refuses ({@code .kb/template-class-embedding.md}). The bridge
 * holds no per-program state and needs no probe of its own (it binds the library at its
 * first call), so the program emits no init method: a call site's method reference
 * resolves the file from the program's class loader like any other class.
 *
 * <p>
 * It is a SECOND bridge rather than more methods on the {@code --simd} one because the
 * two flags are orthogonal: {@code --blas} must work on a build that never asked for
 * {@code --simd} (and so must not drag in the incubator Vector API, which would make the
 * class need {@code --add-modules} to run), and {@code --simd} must keep producing
 * exactly the bytes it produced before.
 */
final class JvmBlasRuntimeBuilder {

	/** The template's internal (constant-pool) class name before renaming. */
	private static final String TEMPLATE_INTERNAL_NAME = "am/ik/rontolisp/codegen/jvm/JvmBlasTemplate";

	/** The {@code ops} key of the {@code linalg:dot} kernel. */
	static final String DOT = "dot";

	/** The {@code ops} key of the {@code vec:matvec} kernel. */
	static final String MATVEC = "matvec";

	/** The {@code ops} key of the {@code vec:matvec-into} kernel. */
	static final String MATVEC_INTO = "matvecInto";

	private JvmBlasRuntimeBuilder() {
	}

	/**
	 * The internal name of a program's bridge class: named after the program, in its
	 * package, so programs built by different rontolisp versions never share one file.
	 * @param programInternalName the generated class's internal (slash-separated) name
	 * @return the bridge's internal name
	 */
	static String bridgeName(String programInternalName) {
		return programInternalName + "$BlasBridge";
	}

	/**
	 * The constant-pool references the accelerated call sites need ({@code ops} keys:
	 * {@value #DOT}, {@value #MATVEC} and {@value #MATVEC_INTO}), and the bridge class
	 * file that travels beside the program, keyed by its path within an output tree.
	 */
	record BlasRuntime(Map<String, MethodrefConstant> ops, Map<String, byte[]> classFiles) {
	}

	/**
	 * Registers the bridge references and renames the bridge class file.
	 * @param cp the constant pool
	 * @param programInternalName the generated class's internal name -- the bridge is
	 * named after it and lives in its package (the bridge methods are package-private)
	 * @return the runtime pieces
	 */
	static BlasRuntime build(ConstantPool cp, String programInternalName) {
		String bridgeName = bridgeName(programInternalName);
		byte[] bridgeBytes = JvmJavaRuntimeBuilder.renameClass(loadTemplateBytes(), TEMPLATE_INTERNAL_NAME, bridgeName);

		ClassConstant bridgeClass = cp.addClass(cp.addUtf8(bridgeName));
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		ops.put(DOT, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("blasDot"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATVEC, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("blasMatvec"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		ops.put(MATVEC_INTO, cp.addMethodref(bridgeClass, cp.addNameAndType(cp.addUtf8("blasMatvecInto"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))));
		return new BlasRuntime(ops, Map.of(bridgeName + ".class", bridgeBytes));
	}

	/** Reads the compiled {@link JvmBlasTemplate} bytecode from the classpath. */
	private static byte[] loadTemplateBytes() {
		try (InputStream in = JvmBlasRuntimeBuilder.class.getResourceAsStream("JvmBlasTemplate.class")) {
			if (in == null) {
				throw new IllegalStateException("JvmBlasTemplate.class not found on the classpath");
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
