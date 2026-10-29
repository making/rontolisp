package am.ik.rontolisp;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code src/native/java} is compiled only under {@code -Pnative}, by the native-image
 * build and by nothing {@code ./mvnw test} runs, so a member it substitutes or aliases
 * can be renamed in the main tree and the first thing to notice is the CI image build.
 * SubstrateVM checks {@code @Alias} and {@code @Substitute} against the target class at
 * image build time; this test does the same from the JVM lane, by reading the source
 * ({@code .kb/native-downcalls.md}).
 */
class NativeSubstitutionsTest {

	private static final Path ROOT = Path.of("src/native/java");

	private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

	private static final Pattern TARGET = Pattern.compile("@TargetClass\\((\\w+)\\.class\\)");

	/** {@code @Alias} over one or more fields: the type, then a comma list of names. */
	private static final Pattern ALIAS_FIELD = Pattern
		.compile("@Alias\\s+(?:private\\s+|static\\s+|final\\s+)*[\\w.\\[\\]<>]+\\s+(\\w+(?:\\s*,\\s*\\w+)*)\\s*;");

	/** {@code @Alias static native} over a method. */
	private static final Pattern ALIAS_METHOD = Pattern
		.compile("@Alias\\s+(?:private\\s+|static\\s+)*native\\s+[\\w.\\[\\]<>]+\\s+(\\w+)\\s*\\(([^)]*)\\)");

	private static final Pattern SUBSTITUTE = Pattern
		.compile("@Substitute\\s+(?:private\\s+|static\\s+|final\\s+)*[\\w.\\[\\]<>]+\\s+(\\w+)\\s*\\(([^)]*)\\)");

	@Test
	void everyAliasedAndSubstitutedMemberExistsOnItsTargetClass() throws Exception {
		List<Path> sources = sources();
		assertThat(sources).as("the native source set is not empty").isNotEmpty();
		List<String> problems = new ArrayList<>();
		for (Path source : sources) {
			String text = Files.readString(source);
			if (!TARGET.matcher(text).find()) {
				// a Feature, which javac checks against the main tree when the image
				// builds
				continue;
			}
			Class<?> target = target(source, text);
			for (Matcher m = ALIAS_FIELD.matcher(text); m.find();) {
				for (String name : m.group(1).split("\\s*,\\s*")) {
					if (Arrays.stream(target.getDeclaredFields()).noneMatch(f -> f.getName().equals(name))) {
						problems.add(source + ": @Alias field " + name + " is not a field of " + target.getName());
					}
				}
			}
			for (Matcher m = ALIAS_METHOD.matcher(text); m.find();) {
				check(problems, source, target, "@Alias", m.group(1), m.group(2));
			}
			for (Matcher m = SUBSTITUTE.matcher(text); m.find();) {
				check(problems, source, target, "@Substitute", m.group(1), m.group(2));
			}
		}
		assertThat(problems).as("src/native/java members with no counterpart in the main tree").isEmpty();
	}

	@Test
	void theScanReachesTheSubstitutions() throws Exception {
		// Guards the check above against passing vacuously on a pattern that matches
		// nothing: the one substitution this source set started with is still found.
		Path blas = ROOT.resolve("am/ik/rontolisp/eval/Target_LinalgBlasKernels.java");
		String text = Files.readString(blas);
		assertThat(SUBSTITUTE.matcher(text).results().map(r -> r.group(1)).toList()).contains("gemm", "gemmF", "gemv",
				"gemvF");
		assertThat(ALIAS_FIELD.matcher(text).results().map(r -> r.group(1)).toList())
			.anyMatch(names -> names.contains("DGEMV_ADDRESS"));
		assertThat(ALIAS_METHOD.matcher(text).results().map(r -> r.group(1)).toList()).contains("note");
	}

	/** A method of the target with this name and the same parameter type names. */
	private static void check(List<String> problems, Path source, Class<?> target, String kind, String name,
			String parameters) {
		List<String> wanted = parameterTypes(parameters);
		boolean found = Arrays.stream(target.getDeclaredMethods())
			.filter(m -> m.getName().equals(name))
			.map(Method::getParameterTypes)
			.anyMatch(types -> Arrays.stream(types).map(Class::getSimpleName).toList().equals(wanted));
		if (!found) {
			problems.add(source + ": " + kind + " " + name + "(" + String.join(", ", wanted) + ") is not a method of "
					+ target.getName());
		}
	}

	/**
	 * The simple type names of a parameter list, {@code "double[] a, int oa"} ->
	 * [double[], int].
	 */
	private static List<String> parameterTypes(String parameters) {
		if (parameters.isBlank()) {
			return List.of();
		}
		return Arrays.stream(parameters.split(",")).map(String::strip).map(p -> {
			String[] words = p.split("\\s+");
			return words[words.length - 2].replaceAll("^.*\\.", "");
		}).toList();
	}

	private static Class<?> target(Path source, String text) throws ClassNotFoundException {
		Matcher pkg = PACKAGE.matcher(text);
		Matcher target = TARGET.matcher(text);
		assertThat(pkg.find()).as(source + " declares a package").isTrue();
		assertThat(target.find()).as(source + " carries @TargetClass(X.class)").isTrue();
		return Class.forName(pkg.group(1) + "." + target.group(1));
	}

	private static List<Path> sources() throws IOException {
		try (Stream<Path> tree = Files.walk(ROOT)) {
			return tree.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}

}
