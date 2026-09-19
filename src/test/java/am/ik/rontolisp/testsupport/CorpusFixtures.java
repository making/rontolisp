package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The one tree the ci-spec corpus needs but no backend can build at run time, plus the
 * cleanup for whatever ELSE an in-process run of the corpus leaves behind.
 *
 * <p>
 * The {@code wild-pathnames} case walks a pathspec under {@code ./wpc-sub/} with a
 * {@code **} directory component: a bounded tree anchored to a directory the run owns, so
 * the walk enters exactly one directory and its answer cannot depend on what else the run
 * directory holds. A {@code :wild-inferiors} anchored at the run directory itself (a
 * {@code **} component in a pathspec starting at the working directory) instead reads
 * EVERY directory below the process working directory, which is the project root for the
 * in-process corpus runners -- on a box carrying agent checkouts under
 * {@code .claude/worktrees/} that is a walk of thousands of directories, and the case's
 * filter fixed the assertion, not the work.
 *
 * <p>
 * The tree is staged by the driver because neither WASM backend can create a directory
 * (the same reason {@code WasmLispCompilerIntegrationTest} builds its {@code wt} tree
 * with {@code mkdir} in the container). Every driver that RUNS the corpus must stage it
 * before the run: {@code CiSpecE2eTest} (the working directory is its {@code @TempDir})
 * and {@code JvmClassShakerCorpusTest} (the process working directory, the project root
 * -- which must also be cleaned).
 *
 * <p>
 * Beyond {@code wpc-sub}, dozens of other ci-spec cases write scratch files and even a
 * nested directory tree (the {@code filesystem-write-create-rename-delete-and-probe}
 * case's {@code w257/sub/dir/}) at relative paths, which resolve against the project root
 * for the same in-process runners. {@code JvmClassShakerCorpusTest} used to track those
 * names by hand in a constant list; it went stale every time a case was added that wrote
 * a new name, and even a hand-complete list could not have covered {@code w257/}, since
 * {@code Files.deleteIfExists} refuses a non-empty directory. {@link #snapshotTopLevel}
 * and {@link #removeNewEntries} replace the list: snapshot the run directory before the
 * corpus runs, then delete whatever is new afterward, file or directory, by name or not.
 */
public final class CorpusFixtures {

	/** The directory name the {@code wild-pathnames} case anchors its walk to. */
	public static final String WILD_PATHNAME_DIR = "wpc-sub";

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
	 * Removes the staged tree and its files. For drivers whose run directory is not
	 * itself disposable (the project root under the in-process corpus tests).
	 * @param runDir the directory the tree was staged into
	 * @throws IOException if the tree cannot be removed
	 */
	public static void removeWildPathnameTree(Path runDir) throws IOException {
		Path wpc = runDir.resolve(WILD_PATHNAME_DIR);
		Files.deleteIfExists(wpc.resolve("wpc-a.txt"));
		Files.deleteIfExists(wpc.resolve("wpc-b.txt"));
		Files.deleteIfExists(wpc);
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
	 * Records the top-level entry names already present under {@code runDir}, to pass to
	 * {@link #removeNewEntries} after an in-process corpus run.
	 * @param runDir the directory the corpus program is about to run in
	 * @return the entry names already there, before the run
	 * @throws IOException if the directory cannot be listed
	 */
	public static Set<String> snapshotTopLevel(Path runDir) throws IOException {
		try (Stream<Path> children = Files.list(runDir)) {
			return children.map(child -> child.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
		}
	}

	/**
	 * Deletes every top-level entry under {@code runDir} that was not present in
	 * {@code before} -- whatever the corpus run wrote at a relative path, by name or not,
	 * file or directory. Entries staged deliberately (such as
	 * {@link #stageWildPathnameTree}) must already be removed before calling this, or
	 * they are swept up too.
	 * @param runDir the directory the corpus program ran in
	 * @param before the snapshot {@link #snapshotTopLevel} took beforehand
	 * @throws IOException if an entry cannot be listed or removed
	 */
	public static void removeNewEntries(Path runDir, Set<String> before) throws IOException {
		try (Stream<Path> children = Files.list(runDir)) {
			for (Path child : children.toList()) {
				if (!before.contains(child.getFileName().toString())) {
					deleteRecursively(child);
				}
			}
		}
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (Files.isDirectory(path)) {
			try (Stream<Path> walk = Files.walk(path)) {
				for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(entry);
				}
			}
		}
		else {
			Files.deleteIfExists(path);
		}
	}

}
