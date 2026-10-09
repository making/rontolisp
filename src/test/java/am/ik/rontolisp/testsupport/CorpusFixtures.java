package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The trees the ci-spec corpus needs but no backend can build at run time -- the
 * wild-pathname walk's and the symbolic links {@code truename} resolves -- plus the
 * {@code .lnk} fixture in the same position.
 *
 * <p>
 * The {@code wild-pathnames} case walks a pathspec under {@code ./wpc-sub/} with a
 * {@code **} directory component: a bounded tree anchored to a directory the run owns, so
 * the walk enters exactly one directory and its answer cannot depend on what else the run
 * directory holds. A {@code :wild-inferiors} anchored at the run directory itself (a
 * {@code **} component in a pathspec starting at the working directory) instead reads
 * EVERY directory below the process working directory -- on a box carrying agent
 * checkouts under {@code .claude/worktrees/} that is a walk of thousands of directories,
 * and the case's filter fixed the assertion, not the work.
 *
 * <p>
 * The tree is staged by the driver because neither WASM backend can create a directory
 * (the same reason {@code WasmLispCompilerIntegrationTest} builds its {@code wt} tree
 * with {@code mkdir} in the container). <b>Every driver that RUNS the corpus stages it
 * into a working directory THAT RUN OWNS</b>, because dozens of other cases write scratch
 * files and even a nested tree (the
 * {@code filesystem-write-create-rename-delete-and-probe} case's {@code w257/sub/dir/})
 * at relative paths, and one of them reads a directory listing back. A run directory
 * shared with anything else -- the second surefire fork, another build on the box, an
 * orphaned one -- makes the program's OUTPUT depend on that other party
 * ({@code .kb/test-execution.md}, "A test that runs a program in the project root").
 * {@code CiSpecE2eTest} runs every leg in its {@code @TempDir};
 * {@code JvmDeadMethodEliminationCorpusTest} runs the program in a subprocess for the
 * same reason, one fresh directory per run. Neither has anything to clean up afterwards,
 * which is why the snapshot-and-delete pair that used to live here is gone.
 */
public final class CorpusFixtures {

	/** The directory name the {@code wild-pathnames} case anchors its walk to. */
	public static final String WILD_PATHNAME_DIR = "wpc-sub";

	/** The directory the {@code truename-resolves-symbolic-links} case reads. */
	public static final String SYMLINK_DIR = "e95-links";

	private CorpusFixtures() {
	}

	/**
	 * Stages the {@code wild-pathnames} tree under the directory the corpus program will
	 * run in. Idempotent: safe to call for every leg of a driver.
	 * @param runDir the working directory the corpus program runs with
	 * @throws IOException if the tree cannot be staged
	 */
	public static void stageWildPathnameTree(Path runDir) throws IOException {
		Path wpc = runDir.resolve(WILD_PATHNAME_DIR);
		Files.createDirectories(wpc);
		Files.writeString(wpc.resolve("wpc-a.txt"), "a\n");
		Files.writeString(wpc.resolve("wpc-b.txt"), "b\n");
	}

	/**
	 * The classpath resource holding the {@code .lnk} fixture the uiop/os parsers
	 * consume.
	 */
	private static final String LNK_FIXTURE_RESOURCE = "/lnk/sample.lnk";

	/** The file name the {@code uiop-os-host-identity} ci-spec case parses. */
	public static final String LNK_FIXTURE_NAME = "x.lnk";

	/**
	 * The filesystem path of the {@code .lnk} fixture resource, for a unit test that
	 * passes a pathname straight into {@code uiop:parse-windows-shortcut}.
	 * @return the fixture's filesystem path
	 * @throws IOException if the resource cannot be located on the filesystem
	 */
	public static Path lnkFixturePath() throws IOException {
		var url = CorpusFixtures.class.getResource(LNK_FIXTURE_RESOURCE);
		if (url == null) {
			throw new IOException("missing .lnk fixture resource " + LNK_FIXTURE_RESOURCE);
		}
		try {
			return Path.of(url.toURI());
		}
		catch (java.net.URISyntaxException e) {
			throw new IOException("bad fixture URI " + url, e);
		}
	}

	/**
	 * Stages the {@code .lnk} fixture under the directory the corpus program runs in,
	 * named {@link #LNK_FIXTURE_NAME} so the {@code uiop-os-host-identity} case can parse
	 * it by that relative name. Idempotent.
	 * @param runDir the working directory the corpus program runs with
	 * @throws IOException if the fixture cannot be staged
	 */
	public static void stageLnkFixture(Path runDir) throws IOException {
		Files.copy(lnkFixturePath(), runDir.resolve(LNK_FIXTURE_NAME),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Stages the {@code truename-resolves-symbolic-links} tree: a file, a directory and
	 * three symbolic links, each target RELATIVE (a WASI host follows no absolute one).
	 * No backend creates a link, so the driver does. Idempotent: an existing tree is left
	 * as it is.
	 * @param runDir the working directory the corpus program runs with
	 * @throws IOException if the tree cannot be staged
	 */
	public static void stageSymlinkTree(Path runDir) throws IOException {
		Path dir = runDir.resolve(SYMLINK_DIR);
		if (Files.isDirectory(dir)) {
			return;
		}
		Files.createDirectories(dir.resolve("d"));
		Files.writeString(dir.resolve("a.txt"), "a\n");
		Files.writeString(dir.resolve("d/f.txt"), "f\n");
		Files.createSymbolicLink(dir.resolve("in"), Path.of("a.txt"));
		Files.createSymbolicLink(dir.resolve("dl"), Path.of("d"));
		Files.createSymbolicLink(dir.resolve("up"), Path.of("../" + SYMLINK_DIR + "/d"));
	}

	/**
	 * Records the top-level entry names present under {@code runDir}, for a test that
	 * asserts a directory it does not own came out as it went in.
	 * @param runDir the directory to record
	 * @return its entry names
	 * @throws IOException if the directory cannot be listed
	 */
	public static Set<String> snapshotTopLevel(Path runDir) throws IOException {
		try (Stream<Path> children = Files.list(runDir)) {
			return children.map(child -> child.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
		}
	}

}
