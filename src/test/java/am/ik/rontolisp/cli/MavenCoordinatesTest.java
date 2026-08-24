package am.ik.rontolisp.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenCoordinatesTest {

	@Test
	void parsesTheThreePartSpec() {
		MavenCoordinates coordinates = MavenCoordinates.parse("com.example:acme-kernels:1.0.0-SNAPSHOT");
		assertThat(coordinates.groupId()).isEqualTo("com.example");
		assertThat(coordinates.artifactId()).isEqualTo("acme-kernels");
		assertThat(coordinates.version()).isEqualTo("1.0.0-SNAPSHOT");
		assertThat(coordinates.metaInfDirectory()).isEqualTo("META-INF/maven/com.example/acme-kernels/");
	}

	@Test
	void aSpecThatIsNotThreeWellFormedPartsIsRefused() {
		assertThatThrownBy(() -> MavenCoordinates.parse("com.example:acme-kernels"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("groupId:artifactId:version");
		assertThatThrownBy(() -> MavenCoordinates.parse("com.example::1.0.0"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("artifactId");
		// The parts land unescaped in an XML text node and in a jar entry path, so the
		// shape is enforced rather than trusted.
		assertThatThrownBy(() -> MavenCoordinates.parse("com.example:kernels:1.0<x"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("version");
		assertThatThrownBy(() -> MavenCoordinates.parse("../evil:kernels:1.0"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("groupId");
	}

	@Test
	void theGeneratedPomCarriesTheCoordinatesAndAnEmptyDependencyList() {
		String pom = MavenCoordinates.parse("com.example:kernels:1.0.0").pomXml(false);
		assertThat(pom).startsWith(MavenCoordinates.POM_MARKER)
			.contains("<groupId>com.example</groupId>")
			.contains("<artifactId>kernels</artifactId>")
			.contains("<version>1.0.0</version>")
			.contains("<packaging>jar</packaging>")
			// The empty list is the property that makes the artifact trivial to consume,
			// so it is stated rather than left out.
			.contains("<dependencies/>")
			.doesNotContain("jdk.incubator.vector");
	}

	@Test
	void aSimdBuildSaysSoInTheDescriptionBecauseTheConsumerDidNotChooseTheFlag() {
		// A --simd class needs --add-modules jdk.incubator.vector in the consumer's JVM,
		// and the consumer never saw the build command: the pom is the only place that
		// can tell them.
		assertThat(MavenCoordinates.parse("com.example:kernels:1.0.0").pomXml(true))
			.contains("--add-modules jdk.incubator.vector");
	}

	@Test
	void thePropertiesFileCarriesNoTimestamp() {
		// Maven writes a build date into its own pom.properties; the emitted artifact
		// must be a function of the program, not of the run.
		assertThat(MavenCoordinates.parse("com.example:kernels:1.0.0").pomProperties())
			.isEqualTo("groupId=com.example\nartifactId=kernels\nversion=1.0.0\n");
	}

}
