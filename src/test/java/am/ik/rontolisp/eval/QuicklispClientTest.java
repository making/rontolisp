package am.ik.rontolisp.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Quicklisp downloader behind {@code ql:quickload}: dist-index parsing, tarball
 * download + extraction, caching and transitive dependency resolution -- all against an
 * in-memory distribution ({@link QuicklispTestSupport}), no network access.
 */
class QuicklispClientTest {

	private static final String MYLIB_TARBALL_URL = "http://fake.quicklisp/archive/mylib-1.0.tar.gz";

	private static final String CHILD_TARBALL_URL = "http://fake.quicklisp/archive/child-1.0.tar.gz";

	@Test
	void downloadsExtractsAndReturnsAsdDirectory(@TempDir Path home) throws IOException {
		QuicklispTestSupport.RecordingDownloader downloader = QuicklispTestSupport.dist(//
				"# project system-file system-name [dependency1..dependencyN]\nmylib mylib mylib\n", //
				"# project url size file-md5 content-sha1 prefix [system-file1..system-fileN]\n" + "mylib "
						+ MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_TARBALL_URL, QuicklispTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)"))));
		QuicklispClient client = new QuicklispClient(home, downloader);

		List<String> asdDirs = client.ensureAvailable("mylib");

		Path extracted = home.resolve("software").resolve("mylib-1.0");
		assertThat(asdDirs).containsExactly(extracted.toAbsolutePath().normalize().toString());
		assertThat(Files.readString(extracted.resolve("mylib.lisp"))).contains("mylib-answer");
		assertThat(Files.readString(extracted.resolve("mylib.asd"))).contains("defsystem");
	}

	@Test
	void reusesTheCacheOnASecondCall(@TempDir Path home) throws IOException {
		QuicklispTestSupport.RecordingDownloader downloader = QuicklispTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_TARBALL_URL, QuicklispTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)"))));
		QuicklispClient client = new QuicklispClient(home, downloader);

		client.ensureAvailable("mylib");
		client.ensureAvailable("mylib");

		// The tarball (and the dist indexes) are fetched exactly once.
		assertThat(downloader.hits.get(MYLIB_TARBALL_URL)).isEqualTo(1);
		assertThat(downloader.hits.get(QuicklispTestSupport.DISTINFO_URL)).isEqualTo(1);
	}

	@Test
	void resolvesTransitiveDependencies(@TempDir Path home) throws IOException {
		QuicklispTestSupport.RecordingDownloader downloader = QuicklispTestSupport.dist(//
				"parent parent parent child\nchild child child\n", //
				"parent " + MYLIB_TARBALL_URL + " 100 md5 sha1 parent-1.0 parent\n" //
						+ "child " + CHILD_TARBALL_URL + " 100 md5 sha1 child-1.0 child\n", //
				Map.of(//
						MYLIB_TARBALL_URL,
						QuicklispTestSupport.tarGz(Map.of("parent-1.0/parent.asd",
								"(defsystem \"parent\" :depends-on (\"child\") :components ((:file \"parent\")))",
								"parent-1.0/parent.lisp", "(defun p () 1)")), //
						CHILD_TARBALL_URL,
						QuicklispTestSupport.tarGz(
								Map.of("child-1.0/child.asd", "(defsystem \"child\" :components ((:file \"child\")))",
										"child-1.0/child.lisp", "(defun c () 2)"))));
		QuicklispClient client = new QuicklispClient(home, downloader);

		List<String> asdDirs = client.ensureAvailable("parent");

		// Both the dependency and the requested system are downloaded and locatable.
		assertThat(downloader.hits).containsKey(MYLIB_TARBALL_URL).containsKey(CHILD_TARBALL_URL);
		assertThat(asdDirs).anyMatch(d -> d.endsWith("parent-1.0")).anyMatch(d -> d.endsWith("child-1.0"));
	}

	@Test
	void reportsAnUnknownSystemClearly(@TempDir Path home) {
		QuicklispTestSupport.RecordingDownloader downloader = QuicklispTestSupport.dist("mylib mylib mylib\n",
				"mylib " + MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", Map.of());
		QuicklispClient client = new QuicklispClient(home, downloader);

		assertThatThrownBy(() -> client.ensureAvailable("nope")).isInstanceOf(IOException.class)
			.hasMessageContaining("nope")
			.hasMessageContaining("Quicklisp distribution");
	}

}
