package am.ik.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The git fetcher against repositories created on disk by the test (a {@code file://}
 * URL; nothing reaches the network).
 */
class GitFetcherTest {

	private static final String NO_GIT = "rontolisp-test-no-such-git";

	/** A source repository the test commits into. */
	private record Source(Path dir) {

		String url() {
			return this.dir.toUri().toString();
		}

		static Source create(Path dir) throws IOException {
			Files.createDirectories(dir);
			git(dir, "init", "--quiet", "--initial-branch=main");
			return new Source(dir);
		}

		String commit(Map<String, String> files) throws IOException {
			for (Map.Entry<String, String> file : files.entrySet()) {
				Path path = this.dir.resolve(file.getKey());
				Files.createDirectories(path.getParent());
				Files.writeString(path, file.getValue());
			}
			git(this.dir, "add", "--all");
			git(this.dir, "commit", "--quiet", "--allow-empty", "--message=commit");
			return git(this.dir, "rev-parse", "HEAD").strip();
		}

		void tag(String name, String sha) throws IOException {
			git(this.dir, "tag", "--force", "--annotate", "--message=" + name, name, sha);
		}

	}

	private static String git(Path dir, String... args) throws IOException {
		List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=test", "-c",
				"user.email=test@example.com", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"));
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", dir.resolve(".no-global-config").toString());
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		try {
			if (process.waitFor() != 0) {
				throw new IOException("git " + String.join(" ", args) + " failed: " + output);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException(ex);
		}
		return output;
	}

	private static GitFetcher fetcher(Path area) {
		return new GitFetcher(area, GitFetcher.DEFAULT_EXECUTABLE);
	}

	private static GitCoordinate at(Source source, String sha) {
		return GitCoordinate.builder().url(source.url()).sha(sha).build();
	}

	private static List<String> listTree(Path dir) throws IOException {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).map(path -> dir.relativize(path).toString()).sorted().toList();
		}
	}

	@Test
	void aCommitIsCheckedOutAsItsTreeWithoutGitMetadata(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String first = source.commit(Map.of("a.txt", "one\r\n"));
		source.commit(Map.of("a.txt", "two", "sub/deps.edn", "{}"));

		Path tree = fetcher(tmp.resolve("area")).fetch(at(source, first));

		assertThat(tree).isEqualTo(tmp.resolve("area/libs/" + GitFetcher.cacheKey(source.url()) + "/" + first));
		assertThat(listTree(tree)).containsExactly("a.txt");
		assertThat(Files.readString(tree.resolve("a.txt"))).isEqualTo("one\r\n");
	}

	@Test
	void theRootAnswersASubDirectoryOfTheCheckout(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a", "modules/core/deps.edn", "{:paths [\"src\"]}"));

		Path dir = fetcher(tmp.resolve("area"))
			.fetch(GitCoordinate.builder().url(source.url()).sha(sha.toUpperCase()).root("./modules//core/").build());

		assertThat(dir).isEqualTo(
				tmp.resolve("area/libs/" + GitFetcher.cacheKey(source.url()) + "/" + sha).resolve("modules/core"));
		assertThat(Files.readString(dir.resolve("deps.edn"))).isEqualTo("{:paths [\"src\"]}");
	}

	@Test
	void aRootThatIsNotADirectoryOfTheCheckoutIsRefused(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a"));
		GitCoordinate coordinate = GitCoordinate.builder().url(source.url()).sha(sha).root("missing").build();

		assertThatThrownBy(() -> fetcher(tmp.resolve("area")).fetch(coordinate)).isInstanceOf(IOException.class)
			.hasMessage("root directory missing is not a directory of " + source.url() + " at " + sha);
	}

	@Test
	void anInstalledCheckoutNeedsNoGit(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a"));
		Path installed = fetcher(tmp.resolve("area")).fetch(at(source, sha));

		Path again = new GitFetcher(tmp.resolve("area"), NO_GIT).fetch(at(source, sha));

		assertThat(again).isEqualTo(installed);
	}

	@Test
	void aCommitMadeAfterTheCloneIsFetched(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String first = source.commit(Map.of("a.txt", "one"));
		GitFetcher fetcher = fetcher(tmp.resolve("area"));
		fetcher.fetch(at(source, first));
		String second = source.commit(Map.of("a.txt", "two"));

		Path tree = fetcher.fetch(at(source, second));

		assertThat(Files.readString(tree.resolve("a.txt"))).isEqualTo("two");
		assertThat(Files.readString(fetcher.fetch(at(source, first)).resolve("a.txt"))).isEqualTo("one");
	}

	@Test
	void aCommitTheRepositoryDoesNotHaveIsRefusedAndInstallsNothing(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		source.commit(Map.of("a.txt", "a"));
		String absent = "0123456789abcdef0123456789abcdef01234567";

		assertThatThrownBy(() -> fetcher(tmp.resolve("area")).fetch(at(source, absent))).isInstanceOf(IOException.class)
			.hasMessage("commit " + absent + " not found in " + source.url());
		assertThat(tmp.resolve("area/libs/" + GitFetcher.cacheKey(source.url()) + "/" + absent)).doesNotExist();
	}

	@Test
	void aTagNamingTheCommitIsAccepted(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a"));
		source.tag("v1.0", sha);

		Path tree = fetcher(tmp.resolve("area"))
			.fetch(GitCoordinate.builder().url(source.url()).sha(sha).tag("v1.0").build());

		assertThat(Files.readString(tree.resolve("a.txt"))).isEqualTo("a");
	}

	@Test
	void aTagCreatedAfterTheCheckoutIsFetchedAndChecked(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a"));
		GitFetcher fetcher = fetcher(tmp.resolve("area"));
		Path installed = fetcher.fetch(at(source, sha));
		source.tag("v2", sha);

		Path tagged = fetcher.fetch(GitCoordinate.builder().url(source.url()).sha(sha).tag("v2").build());

		assertThat(tagged).isEqualTo(installed);
	}

	@Test
	void aTagNamingAnotherCommitIsRefused(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String first = source.commit(Map.of("a.txt", "one"));
		String second = source.commit(Map.of("a.txt", "two"));
		source.tag("v1.0", first);
		GitCoordinate coordinate = GitCoordinate.builder().url(source.url()).sha(second).tag("v1.0").build();

		assertThatThrownBy(() -> fetcher(tmp.resolve("area")).fetch(coordinate)).isInstanceOf(IOException.class)
			.hasMessage("tag v1.0 names commit " + first + ", not " + second + ", in " + source.url());
	}

	@Test
	void aTagMovedUpstreamIsCheckedAgainstItsNewCommit(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String first = source.commit(Map.of("a.txt", "one"));
		source.tag("latest", first);
		GitFetcher fetcher = fetcher(tmp.resolve("area"));
		fetcher.fetch(GitCoordinate.builder().url(source.url()).sha(first).tag("latest").build());
		String second = source.commit(Map.of("a.txt", "two"));
		source.tag("latest", second);

		Path tree = fetcher.fetch(GitCoordinate.builder().url(source.url()).sha(second).tag("latest").build());

		assertThat(Files.readString(tree.resolve("a.txt"))).isEqualTo("two");
	}

	@Test
	void anUnknownTagIsRefused(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a"));
		GitCoordinate coordinate = GitCoordinate.builder().url(source.url()).sha(sha).tag("v9").build();

		assertThatThrownBy(() -> fetcher(tmp.resolve("area")).fetch(coordinate)).isInstanceOf(IOException.class)
			.hasMessage("tag v9 not found in " + source.url());
	}

	@Test
	void withoutGitAFetchIsRefusedByName(@TempDir Path tmp) {
		GitCoordinate coordinate = GitCoordinate.builder()
			.url("https://github.com/user/repo.git")
			.sha("0123456789abcdef0123456789abcdef01234567")
			.build();

		assertThatThrownBy(() -> new GitFetcher(tmp.resolve("area"), NO_GIT).fetch(coordinate))
			.isInstanceOf(IOException.class)
			.hasMessageStartingWith("the git command '" + NO_GIT + "' cannot be run (is git installed and on PATH?): ");
	}

	@Test
	void concurrentFetchesOfOneCommitShareOneCheckout(@TempDir Path tmp) throws Exception {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a"));
		GitFetcher fetcher = fetcher(tmp.resolve("area"));
		Callable<Path> fetch = () -> fetcher.fetch(at(source, sha));
		List<Path> trees = new ArrayList<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
			List<Future<Path>> futures = pool.invokeAll(List.of(fetch, fetch, fetch, fetch));
			for (Future<Path> future : futures) {
				trees.add(future.get());
			}
		}

		assertThat(trees).containsOnly(trees.get(0));
		assertThat(listTree(trees.get(0))).containsExactly("a.txt");
		try (Stream<Path> entries = Files.list(trees.get(0).getParent())) {
			assertThat(entries.map(path -> path.getFileName().toString())).containsExactly(sha);
		}
	}

	@Test
	void aShortShaIsRefused() {
		assertThatThrownBy(() -> GitCoordinate.builder().url("https://github.com/user/repo.git").sha("0123abc").build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a full commit sha (40 hex digits; a short sha is refused): '0123abc' for "
					+ "https://github.com/user/repo.git");
	}

	@Test
	void anArgumentThatGitWouldReadAsAnOptionIsRefused() {
		String sha = "0123456789abcdef0123456789abcdef01234567";

		assertThatThrownBy(() -> GitCoordinate.builder().url("--upload-pack=touch x").sha(sha).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a git repository URL: '--upload-pack=touch x'");
		assertThatThrownBy(() -> GitCoordinate.builder().url("https://h/r.git").sha(sha).tag("--all").build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a git tag name: '--all' for https://h/r.git");
	}

	@Test
	void aRootLeavingTheCheckoutIsRefused() {
		String sha = "0123456789abcdef0123456789abcdef01234567";

		assertThatThrownBy(() -> GitCoordinate.builder().url("https://h/r.git").sha(sha).root("a/../../b").build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("root directory leaves the checkout: 'a/../../b' for https://h/r.git");
		assertThatThrownBy(() -> GitCoordinate.builder().url("https://h/r.git").sha(sha).root("/etc").build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a relative root directory: '/etc' for https://h/r.git");
		assertThat(GitCoordinate.builder().url("https://h/r.git").sha(sha).root("./").build().root()).isNull();
	}

	@Test
	void oneRepositoryOverAnyTransportSharesAKeyAndTwoRepositoriesNever() {
		String key = GitFetcher.cacheKey("https://github.com/user/repo.git");

		assertThat(key).matches("github\\.com_user_repo-[0-9a-f]{16}");
		assertThat(GitFetcher.cacheKey("git@github.com:user/repo")).isEqualTo(key);
		assertThat(GitFetcher.cacheKey("ssh://git@GitHub.com/user/repo.git/")).isEqualTo(key);
		assertThat(GitFetcher.cacheKey("https://github.com/user/repo_")).isNotEqualTo(key);
		assertThat(GitFetcher.cacheKey("https://github.com/user_repo")).isNotEqualTo(key);
		assertThat(GitFetcher.cacheKey("file:///srv/git/../repo.git")).startsWith("_srv_git_.._repo-");
	}

	@Test
	void aRevisionResolvesToItsFullShaFetchingOnlyWhatTheCloneLacks(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String first = source.commit(Map.of("a.txt", "one"));
		source.tag("v1", first);
		GitFetcher fetcher = fetcher(tmp.resolve("area"));

		assertThat(fetcher.resolve(source.url(), "v1")).isEqualTo(first);
		assertThat(fetcher.resolve(source.url(), first.substring(0, 7))).isEqualTo(first);
		assertThat(fetcher.resolve(source.url(), first)).isEqualTo(first);
		// a commit and a tag made after the clone
		String second = source.commit(Map.of("a.txt", "two"));
		source.tag("v2", second);
		assertThat(fetcher.resolve(source.url(), "v2")).isEqualTo(second);
		assertThat(fetcher.resolve(source.url(), second.substring(0, 8))).isEqualTo(second);
		assertThat(fetcher.resolve(source.url(), "v9")).isNull();
		assertThat(fetcher.resolve(source.url(), "0123456789012345678901234567890123456789")).isNull();
	}

	@Test
	void aTagIsLookedUpInTheCloneThenFetched(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a"));
		source.tag("v1", sha);
		GitFetcher fetcher = fetcher(tmp.resolve("area"));

		assertThat(fetcher.hasTag(source.url(), "v1")).isTrue();
		assertThat(fetcher.hasTag(source.url(), "v2")).isFalse();
		source.tag("v2", sha);
		assertThat(fetcher.hasTag(source.url(), "v2")).isTrue();
	}

	@Test
	void ofTwoCommitsTheDescendantIsTheNewerAndUnrelatedOnesHaveNone(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String first = source.commit(Map.of("a.txt", "one"));
		String second = source.commit(Map.of("a.txt", "two"));
		git(source.dir(), "checkout", "--quiet", "--orphan", "other");
		String unrelated = source.commit(Map.of("b.txt", "b"));
		GitFetcher fetcher = fetcher(tmp.resolve("area"));

		assertThat(fetcher.descendant(source.url(), first, second)).isEqualTo(second);
		assertThat(fetcher.descendant(source.url(), second, first)).isEqualTo(second);
		assertThat(fetcher.descendant(source.url(), first, first)).isEqualTo(first);
		assertThat(fetcher.descendant(source.url(), second, unrelated)).isNull();
		assertThat(fetcher.descendant(source.url(), first, "0123456789012345678901234567890123456789")).isNull();
	}

	@Test
	void aCheckoutOfACommitTheRepositoryLacksIsNoneAndInstallsNothing(@TempDir Path tmp) throws IOException {
		Source source = Source.create(tmp.resolve("src"));
		String sha = source.commit(Map.of("a.txt", "a"));
		GitFetcher fetcher = fetcher(tmp.resolve("area"));
		String absent = "0123456789abcdef0123456789abcdef01234567";

		assertThat(fetcher.checkout(source.url(), sha)).isEqualTo(fetcher.fetch(at(source, sha)));
		assertThat(fetcher.checkout(source.url(), absent)).isNull();
		assertThat(tmp.resolve("area/libs/" + GitFetcher.cacheKey(source.url()) + "/" + absent)).doesNotExist();
	}

	@Test
	void aRevisionGitWouldReadAsAnOptionIsRefused(@TempDir Path tmp) {
		GitFetcher fetcher = fetcher(tmp.resolve("area"));

		assertThatThrownBy(() -> fetcher.resolve("https://h/r.git", "--all"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a git revision name: '--all' for https://h/r.git");
		assertThatThrownBy(() -> fetcher.hasTag("--upload-pack=x", "v1")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a git repository URL: '--upload-pack=x'");
		assertThatThrownBy(() -> fetcher.descendant("https://h/r.git", "abc", "abc"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("not a full commit sha (40 hex digits; a short sha is refused): 'abc' for https://h/r.git");
	}

}
