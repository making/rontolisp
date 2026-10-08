package am.ik.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Installs into a cache whose entries are marked installed by EXISTING: a consumer reuses
 * any entry it finds, so an entry must never be seen half-written -- not after a failed
 * or crashed install, and not by a second process installing the same entry at once.
 *
 * <p>
 * A directory is written into a private staging directory beside its target (same parent,
 * so the same file system) and renamed into place in one step; a file goes through a
 * temporary file and an atomic rename. Losing the rename to another installer means using
 * the winner's entry: it is complete by the same argument.
 */
public final class AtomicInstall {

	/**
	 * The name prefix of a staging directory. The leading dot keeps it apart from every
	 * entry name a consumer derives from an index.
	 */
	public static final String STAGING_PREFIX = ".staging-";

	private AtomicInstall() {
	}

	/**
	 * Writes a tree into a staging directory and answers the directory in it that becomes
	 * the installed entry.
	 */
	@FunctionalInterface
	public interface TreeWriter {

		/**
		 * Writes the tree.
		 * @param staging an empty private directory beside the target
		 * @return the finished directory, inside {@code staging} (or {@code staging}
		 * itself)
		 * @throws IOException if the tree cannot be written; nothing is installed
		 */
		Path write(Path staging) throws IOException;

	}

	/**
	 * Installs a directory at {@code target} unless one is already there. The writer
	 * fills a fresh staging directory; the directory it answers is renamed to
	 * {@code target}. A writer failure leaves no {@code target} and no staging directory.
	 * @param target the entry to install
	 * @param writer writes the entry's tree
	 * @return {@code target}
	 * @throws IOException if writing or renaming fails and no entry is installed
	 */
	public static Path installDirectory(Path target, TreeWriter writer) throws IOException {
		if (Files.isDirectory(target)) {
			return target;
		}
		Path parent = Objects.requireNonNull(target.toAbsolutePath().getParent());
		Files.createDirectories(parent);
		Path staging = Files.createTempDirectory(parent, STAGING_PREFIX);
		try {
			Path finished = writer.write(staging).toAbsolutePath().normalize();
			if (!finished.startsWith(staging.toAbsolutePath().normalize()) || !Files.isDirectory(finished)) {
				throw new IOException("staged tree " + finished + " is not a directory inside " + staging);
			}
			try {
				Files.move(finished, target, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException ex) {
				// rename(2) onto an existing non-empty directory fails; which exception
				// that surfaces as differs by platform, so the answer is the directory.
				if (!Files.isDirectory(target)) {
					throw ex;
				}
			}
		}
		finally {
			deleteRecursively(staging);
		}
		return target;
	}

	/**
	 * Writes {@code bytes} to {@code target} through a temporary file in the same
	 * directory and an atomic rename, so a concurrent reader sees the old file, no file,
	 * or the whole new one -- never a prefix of it.
	 * @param target the file to write
	 * @param bytes its content
	 * @throws IOException if the file cannot be written
	 */
	public static void writeFile(Path target, byte[] bytes) throws IOException {
		Path dir = Objects.requireNonNull(target.toAbsolutePath().getParent());
		Files.createDirectories(dir);
		Path temp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
		try {
			Files.write(temp, bytes);
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally {
			Files.deleteIfExists(temp);
		}
	}

	/**
	 * Deletes {@code dir} and everything under it, if it exists. A symbolic link is
	 * removed, never followed.
	 * @param dir the directory to delete
	 * @throws IOException if an entry cannot be deleted
	 */
	public static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

}
