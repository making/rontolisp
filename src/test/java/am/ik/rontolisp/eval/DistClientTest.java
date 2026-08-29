package am.ik.rontolisp.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dist downloader behind {@code ql:quickload}: dist-index parsing, tarball download +
 * extraction, caching, transitive dependency resolution and the multi-dist search order
 * ({@code ql-dist:install-dist} / {@code --dist}) -- all against in-memory distributions
 * ({@link DistTestSupport}), no network access.
 */
class DistClientTest {

	private static final String MYLIB_TARBALL_URL = "http://fake.quicklisp/archive/mylib-1.0.tar.gz";

	private static final String CHILD_TARBALL_URL = "http://fake.quicklisp/archive/child-1.0.tar.gz";

	private static final String ULTRA_TARBALL_URL = "http://fake.ultralisp/archive/fresh-1.0.tar.gz";

	@Test
	void downloadsExtractsAndReturnsAsdDirectory(@TempDir Path base) throws IOException {
		DistTestSupport.RecordingDownloader downloader = DistTestSupport.dist(//
				"# project system-file system-name [dependency1..dependencyN]\nmylib mylib mylib\n", //
				"# project url size file-md5 content-sha1 prefix [system-file1..system-fileN]\n" + "mylib "
						+ MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_TARBALL_URL, DistTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)"))));
		DistClient client = new DistClient(base, downloader);

		List<String> asdDirs = client.ensureAvailable("mylib");

		// Each dist caches under its own subdirectory of the base.
		Path extracted = base.resolve("quicklisp").resolve("software").resolve("mylib-1.0");
		assertThat(asdDirs).containsExactly(extracted.toAbsolutePath().normalize().toString());
		assertThat(Files.readString(extracted.resolve("mylib.lisp"))).contains("mylib-answer");
		assertThat(Files.readString(extracted.resolve("mylib.asd"))).contains("defsystem");
	}

	@Test
	void reusesTheCacheOnASecondCall(@TempDir Path base) throws IOException {
		DistTestSupport.RecordingDownloader downloader = DistTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_TARBALL_URL, DistTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)"))));
		DistClient client = new DistClient(base, downloader);

		client.ensureAvailable("mylib");
		client.ensureAvailable("mylib");

