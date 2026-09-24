package am.ik.rontolisp;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No method of the main classes is too large for the JIT, except the ones named below.
 * HotSpot never compiles a method whose bytecode exceeds {@code HugeMethodLimit} (8,000
 * bytes, a develop flag, so fixed in a product JVM): it runs interpreted however hot it
 * gets, loops included, since OSR is refused too. A per-node dispatcher crossing that
 * line costs every compile -- {@code WasmExprCompiler.compileConsLocated}, one switch
 * over every operator, had reached 28 KB and was 12% of a WASM compile's CPU before its
 * dispatch was sliced into methods under the limit.
 *
 * <p>
 * The allowed methods run once per compile or once per class initialization, so their own
 * interpretation is a fixed cost rather than one per form; a method joining the list
 * needs that argument.
 */
class HugeMethodTest {

	/** HotSpot's {@code HugeMethodLimit}. */
	private static final int HUGE_METHOD_LIMIT = 8000;

	private static final Set<String> RUN_ONCE = Set.of("am.ik.rontolisp.PackageRegistry.<clinit>",
			"am.ik.rontolisp.codegen.jvm.JvmLispCompiler.compile",
			"am.ik.rontolisp.codegen.wasm.WasmLispCompiler.compile",
			"am.ik.rontolisp.codegen.jvm.JvmArrayRuntimeBuilder.build",
			"am.ik.rontolisp.codegen.wasm.WasmEvalRuntimeBuilder.buildEvalBody",
			"am.ik.rontolisp.codegen.jvm.JvmExprCompiler.compileConsLocated");

	@Test
	void everyMethodOutsideTheRunOnceListIsSmallEnoughToCompile() throws IOException, URISyntaxException {
		Path root = Path.of(ClosRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Set<String> huge = new TreeSet<>();
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
				ClassModel model = ClassFile.of().parse(file);
				String owner = model.thisClass().asInternalName().replace('/', '.');
				for (MethodModel method : model.methods()) {
					int length = method.findAttribute(Attributes.code()).map(CodeAttribute::codeLength).orElse(0);
					if (length > HUGE_METHOD_LIMIT) {
						huge.add(owner + "." + method.methodName().stringValue());
					}
				}
			}
		}
		assertThat(huge).isSubsetOf(RUN_ONCE);
	}

}
