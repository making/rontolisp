package am.ik.rontolisp.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule that turns an {@code -o} path into the name of the class emitted into it.
 */
class JvmArtifactOptionsTest {

	private static String name(String outputFile) {
		return JvmArtifactOptions.NONE.internalClassName(outputFile);
	}

	@Test
	void aRelativeDirectoryIsThePackage() {
		assertThat(name("com/acme/Kernels.class")).isEqualTo("com/acme/Kernels");
		assertThat(name("Prog.class")).isEqualTo("Prog");
	}

	@Test
	void aDirectoryTheJvmAcceptsStaysThePackage() {
		// A package segment is not a Java identifier, it is a JVM unqualified name: a
		// dash is fine there and `java -cp . out-dir.T3` runs, so nothing is dropped.
		assertThat(name("out-dir/T3.class")).isEqualTo("out-dir/T3");
		assertThat(name("2048/T3.class")).isEqualTo("2048/T3");
	}

	@Test
	void anAbsoluteDirectoryIsNotAPackage() {
		// The leading separator opens the name with an EMPTY segment, which no JVM
		// loads; a package was never plausible, so the directory is just a directory.
		assertThat(name("/tmp/out/T2.class")).isEqualTo("T2");
		assertThat(name("/T2.class")).isEqualTo("T2");
	}

	@Test
	void aDirectoryHoldingADotIsNotAPackage() {
		// `.` separates packages, so a segment cannot contain one -- ./ and ../ included.
		assertThat(name("./T2.class")).isEqualTo("T2");
		assertThat(name("../out/T2.class")).isEqualTo("T2");
		assertThat(name("build.d/T2.class")).isEqualTo("T2");
	}

	@Test
	void aFileNameThatCannotBeAClassNameIsRefused() {
		// Nothing derivable works: the class file is written under the -o name, so a
		// stem that cannot be a class name leaves no loadable spelling to fall back to.
		assertThatThrownBy(() -> name("/tmp/out/my.prog.class")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("my.prog")
			.hasMessageContaining("--class-name");
		assertThatThrownBy(() -> name(".class")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--class-name");
	}

	@Test
	void anOutputThatNamesNoClassIsRefused() {
		assertThatThrownBy(() -> name("out.wasm")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--class-name");
	}

	@Test
	void anArchiveReadsItsStemOnly() {
		// A jar and a war never read the directory at all, so an absolute path was
		// always safe there -- pinned so the .class rule above cannot drift into them.
		assertThat(name("/tmp/build/my-app-1.0.0.jar")).isEqualTo("MyApp100");
		assertThat(name("/tmp/x/app.war")).isEqualTo("App");
		assertThat(name("/tmp/x/2048.jar")).isEqualTo("_2048");
	}

	@Test
	void anExplicitClassNameWinsOverThePath() {
		JvmArtifactOptions options = new JvmArtifactOptions("com.example.Kernels", null, false);
		assertThat(options.internalClassName("/tmp/out/T2.class")).isEqualTo("com/example/Kernels");
	}

}
