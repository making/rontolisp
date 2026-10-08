package am.ik.maven;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Version constraints as Maven Resolver's {@code GenericVersionScheme} reads them: one
 * version, or a union of ranges, refused in its words when malformed.
 */
class VersionConstraintTest {

	private static boolean contains(String constraint, String version) throws MavenResolutionException {
		return VersionConstraint.parse(constraint).contains(GenericVersion.parse(version));
	}

	@Test
	void aRangeContainsWhatItsBoundsAdmit() throws MavenResolutionException {
		assertThat(contains("[1.0,2.0)", "1.0")).isTrue();
		assertThat(contains("[1.0,2.0)", "1.9.9")).isTrue();
		assertThat(contains("[1.0,2.0)", "2.0")).isFalse();
		assertThat(contains("(1.0,2.0]", "1.0")).isFalse();
		assertThat(contains("(,1.0]", "0.1")).isTrue();
		assertThat(contains("[1.0,)", "99")).isTrue();
		// 1.0 and 1 are one version
		assertThat(contains("[1]", "1.0")).isTrue();
		assertThat(contains("[1.*]", "1.5-beta")).isTrue();
		assertThat(contains("[1.*]", "2.0")).isFalse();
		assertThat(contains("[1.0],[3.0,)", "3.1")).isTrue();
		assertThat(contains("[1.0],[3.0,)", "2.0")).isFalse();
		assertThat(contains("1.0", "1.0")).isTrue();
		assertThat(contains("1.0", "1.1")).isFalse();
	}

	@Test
	void itPrintsAsMavenResolverDoes() throws MavenResolutionException {
		assertThat(VersionConstraint.parse("[1.0]")).hasToString("[1.0,1.0]");
		assertThat(VersionConstraint.parse("[1.*]")).hasToString("[1.min,1.max]");
		assertThat(VersionConstraint.parse("[1.0], [3.0,)")).hasToString("[1.0,1.0], [3.0,)");
		assertThat(VersionConstraint.parse("[ 1.0 , 2.0 )")).hasToString("[1.0,2.0)");
		assertThat(VersionConstraint.parse("1.0-SNAPSHOT")).hasToString("1.0-SNAPSHOT");
		VersionConstraint union = VersionConstraint.parse("[1,2),(3,4]");
		assertThat(union.lowerBound()).isEqualTo(new VersionConstraint.Bound(GenericVersion.parse("1"), true));
		assertThat(union.upperBound()).isEqualTo(new VersionConstraint.Bound(GenericVersion.parse("4"), true));
		assertThat(VersionConstraint.parse("[1,)").upperBound()).isNull();
	}

	@Test
	void aMalformedRangeIsRefusedInMavensWords() {
		assertThatThrownBy(() -> VersionConstraint.parse("[1.0,2.0")).hasMessage("Unbounded version range [1.0,2.0");
		assertThatThrownBy(() -> VersionConstraint.parse("(1.0)"))
			.hasMessage("Invalid version range (1.0), single version must be surrounded by []");
		assertThatThrownBy(() -> VersionConstraint.parse("[2.0,1.0]"))
			.hasMessage("Invalid version range [2.0,1.0], lower bound must not be greater than upper bound");
		assertThatThrownBy(() -> VersionConstraint.parse("[1,2,3]"))
			.hasMessage("Invalid version range [1,2,3], bounds may not contain additional ','");
		assertThatThrownBy(() -> VersionConstraint.parse("[1,2) 3"))
			.hasMessage("Invalid version range [1,2) 3, expected [ or ( but got 3");
	}

}
