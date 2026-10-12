package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.codegen.jvm.JvmClassFileLookup;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaImplementationType;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * A native image lowers a Clojure host form against class files -- the JDK's
 * {@code ct.sym} for its own release and the program's Java class path -- where a JVM
 * reflects, since the image's own reflection holds only what its build registered
 * ({@code .kb/clojure-frontend.md}, "Host classes"). Both must lower every program of
 * {@link ClojureLoweringCorpus} to the same forms, or the native binary compiles a
 * program differently from the jar; this pins it without a native build, which
 * {@code ClojureNativeLoweringE2eTest} then checks of the binary itself.
 */
class ClojureClassFilesLoweringTest {

	@TempDir
	static Path workDir;

	@TestFactory
	Stream<DynamicTest> classFilesLowerEveryProgramAsReflectionDoes() throws Exception {
		assumeTrue(JvmClassFileLookup.findCtSym() != null, "no JDK lib/ct.sym to read the class files from");
		List<DynamicTest> tests = new ArrayList<>();
		for (ClojureLoweringCorpus.Program program : ClojureLoweringCorpus.programs(workDir)) {
			tests.add(dynamicTest(program.name(), () -> {
				List<String> reflected = printed(
						CompileFrontendAccess.clojureFile(program.file(), program.javaClassPath(), null).forms());
				List<String> read;
				Counting asked;
				try (JvmClassFileLookup classFiles = JvmClassFileLookup.forJdk(null, program.javaClassPath())) {
					asked = new Counting(classFiles);
					read = printed(
							CompileFrontendAccess.clojureFile(program.file(), program.javaClassPath(), asked).forms());
				}
				assertThat(reflected).as("%s: the reflected lowering", program.name()).isNotEmpty();
				if (program.name().equals("host-forms")) {
					// the class files answered, not a reflection the plumbing fell back
					// to
					assertThat(asked.finds).as("class questions the class files answered").isPositive();
				}
				int differs = firstDifference(reflected, read);
				assertThat(differs)
					.as("%s: the class-file lowering differs from the reflected one at form %d%n  reflected:   %s%n"
							+ "  class files: %s", program.name(), differs, excerpt(reflected, differs),
							excerpt(read, differs))
					.isEqualTo(-1);
			}));
		}
		return tests.stream();
	}

	/** A lookup counting the classes asked of it. */
	private static final class Counting implements JavaClassLookup {

		private final JavaClassLookup lookup;

		int finds;

		Counting(JavaClassLookup lookup) {
			this.lookup = lookup;
		}

		@Override
		public @Nullable JavaType find(String name) {
			this.finds++;
			return this.lookup.find(name);
		}

		@Override
		public JavaImplementationType implementationOf(List<JavaType> interfaces) {
			return this.lookup.implementationOf(interfaces);
		}

		@Override
		public JavaImplementationType subclassOf(JavaType superclass, List<JavaType> interfaces) {
			return this.lookup.subclassOf(superclass, interfaces);
		}

	}

	private static List<String> printed(List<LispVal> forms) {
		List<String> lines = new ArrayList<>(forms.size());
		for (LispVal form : forms) {
			lines.add(form.print());
		}
		return lines;
	}

	/** The index of the first form the two lowerings differ at, or -1 when they agree. */
	private static int firstDifference(List<String> a, List<String> b) {
		int common = Math.min(a.size(), b.size());
		for (int i = 0; i < common; i++) {
			if (!a.get(i).equals(b.get(i))) {
				return i;
			}
		}
		return a.size() == b.size() ? -1 : common;
	}

	private static String excerpt(List<String> lines, int index) {
		if (index < 0 || index >= lines.size()) {
			return "(none)";
		}
		String line = lines.get(index);
		return line.length() <= 400 ? line : line.substring(0, 400) + " ...";
	}

}
