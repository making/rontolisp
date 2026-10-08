package am.ik.maven;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coordinates in Maven Resolver's spelling, the repository layout path, and the versions
 * that need {@code maven-metadata.xml}.
 */
class ArtifactTest {

	@Test
	void parsesMavensCoordinateForms() {
		assertThat(Artifact.parse("org.example:lib:1.0"))
			.isEqualTo(new Artifact("org.example", "lib", "1.0", "", "jar"));
		assertThat(Artifact.parse("org.example:lib:pom:1.0"))
			.isEqualTo(new Artifact("org.example", "lib", "1.0", "", "pom"));
		assertThat(Artifact.parse("org.example:lib:jar:sources:1.0"))
			.isEqualTo(new Artifact("org.example", "lib", "1.0", "sources", "jar"));
		assertThat(Artifact.parse("org.example:lib::tests:1.0").extension()).isEqualTo("jar");
		assertThatThrownBy(() -> Artifact.parse("org.example:lib")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void printsAsMavenDoes() {
		assertThat(Artifact.parse("org.example:lib:1.0")).hasToString("org.example:lib:jar:1.0");
		assertThat(Artifact.parse("org.example:lib:jar:sources:1.0")).hasToString("org.example:lib:jar:sources:1.0");
	}

	@Test
	void thePathIsTheMaven2Layout() {
		assertThat(Artifact.parse("org.example.deep:lib:jar:sources:1.0").path())
			.isEqualTo("org/example/deep/lib/1.0/lib-1.0-sources.jar");
		assertThat(Artifact.parse("org.example:lib:jar:sources:1.0").pom().path())
			.isEqualTo("org/example/lib/1.0/lib-1.0.pom");
	}

	@Test
	void twoVersionsShareTheVersionlessKey() {
		assertThat(Artifact.parse("g:a:1").versionlessKey()).isEqualTo(Artifact.parse("g:a:2").versionlessKey())
			.isNotEqualTo(Artifact.parse("g:a:jar:tests:1").versionlessKey());
	}

	@Test
	void versionsThatNeedMetadataAreNamed() {
		assertThat(Artifact.parse("g:a:1.0").unsupportedVersion()).isNull();
		assertThat(Artifact.parse("g:a:1.0-SNAPSHOT").unsupportedVersion()).startsWith("SNAPSHOT versions");
		assertThat(Artifact.parse("g:a:1.0-20240101.123456-7").unsupportedVersion()).startsWith("SNAPSHOT versions");
		assertThat(Artifact.parse("g:a:[1.0,2.0)").unsupportedVersion()).startsWith("version ranges");
		assertThat(Artifact.parse("g:a:(,2.0]").unsupportedVersion()).startsWith("version ranges");
		assertThat(Artifact.parse("g:a:LATEST").unsupportedVersion()).startsWith("the LATEST and RELEASE");
		assertThat(Artifact.parse("g:a:RELEASE").unsupportedVersion()).startsWith("the LATEST and RELEASE");
	}

}
