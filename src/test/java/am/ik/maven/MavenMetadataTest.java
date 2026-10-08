package am.ik.maven;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code maven-metadata.xml} read as Maven's lenient metadata reader reads it.
 */
class MavenMetadataTest {

	private static MavenMetadata read(String xml) throws XmlParser.Malformed {
		return MavenMetadata.read(xml.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void anArtifactsVersioningAndASnapshotsBuildsAreRead() throws XmlParser.Malformed {
		MavenMetadata versions = read("""
				<metadata><groupId>g</groupId><artifactId>a</artifactId><versioning>
				  <latest> 2.0 </latest><release>1.5</release><unknown><x/></unknown>
				  <versions><version>1.0</version><version>2.0</version></versions>
				  <lastUpdated>20240101000000</lastUpdated></versioning></metadata>
				""");
		assertThat(versions.latest()).isEqualTo("2.0");
		assertThat(versions.release()).isEqualTo("1.5");
		assertThat(versions.versions()).containsExactly("1.0", "2.0");
		assertThat(versions.lastUpdated()).isEqualTo("20240101000000");

		MavenMetadata builds = read(
				"""
						<metadata modelVersion="1.1.0"><versioning>
						  <snapshot><timestamp>20240102.030405</timestamp><buildNumber>2</buildNumber></snapshot>
						  <snapshotVersions><snapshotVersion><classifier>sources</classifier><extension>jar</extension>
						  <value>1.0-20240102.030405-2</value><updated>20240102030405</updated></snapshotVersion></snapshotVersions>
						</versioning></metadata>
						""");
		assertThat(builds.snapshot()).isEqualTo(new MavenMetadata.Snapshot("20240102.030405", 2, false));
		assertThat(builds.snapshotVersions()).isEqualTo(List
			.of(new MavenMetadata.SnapshotVersion("sources", "jar", "1.0-20240102.030405-2", "20240102030405")));
		assertThat(read("<metadata/>")).isEqualTo(MavenMetadata.EMPTY);
	}

	@Test
	void whatMavensReaderRejectsIsRejected() {
		assertThatThrownBy(() -> read(
				"<metadata><versioning><release>1</release><release>2</release></versioning></metadata>"))
			.isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith("Duplicated tag: 'release'");
		assertThatThrownBy(() -> read(
				"<metadata><versioning><snapshot><buildNumber>x</buildNumber></snapshot></versioning></metadata>"))
			.isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith("Unable to parse element value, must be an integer: buildNumber 'x'");
	}

}
