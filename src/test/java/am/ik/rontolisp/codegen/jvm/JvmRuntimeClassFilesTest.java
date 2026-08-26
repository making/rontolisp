package am.ik.rontolisp.codegen.jvm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That EVERY class file of {@code am.ik.rontolisp.runtime} travels with something.
 *
 * <p>
 * Nothing can enumerate a package from a classpath, still less from inside a native
 * image, so each feature's travelling list is hand-kept -- the packed float-array
 * handle's ({@code JvmExportRuntimeBuilder}) and the served-request runtime's
 * ({@code JvmHttpHandlerRuntimeBuilder}). This pins their union against the package's
 * actual class files, so a class added there is a failure HERE rather than a
 * {@code NoClassDefFoundError} in someone's deployment. Which of the two lists it belongs
 * to is the feature's own test's business ({@code JvmHttpHandlerTravellingRuntimeTest}
 * recomputes the served closure).
 *
 * <p>
 * {@code package-info.class} deliberately stays behind: it carries only the build's
 * nullness annotation, which is the compiler's business and not the artifact's.
 */
class JvmRuntimeClassFilesTest {

	@Test
	void everyClassFileOfTheRuntimePackageIsOnATravellingList() throws Exception {
		Path packageDir = Path.of("target/classes/am/ik/rontolisp/runtime");
		try (Stream<Path> files = Files.list(packageDir)) {
			List<String> onDisk = files.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".class"))
				.filter(name -> !name.equals("package-info.class"))
				.sorted()
				.toList();
			assertThat(Stream
				.concat(JvmExportRuntimeBuilder.RUNTIME_CLASS_FILES.stream(),
						JvmHttpHandlerRuntimeBuilder.RUNTIME_CLASS_FILES.stream())
				.map(path -> path.substring(path.lastIndexOf('/') + 1))
				.sorted()
				.toList()).isEqualTo(onDisk);
		}
	}

}
