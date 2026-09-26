package am.ik.rontolisp.codegen.jvm;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.Features;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every template bridge travels beside the compiled program as class files of its own,
 * named after the program -- never as bytes the program defines at run time, which a
 * GraalVM native image refuses with an {@code UnsupportedFeatureError} no degrade catches
 * ({@code .kb/template-class-embedding.md}). One program per bridge, and all of them in
 * one program, whose files must not collide.
 */
class ShippedBridgeClassFilesTest {

	/** One bridge: a program that emits it, the flags it needs and its shipped files. */
	private record Bridge(String name, String source, boolean simd, boolean blas, boolean gpu,
			List<String> shippedSuffixes) {
	}

	private static final List<Bridge> BRIDGES = List.of(
			// A class named at run time: a site resolved at compile time is a direct call
			// and needs no bridge.
			new Bridge("java:", "(let ((math \"java.lang.Math\")) (print (java:static math \"max\" 3 7)))", false,
					false, false, List.of("$JavaBridge")),
			new Bridge("geom:", "(print (geom:volume (geom:box 10)))", false, false, false, List.of("$GeomBridge")),
			new Bridge("--simd", "(print (vec:sum #d(1.0 2.0)))", true, false, false, List.of("$SimdBridge")),
			new Bridge("--blas", "(print (linalg:dot #d(1.0 2.0) #d(3.0 4.0)))", false, true, false,
					List.of("$BlasBridge")),
			new Bridge("--gpu", "(print (linalg:matmul #d((1.0)) #d((2.0))))", false, false, true,
					List.of("$GpuBridge", "$GpuGpu", "$GpuCudaDriver", "$GpuMetalGemm")),
			new Bridge("objc:", "(print (objc:objectp 1))", false, false, false,
					List.of("$ObjcBridge", "$ObjcObject", "$ObjcObjcRuntime", "$ObjcObjcException")),
			new Bridge("ffi:", "(print (ffi:pointerp 1))", false, false, false,
					List.of("$FfiBridge", "$FfiPointer", "$FfiFfiRuntime", "$FfiFfiException")));

	private static Map<String, byte[]> compile(String className, String source, boolean simd, boolean blas, boolean gpu,
			Map<String, byte[]> classBytes) {
		JvmLispCompiler compiler = JvmLispCompiler.builder()
			.className(className)
			.optimize(OptimizeLevel.NONE)
			.simd(simd)
			.blas(blas)
			.gpu(gpu)
			.build();
		classBytes.put(className, compiler.compile(CompileFrontendAccess.corpus(source, Features.JVM, false, false)));
		return compiler.runtimeClassFiles();
	}

	private static Map<String, byte[]> shippedBridges(Map<String, byte[]> files, String className) {
		Map<String, byte[]> bridges = new HashMap<>();
		files.forEach((path, bytes) -> {
			if (path.startsWith(className + "$")) {
				bridges.put(path, bytes);
			}
		});
		return bridges;
	}

	@Test
	void everyBridgeShipsAsClassFilesNamedAfterTheProgram() {
		for (Bridge bridge : BRIDGES) {
			Map<String, byte[]> classBytes = new HashMap<>();
			Map<String, byte[]> files = compile("com/example/Prog", bridge.source(), bridge.simd(), bridge.blas(),
					bridge.gpu(), classBytes);
			String program = new String(classBytes.get("com/example/Prog"), StandardCharsets.ISO_8859_1);
			assertThat(program).as("%s: the class defines nothing at run time", bridge.name())
				.doesNotContain("defineClass")
				.doesNotContain("java/util/Base64");
			for (String suffix : bridge.shippedSuffixes()) {
				assertThat(files).as(bridge.name()).containsKey("com/example/Prog" + suffix + ".class");
			}
			Map<String, byte[]> shipped = shippedBridges(files, "com/example/Prog");
			assertThat(shipped).as(bridge.name()).isNotEmpty();
			shipped.forEach((path, bytes) -> assertThat(new String(bytes, StandardCharsets.ISO_8859_1))
				.as("%s: %s keeps no name from before the rename", bridge.name(), path)
				.doesNotContain("am/ik/rontolisp/codegen/jvm/")
				.doesNotContain("am/ik/gpu/")
				.doesNotContain("am/ik/objc/")
				.doesNotContain("am/ik/ffi/"));
		}
	}

	@Test
	void everyBridgeInOneProgramShipsWithoutACollision() {
		// The files of one bridge must never overwrite another's: they are all named by
		// prefixing the program's name, so a library class could shadow a bridge.
		int expected = 0;
		for (Bridge bridge : BRIDGES) {
			expected += shippedBridges(
					compile("Prog", bridge.source(), bridge.simd(), bridge.blas(), bridge.gpu(), new HashMap<>()),
					"Prog")
				.size();
		}
		StringBuilder all = new StringBuilder();
		for (Bridge bridge : BRIDGES) {
			all.append(bridge.source()).append('\n');
		}
		Map<String, byte[]> files = compile("Prog", all.toString(), true, true, true, new HashMap<>());
		assertThat(shippedBridges(files, "Prog")).hasSize(expected);
	}

	@Test
	void twoProgramsInOnePackageShipDisjointFiles() {
		// A Maven module compiles every program into one target/classes: a bridge holds
		// per-program static state (bind's _apply, the device residency), so no file may
		// be shared between two programs.
		Bridge gpu = BRIDGES.get(4);
		Map<String, byte[]> first = compile("com/example/First", gpu.source(), false, false, true, new HashMap<>());
		Map<String, byte[]> second = compile("com/example/Second", gpu.source(), false, false, true, new HashMap<>());
		assertThat(shippedBridges(first, "com/example/First")).isNotEmpty();
		assertThat(shippedBridges(first, "com/example/First").keySet())
			.doesNotContainAnyElementsOf(shippedBridges(second, "com/example/Second").keySet());
	}

}
