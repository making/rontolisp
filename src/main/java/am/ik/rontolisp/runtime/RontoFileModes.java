package am.ik.rontolisp.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a host with permission bits answers of a path beside its {@code stat}: whether the
 * program may read, write or execute it ({@code %file-access}), the space of the
 * partition holding it ({@code %file-space}), its bits written ({@code %file-set-mode}),
 * and a file or a directory made with bits ({@code %file-make}). The interpreter calls
 * the {@code String} forms, the JVM backend the forms over rontolisp values; no WASI host
 * has these, so neither WASM backend compiles them. Nothing here throws.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside a compiled program that
 * asks ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoFileModes {

	/** Nothing is there, or something above the last name is no directory. */
	public static final int MISSING = 1;

	/** The host refused. */
	public static final int DENIED = 2;

	/** Too many symbolic links. */
	public static final int LOOP = 3;

	/** Another reason. */
	public static final int OTHER = 4;

	private RontoFileModes() {
	}

	/**
	 * Why a {@code java.nio.file} operation failed, as {@code %file-stat} says it: a JDK
	 * throws {@code NoSuchFileException} where the path is missing (and where a part of
	 * it is no directory, to a stat; an operation that opens the file says
	 * {@code Not a directory}), {@code AccessDeniedException} where the host refused, and
	 * words of its own after {@code ELOOP}'s.
	 * @param failed the exception
	 * @return the reason
	 */
	public static int why(Exception failed) {
		if (failed instanceof NoSuchFileException || failed instanceof InvalidPathException) {
			return MISSING;
		}
		if (failed instanceof AccessDeniedException) {
			return DENIED;
		}
		String why = failed instanceof FileSystemException named ? named.getReason() : null;
		if (why != null && why.equals("Not a directory")) {
			return MISSING;
		}
		if (why != null && why.startsWith("Too many levels of symbolic links")) {
			return LOOP;
		}
		return OTHER;
	}

	/**
	 * Whether the host lets the program read (4), write (2) or execute (1) what the path
	 * names, links followed: {@code access(2)}'s answer, which a JDK's {@code File} and
	 * {@code Files} give.
	 * @param path the path
	 * @param what 4, 2 or 1
	 * @return the answer; false for a path the host cannot be asked of
	 */
	public static boolean access(String path, int what) {
		try {
			Path at = Path.of(path);
			return what == 4 ? Files.isReadable(at) : what == 2 ? Files.isWritable(at) : Files.isExecutable(at);
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	 * The size of the partition holding the path (0), the bytes free in it (1) or the
	 * bytes this program may use of it (2).
	 * @param path the path
	 * @param which 0, 1 or 2
	 * @return the bytes; 0 where the path names nothing
	 */
	public static long space(String path, int which) {
		try {
			File at = new File(path);
			return which == 0 ? at.getTotalSpace() : which == 1 ? at.getFreeSpace() : at.getUsableSpace();
		}
		catch (RuntimeException ex) {
			return 0;
		}
	}

	/**
	 * Gives what the path names, links followed, the permission bits ({@code chmod}).
	 * @param path the path
	 * @param mode the bits, 07777
	 * @return 0, or why not
	 */
	public static int setMode(String path, int mode) {
		try {
			Files.setAttribute(Path.of(path), "unix:mode", mode & 07777);
			return 0;
		}
		catch (IOException | RuntimeException ex) {
			return why(ex);
		}
	}

	/**
	 * Makes a file or a directory with the permission bits less the umask, as
	 * {@code open(2)} with {@code O_CREAT} and {@code O_EXCL}, and {@code mkdir(2)}, make
	 * one.
	 * @param path the path
	 * @param mode the bits, 0777
	 * @param directory whether a directory is made
	 * @return 0, or why not: {@link #OTHER} for anything there
	 */
	public static int make(String path, int mode, boolean directory) {
		try {
			FileAttribute<Set<PosixFilePermission>> bits = PosixFilePermissions.asFileAttribute(permissions(mode));
			if (directory) {
				Files.createDirectory(Path.of(path), bits);
			}
			else {
				Files.createFile(Path.of(path), bits);
			}
			return 0;
		}
		catch (IOException | RuntimeException ex) {
			return why(ex);
		}
	}

	// The permissions of the low nine bits: OWNER_READ is 0400, OTHERS_EXECUTE 01.
	private static Set<PosixFilePermission> permissions(int mode) {
		Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
		PosixFilePermission[] all = PosixFilePermission.values();
		for (int i = 0; i < all.length; i++) {
			if ((mode & (0400 >> i)) != 0) {
				set.add(all[i]);
			}
		}
		return set;
	}

	/**
	 * The JVM backend's {@code %file-access}.
	 * @param path the path, a quoted string
	 * @param what a {@code Long}
	 * @return {@code "T"} or {@code null}
	 */
	// nil is null in the compiled representation; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static Object fileAccess(Object path, Object what) {
		return access(text(path), ((Number) what).intValue()) ? "T" : null;
	}

	/**
	 * The JVM backend's {@code %file-space}.
	 * @param path the path, a quoted string
	 * @param which a {@code Long}
	 * @return the bytes, a {@code Long}
	 */
	public static Object fileSpace(Object path, Object which) {
		return space(text(path), ((Number) which).intValue());
	}

	/**
	 * The JVM backend's {@code %file-set-mode}.
	 * @param path the path, a quoted string
	 * @param mode a {@code Long}
	 * @return 0 or why not, a {@code Long}
	 */
	public static Object fileSetMode(Object path, Object mode) {
		return (long) setMode(text(path), ((Number) mode).intValue());
	}

	/**
	 * The JVM backend's {@code %file-make}.
	 * @param path the path, a quoted string
	 * @param mode a {@code Long}
	 * @param directory nil ({@code null}) for a file
	 * @return 0 or why not, a {@code Long}
	 */
	public static Object fileMake(Object path, Object mode, Object directory) {
		return (long) make(text(path), ((Number) mode).intValue(), directory != null);
	}

	// A rontolisp string value carries its quotes.
	private static String text(Object value) {
		String quoted = (String) value;
		return quoted.substring(1, quoted.length() - 1);
	}

}
