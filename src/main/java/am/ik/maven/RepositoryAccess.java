package am.ik.maven;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import am.ik.artifact.AtomicInstall;
import am.ik.artifact.Checksum;
import am.ik.artifact.Downloader;
import am.ik.artifact.HttpStatusException;
import org.jspecify.annotations.Nullable;

/**
 * The local repository, in the {@code ~/.m2/repository} layout {@code mvn} and
 * {@code clj} share, filled from the remote repositories on demand. A file already there
 * is used as it is, whoever put it there. A missing one is looked up in each remote
 * repository in order: a {@code 404} moves on to the next, the first copy whose
 * {@code .sha1} matches is written into the local repository (the file, then its
 * {@code .sha1}, each by an atomic rename), and a copy that cannot be verified -- no
 * {@code .sha1}, a mismatch, a transfer failure -- is never written.
 */
final class RepositoryAccess {

	/**
	 * A Maven checksum file in the {@code digest  file} or {@code ALG(file)= digest}
	 * forms.
	 */
	private static final Pattern OPENSSL_FORM = Pattern.compile(".+= [0-9A-Fa-f]+");

	private final Path root;

	private final List<RemoteRepository> repositories;

	private final Downloader downloader;

	private final MavenSettings settings;

	RepositoryAccess(Path localRepository, List<RemoteRepository> repositories, Downloader downloader,
			MavenSettings settings) {
		this.root = localRepository.toAbsolutePath().normalize();
		this.repositories = List.copyOf(repositories);
		this.downloader = downloader;
		this.settings = settings;
	}

	Path root() {
		return this.root;
	}

	List<RemoteRepository> repositories() {
		return this.repositories;
	}

	/**
	 * Returns a file of the local repository, fetching it when it is not there yet.
	 * @param artifact the artifact
	 * @return its local path, or {@code null} when no repository has it
	 * @throws MavenResolutionException if a repository fails, or the settings refuse the
	 * access
	 */
	@Nullable Path fetch(Artifact artifact) throws MavenResolutionException {
		Path target = localPath(artifact);
		if (target == null) {
			// No repository layout holds a path that leaves its root.
			return null;
		}
		if (Files.isRegularFile(target)) {
			return target;
		}
		if (this.settings.offline()) {
			throw new MavenResolutionException("offline (settings.xml <offline>true</offline>): " + artifact
					+ " is not in the local repository " + this.root);
		}
		List<String> failures = new ArrayList<>();
		for (RemoteRepository repository : this.repositories) {
			this.settings.checkRemoteAccess(repository);
			String failure = fetchFrom(repository, artifact, target);
			if (failure == null) {
				return target;
			}
			if (!failure.isEmpty()) {
				failures.add(repository.id() + ": " + failure);
			}
		}
		if (!failures.isEmpty()) {
			throw new MavenResolutionException("cannot fetch " + artifact + " (" + String.join("; ", failures) + ")");
		}
		return null;
	}

	/**
	 * Reads a file of the local repository, fetching it first when needed.
	 * @param artifact the artifact
	 * @return its bytes, or {@code null} when no repository has it
	 * @throws MavenResolutionException if a repository fails or the local file cannot be
	 * read
	 */
	byte @Nullable [] read(Artifact artifact) throws MavenResolutionException {
		Path path = fetch(artifact);
		if (path == null) {
			return null;
		}
		try {
			return Files.readAllBytes(path);
		}
		catch (IOException ex) {
			throw new MavenResolutionException("cannot read " + path + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * Fetches from one repository.
	 * @return {@code null} once the verified file is in place, the empty string when the
	 * repository does not have it, else why the repository's copy was not taken
	 */
	private @Nullable String fetchFrom(RemoteRepository repository, Artifact artifact, Path target)
			throws MavenResolutionException {
		String path = artifact.path();
		byte[] bytes;
		try {
			bytes = get(repository, path);
		}
		catch (IOException ex) {
			return failure(repository, ex);
		}
		if (bytes == null) {
			return "";
		}
		byte[] checksumFile;
		try {
			checksumFile = get(repository, path + ".sha1");
		}
		catch (IOException ex) {
			return failure(repository, ex);
		}
		if (checksumFile == null) {
			return "publishes no " + path + ".sha1 to verify it against";
		}
		String expected = checksum(checksumFile);
		Checksum sha1;
		try {
			sha1 = Checksum.sha1(expected);
		}
		catch (IllegalArgumentException ex) {
			return path + ".sha1 holds no SHA-1 digest: '" + expected + "'";
		}
		try {
			sha1.verify(bytes, repository.resolve(path).toString());
		}
		catch (IOException ex) {
			return ex.getMessage();
		}
		try {
			AtomicInstall.writeFile(target, bytes);
			AtomicInstall.writeFile(target.resolveSibling(target.getFileName() + ".sha1"),
					sha1.hex().getBytes(StandardCharsets.US_ASCII));
		}
		catch (IOException ex) {
			throw new MavenResolutionException("cannot write " + target + ": " + ex.getMessage(), ex);
		}
		return null;
	}

	private String failure(RemoteRepository repository, IOException ex) {
		String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
		if (ex instanceof HttpStatusException status && (status.statusCode() == 401 || status.statusCode() == 403)) {
			return message + ": the repository asks for credentials, which are not sent"
					+ (this.settings.hasServer(repository.id())
							? " (settings.xml <server> '" + repository.id() + "' is not supported)" : "");
		}
		return message;
	}

	/**
	 * Reads one file of a repository.
	 * @return its bytes, or {@code null} when the repository has no such file
	 */
	private byte @Nullable [] get(RemoteRepository repository, String path) throws IOException {
		URI uri = repository.resolve(path);
		URI base = repository.resolve("");
		if (!uri.getPath().startsWith(base.getPath())) {
			return null;
		}
		if (repository.protocol().equals("file")) {
			Path file;
			try {
				file = Path.of(uri);
			}
			catch (IllegalArgumentException | FileSystemNotFoundException ex) {
				throw new IOException("not a file URL: " + uri, ex);
			}
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try {
				return Files.readAllBytes(file);
			}
			catch (NoSuchFileException ex) {
				return null;
			}
		}
		try {
			return this.downloader.get(uri.toString());
		}
		catch (HttpStatusException ex) {
			if (ex.isNotFound()) {
				return null;
			}
			throw ex;
		}
	}

	/**
	 * Maven's reading of a checksum file: the first non-blank line, the digest after the
	 * last space in the {@code ALG(file)= digest} form, else before the first space.
	 */
	static String checksum(byte[] file) {
		String text = new String(file, StandardCharsets.UTF_8);
		String line = "";
		for (String candidate : text.split("\n")) {
			if (!candidate.trim().isEmpty()) {
				line = candidate.trim();
				break;
			}
		}
		if (OPENSSL_FORM.matcher(line).matches()) {
			return line.substring(line.lastIndexOf(' ') + 1);
		}
		int space = line.indexOf(' ');
		return space < 0 ? line : line.substring(0, space);
	}

	/**
	 * The local path of an artifact, or {@code null} when its coordinates would leave the
	 * local repository.
	 */
	@Nullable Path localPath(Artifact artifact) {
		try {
			Path path = this.root.resolve(artifact.path()).normalize();
			return path.startsWith(this.root) && !path.equals(this.root) ? path : null;
		}
		catch (InvalidPathException ex) {
			return null;
		}
	}

}
