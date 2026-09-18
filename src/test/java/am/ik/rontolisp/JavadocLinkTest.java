package am.ik.rontolisp;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code mvn javadoc:jar} is the only place a stale {@code {@literal @}link} target -- an
 * API signature the source moved on from -- gets caught, and that only runs in the deploy
 * job of {@code .github/workflows/ci.yaml}, an hour into the run and against develop
 * already (the {@code SchemeLibrary} javadoc pointing at the old no-arg
 * {@code Scheme#runtimeForms()} broke deploy this way). This test drives the real javadoc
 * tool in-process, over the same sources with the same visibility and release the plugin
 * uses, so a broken cross-reference fails on every push instead.
 *
 * <p>
 * {@code -subpackages am.ik} discovers every package under the shipped source root
 * itself, so this never needs updating when a package is added or removed.
 */
class JavadocLinkTest {

	@Test
	void everyJavadocReferenceResolves(@TempDir Path out) throws IOException {
		DocumentationTool tool = ToolProvider.getSystemDocumentationTool();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fm = tool.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
			List<String> options = List.of("-protected", "--release", String.valueOf(Runtime.version().feature()),
					"-sourcepath", "src/main/java" + File.pathSeparator + "target/generated-sources/nullability",
					"-subpackages", "am.ik", "-classpath", System.getProperty("java.class.path"), "-d", out.toString(),
					"-quiet");
			DocumentationTool.DocumentationTask task = tool.getTask(new StringWriter(), fm, diagnostics, null, options,
					null);
			task.call();
		}
		List<String> errors = diagnostics.getDiagnostics()
			.stream()
			.filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
			.map(Object::toString)
			.toList();
		assertThat(errors).isEmpty();
	}

}