		// The tarball (and the dist indexes) are fetched exactly once.
		assertThat(downloader.hits.get(MYLIB_TARBALL_URL)).isEqualTo(1);
		assertThat(downloader.hits.get(DistTestSupport.DISTINFO_URL)).isEqualTo(1);
	}

	@Test
	void resolvesTransitiveDependencies(@TempDir Path base) throws IOException {
		DistTestSupport.RecordingDownloader downloader = DistTestSupport.dist(//
				"parent parent parent child\nchild child child\n", //
				"parent " + MYLIB_TARBALL_URL + " 100 md5 sha1 parent-1.0 parent\n" //
						+ "child " + CHILD_TARBALL_URL + " 100 md5 sha1 child-1.0 child\n", //
				Map.of(//
						MYLIB_TARBALL_URL,
						DistTestSupport.tarGz(Map.of("parent-1.0/parent.asd",
								"(defsystem \"parent\" :depends-on (\"child\") :components ((:file \"parent\")))",
								"parent-1.0/parent.lisp", "(defun p () 1)")), //
						CHILD_TARBALL_URL,
						DistTestSupport.tarGz(
								Map.of("child-1.0/child.asd", "(defsystem \"child\" :components ((:file \"child\")))",
										"child-1.0/child.lisp", "(defun c () 2)"))));
		DistClient client = new DistClient(base, downloader);

		List<String> asdDirs = client.ensureAvailable("parent");

		// Both the dependency and the requested system are downloaded and locatable.
		assertThat(downloader.hits).containsKey(MYLIB_TARBALL_URL).containsKey(CHILD_TARBALL_URL);
		assertThat(asdDirs).anyMatch(d -> d.endsWith("parent-1.0")).anyMatch(d -> d.endsWith("child-1.0"));
	}

	@Test
	void reportsAnUnknownSystemClearly(@TempDir Path base) {
		DistTestSupport.RecordingDownloader downloader = DistTestSupport.dist("mylib mylib mylib\n",
				"mylib " + MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", Map.of());
		DistClient client = new DistClient(base, downloader);

		assertThatThrownBy(() -> client.ensureAvailable("nope")).isInstanceOf(IOException.class)
			.hasMessageContaining("nope")
			.hasMessageContaining("installed dists")
			.hasMessageContaining("quicklisp");
	}

	@Test
	void resolvesAnUnlistedSecondarySystemThroughItsPrimaryRelease(@TempDir Path base) throws IOException {
		// A NAME/SUB the dist index does not list individually lives in NAME.asd by
		// ASDF's naming rule, so the primary's release is downloaded -- this is how
		// (ql:quickload "tiny-routes/lite"), a system the replacement .asd adds,
		// fetches the tiny-routes release. The asdf loader then locates the slash
		// name against that same file and reports loudly if it is not defined there.
		DistTestSupport.RecordingDownloader downloader = DistTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_TARBALL_URL, DistTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)"))));
		DistClient client = new DistClient(base, downloader);

		List<String> asdDirs = client.ensureAvailable("mylib/extra");

		assertThat(asdDirs).anyMatch(dir -> dir.endsWith("mylib-1.0"));
		// A slash name whose PRIMARY is unknown still fails loudly.
		assertThatThrownBy(() -> client.ensureAvailable("nope/extra")).isInstanceOf(IOException.class)
			.hasMessageContaining("nope/extra")
			.hasMessageContaining("installed dists");
	}

	// --- search-path order ---

	@Test
	void theAsdDirectoriesOfAReleaseAreSortedWhateverOrderTheHostWalkedThemIn() {
		// Files.walk hands back the host's directory order, so the order this list
		// arrives in is a property of the machine, not of the release. Pinning the
		// SORTED result (rather than whatever this filesystem happens to return) is the
		// point: two developers must get one search path from one release.
		List<Path> hostWalkOrder = List.of(//
				Path.of("/dist/mylib-1.0/test/mylib.asd"), //
				Path.of("/dist/mylib-1.0/ext/dep/dep.asd"), //
				Path.of("/dist/mylib-1.0/mylib.asd"), //
				Path.of("/dist/mylib-1.0/contrib/extra.asd"), //
				Path.of("/dist/mylib-1.0/test/mylib-tests.asd"));
		List<String> out = new ArrayList<>();

		DistClient.addAsdDirs(hostWalkOrder, out, new HashSet<>());

		// Sorted, deduplicated -- and the release's top level comes first, because a
		// parent path is a prefix of every path below it. That is what decides which
		// mylib.asd asdf:load-system reads.
		assertThat(out).containsExactly(//
				"/dist/mylib-1.0", //
				"/dist/mylib-1.0/contrib", //
				"/dist/mylib-1.0/ext/dep", //
				"/dist/mylib-1.0/test");
	}

	@Test
	void aReleaseDefiningOneSystemTwiceResolvesToItsTopLevelAsd(@TempDir Path base) throws IOException {
		// Releases do ship a second .asd under test/ defining the same system name.
		// The tarball writes the nested one first on purpose: the search path must not
		// depend on the order the files landed in.
		LinkedHashMap<String, String> files = new LinkedHashMap<>();
		files.put("mylib-1.0/test/mylib.asd", "(defsystem \"mylib\" :components ((:file \"nested\")))");
		files.put("mylib-1.0/test/nested.lisp", "(defun mylib-answer () 0)");
		files.put("mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))");
		files.put("mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)");
		DistTestSupport.RecordingDownloader downloader = DistTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_TARBALL_URL, DistTestSupport.tarGz(files)));
		DistClient client = new DistClient(base, downloader);

		List<String> asdDirs = client.ensureAvailable("mylib");

		Path extracted = base.resolve("quicklisp").resolve("software").resolve("mylib-1.0");
		assertThat(asdDirs).containsExactly(extracted.toAbsolutePath().normalize().toString(),
				extracted.resolve("test").toAbsolutePath().normalize().toString());
	}

	// --- multiple dists ---

	private static DistTestSupport.RecordingDownloader twoDists() {
		return DistTestSupport.dists(//
				DistTestSupport.quicklisp(//
						"mylib mylib mylib\n", //
						"mylib " + MYLIB_TARBALL_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
						Map.of(MYLIB_TARBALL_URL, DistTestSupport.tarGz(Map.of(//
								"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
								"mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)")))),
				DistTestSupport.ultralisp(//
						// The same system name in both dists, plus one only ultralisp
						// has.
						"fresh fresh fresh\nmylib mylib mylib\n", //
						"fresh " + ULTRA_TARBALL_URL + " 100 md5 sha1 fresh-1.0 fresh\n" //
								+ "mylib " + ULTRA_TARBALL_URL + " 100 md5 sha1 fresh-1.0 fresh\n", //
						Map.of(ULTRA_TARBALL_URL, DistTestSupport.tarGz(Map.of(//
								"fresh-1.0/fresh.asd", "(defsystem \"fresh\" :components ((:file \"fresh\")))", //
								"fresh-1.0/fresh.lisp", "(defun fresh-answer () 7)")))));
	}

	@Test
	void aSystemOnlyTheSecondDistHasIsDownloadedFromIt(@TempDir Path base) throws IOException {
		DistTestSupport.RecordingDownloader downloader = twoDists();
		DistClient client = new DistClient(base, downloader, List.of(DistClient.ULTRALISP));

		List<String> asdDirs = client.ensureAvailable("fresh");

		assertThat(client.installedNames()).containsExactly("quicklisp", "ultralisp");
		// Extracted under the ultralisp cache, not the quicklisp one.
		assertThat(asdDirs)
			.containsExactly(base.resolve("ultralisp").resolve("software").resolve("fresh-1.0").toString());
	}

	@Test
	void theFirstDistListingASystemProvidesIt(@TempDir Path base) throws IOException {
		DistTestSupport.RecordingDownloader downloader = twoDists();
		DistClient client = new DistClient(base, downloader, List.of(DistClient.ULTRALISP));

		List<String> asdDirs = client.ensureAvailable("mylib");

		// Both dists list "mylib"; quicklisp is searched first, so its release wins and
		// the ultralisp index is never even fetched.
		assertThat(asdDirs)
			.containsExactly(base.resolve("quicklisp").resolve("software").resolve("mylib-1.0").toString());
		assertThat(downloader.hits).doesNotContainKey(DistTestSupport.ULTRALISP_DISTINFO_URL);
	}

	@Test
	void namingQuicklispExplicitlyReordersTheSearch(@TempDir Path base) throws IOException {
		DistTestSupport.RecordingDownloader downloader = twoDists();
		DistClient client = new DistClient(base, downloader, List.of(DistClient.ULTRALISP, DistClient.QUICKLISP));

		List<String> asdDirs = client.ensureAvailable("mylib");

		assertThat(client.installedNames()).containsExactly("ultralisp", "quicklisp");
		assertThat(asdDirs)
			.containsExactly(base.resolve("ultralisp").resolve("software").resolve("fresh-1.0").toString());
	}

	@Test
	void aDistIsInstallableByItsDistinfoUrlAndIdentifiedByItsHost(@TempDir Path base) throws IOException {
		DistTestSupport.RecordingDownloader downloader = twoDists();
		DistClient client = new DistClient(base, downloader);

		// The URL Ultralisp's own front page tells a user to install, which serves the
		// distinfo directly: it names the same dist as the "ultralisp" spelling, so it
		// shares the cache directory and installing both is one dist, not two.
		assertThat(client.installDist("http://dist.ultralisp.org/")).isEqualTo("ultralisp");
		assertThat(client.installDist(DistClient.ULTRALISP)).isEqualTo("ultralisp");
		assertThat(client.installedNames()).containsExactly("quicklisp", "ultralisp");
		assertThat(client.ensureAvailable("fresh"))
			.containsExactly(base.resolve("ultralisp").resolve("software").resolve("fresh-1.0").toString());
	}

	@Test
	void anUnknownDistSpecIsReportedWithTheKnownNames(@TempDir Path base) {
		DistClient client = new DistClient(base, url -> {
			throw new IOException("no network");
		});

		assertThatThrownBy(() -> client.installDist("ultralist")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ultralist")
			.hasMessageContaining("quicklisp, ultralisp");
	}

	@Test
	void updateDistDropsTheCachedIndexesSoTheNextLookupRefetches(@TempDir Path base) throws IOException {
		DistTestSupport.RecordingDownloader downloader = twoDists();
		DistClient client = new DistClient(base, downloader);

		client.ensureAvailable("mylib");
		client.updateDist(DistClient.QUICKLISP);
		client.ensureAvailable("mylib");

		// The indexes are re-read (a dist rebuilt every few minutes publishes releases
		// the cached index cannot name), while the already-extracted release is kept.
		assertThat(downloader.hits.get(DistTestSupport.DISTINFO_URL)).isEqualTo(2);
		assertThat(downloader.hits.get(DistTestSupport.SYSTEMS_URL)).isEqualTo(2);
		assertThat(downloader.hits.get(MYLIB_TARBALL_URL)).isEqualTo(1);
	}

	@Test
	void updatingADistThatIsNotInstalledIsReported(@TempDir Path base) {
		DistClient client = new DistClient(base, url -> {
			throw new IOException("no network");
		});

		assertThatThrownBy(() -> client.updateDist(DistClient.ULTRALISP)).isInstanceOf(IOException.class)
			.hasMessageContaining("ultralisp")
			.hasMessageContaining("quicklisp");
	}

}
