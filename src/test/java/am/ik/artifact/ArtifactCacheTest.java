package am.ik.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verified downloads, the cache areas, and the atomic install every consumer's "existence
 * is the installed mark" rests on.
 */
class ArtifactCacheTest {

	private static final String URL = "https://repo.example/lib-1.0.jar";

	/** {@code md5 -s hello} / {@code sha1}. */
	private static final String HELLO_MD5 = "5d41402abc4b2a76b9719d911017c592";

	private static final String HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

	private static ArtifactCache serving(Path root, String body) {
		return new ArtifactCache(root, url -> body.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void aDownloadMatchingEveryChecksumIsAnswered(@TempDir Path root) throws IOException {
		byte[] bytes = serving(root, "hello").download(URL, 5, Checksum.md5(HELLO_MD5), Checksum.sha1(HELLO_SHA1));

		assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hello");
	}

	@Test
	void aDigestMismatchIsRefused(@TempDir Path root) {
		assertThatThrownBy(() -> serving(root, "hellO").download(URL, Checksum.sha1(HELLO_SHA1)))
			.isInstanceOf(IOException.class)
			.hasMessageStartingWith("SHA-1 mismatch for " + URL + ": expected " + HELLO_SHA1 + ", got ");
	}

	@Test
	void aSizeMismatchIsReportedBeforeTheDigest(@TempDir Path root) {
		assertThatThrownBy(() -> serving(root, "hel").download(URL, 5, Checksum.md5(HELLO_MD5)))
			.isInstanceOf(IOException.class)
			.hasMessage("size mismatch for " + URL + ": expected 5 bytes, got 3");
	}

	@Test
	void aMalformedDigestIsRefusedWhereItIsRead() {
		assertThatThrownBy(() -> Checksum.md5("md5")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a MD5 digest (32 hex digits expected): 'md5'");
		assertThat(Checksum.sha1(HELLO_SHA1.toUpperCase()).hex()).isEqualTo(HELLO_SHA1);
	}

	@Test
	void anAreaIsOneSegmentUnderTheRoot(@TempDir Path root) {
		ArtifactCache cache = serving(root, "");

		assertThat(cache.area("maven")).isEqualTo(root.resolve("maven"));
		assertThatThrownBy(() -> cache.area("..")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> cache.area("a/b")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aFailedWriterInstallsNothingAndLeavesNoStaging(@TempDir Path root) throws IOException {
		Path target = root.resolve("entries").resolve("lib-1.0");

		assertThatThrownBy(() -> AtomicInstall.installDirectory(target, staging -> {
			Files.writeString(staging.resolve("half.txt"), "half");
			throw new IOException("writer died");
		})).isInstanceOf(IOException.class).hasMessage("writer died");

		assertThat(target).doesNotExist();
		assertThat(entriesOf(root.resolve("entries"))).isEmpty();
	}

	@Test
	void theDirectoryTheWriterAnswersBecomesTheEntry(@TempDir Path root) throws IOException {
		Path target = root.resolve("lib-1.0");

		AtomicInstall.installDirectory(target, staging -> {
			Path tree = Files.createDirectories(staging.resolve("lib-1.0"));
			Files.writeString(tree.resolve("lib.txt"), "content");
			Files.writeString(staging.resolve("outside.txt"), "dropped");
			return tree;
		});

		assertThat(entriesOf(root)).containsExactly("lib-1.0");
		assertThat(entriesOf(target)).containsExactly("lib.txt");
	}

	@Test
	void anExistingEntryIsKeptAndTheWriterNotRun(@TempDir Path root) throws IOException {
		Path target = Files.createDirectories(root.resolve("lib-1.0"));

		AtomicInstall.installDirectory(target, staging -> {
			throw new AssertionError("must not run");
		});

		assertThat(entriesOf(root)).containsExactly("lib-1.0");
	}

	@Test
	void writeFileReplacesAWholeFile(@TempDir Path root) throws IOException {
		Path file = root.resolve("index").resolve("releases.txt");

		AtomicInstall.writeFile(file, "old".getBytes(StandardCharsets.UTF_8));
		AtomicInstall.writeFile(file, "new".getBytes(StandardCharsets.UTF_8));

		assertThat(Files.readString(file)).isEqualTo("new");
		assertThat(entriesOf(root.resolve("index"))).containsExactly("releases.txt");
	}

	private static List<String> entriesOf(Path dir) throws IOException {
		try (Stream<Path> entries = Files.list(dir)) {
			return entries.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

}
