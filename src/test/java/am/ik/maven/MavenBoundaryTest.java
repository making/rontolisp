package am.ik.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.artifact.HttpStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the resolver stops short of Maven on purpose, each pinned so a change to it is a
 * decision: the boundaries {@code .kb/maven-resolver.md} lists.
 */
class MavenBoundaryTest {

	@TempDir
	Path local;

	@Test
	void repositoriesAPomDeclaresAreNeverContacted(@TempDir Path remoteRoot) throws IOException {
		Path pom = remoteRoot.resolve("test/repos/declares/1/declares-1.pom");
		Files.createDirectories(pom.getParent());
		byte[] bytes = """
				<project>
				  <modelVersion>4.0.0</modelVersion>
				  <parent><groupId>test.repos</groupId><artifactId>only-elsewhere</artifactId><version>1</version></parent>
				  <artifactId>declares</artifactId>
				  <repositories><repository><id>elsewhere</id><url>https://elsewhere.example/repo/</url></repository></repositories>
				</project>
				"""
			.getBytes(StandardCharsets.UTF_8);
		Files.write(pom, bytes);
		Files.writeString(pom.resolveSibling("declares-1.pom.sha1"), MavenTestRepository.sha1(bytes));
		List<String> requested = new ArrayList<>();
		MavenResolver resolver = MavenResolver.builder()
			.localRepository(this.local)
			.repositories(List.of(new RemoteRepository("fixture", remoteRoot.toUri().toString())))
			.downloader(url -> {
				requested.add(url);
				throw new HttpStatusException(404, url);
			})
			.systemProperties(MavenTestRepository.SYSTEM)
			.build();

		assertThatThrownBy(() -> resolver.descriptor(Artifact.parse("test.repos:declares:1")))
			.isInstanceOf(MavenResolutionException.class)
			.hasMessage("Non-resolvable parent POM test.repos:only-elsewhere:1 for test.repos:declares:1: no "
					+ "repository has it");
		assertThat(requested).isEmpty();
	}

}
