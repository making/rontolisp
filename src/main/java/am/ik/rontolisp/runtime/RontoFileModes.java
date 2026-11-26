package am.ik.rontolisp.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What a host with permission bits answers of a path beside its {@code stat}: whether the
 * program may read, write or execute it ({@code %file-access}), the space of the
 * partition holding it ({@code %file-space}), its bits written ({@code %file-set-mode}),
 * a file or a directory made with bits ({@code %file-make}), the numbers and the names
 * {@code stat} gives beside the basic ones ({@code %file-unix}) and an owner and a group
 * given ({@code %file-set-owner}); and a hard link made ({@code %make-hard-link}). The
 * interpreter calls the {@code String} forms, the JVM backend the forms over rontolisp
 * values; no WASI host has the first six, so neither WASM backend compiles them, and both
 * make a hard link through the host's own call. Nothing here throws.
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

	/** The host does not allow the change ({@code EPERM}). */
	public static final int NOT_PERMITTED = 5;

	// The unix view's names %file-unix answers, in its order.
	private static final String UNIX = "unix:uid,gid,ino,dev,rdev,nlink,ctime,mode";

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
		if (why != null && why.equals("Operation not permitted")) {
			return NOT_PERMITTED;
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

	/**
	 * What the host's {@code stat} (or {@code lstat}) says of a path beyond the basic
	 * view, through the unix view: its owner's and group's numbers, its inode, its
	 * device, the device it is, its links, the time its status last changed in
	 * nanoseconds and its whole mode, then, where asked, the names of the owner and the
	 * group.
	 * @param path the path
	 * @param follow whether a link in the last name is followed
	 * @param names whether the owner and the group are named
	 * @return the eight {@code Long}s and the two {@code String}s where asked, or the
	 * {@code Integer} reason
	 */
	public static Object unix(String path, boolean follow, boolean names) {
		if (path.isEmpty()) {
			return MISSING;
		}
		try {
			LinkOption[] options = follow ? new LinkOption[0] : new LinkOption[] { LinkOption.NOFOLLOW_LINKS };
			Map<String, Object> read = Files.readAttributes(Path.of(path), names ? UNIX + ",owner,group" : UNIX,
					options);
			String[] keys = UNIX.substring(5).split(",");
			Object[] values = new Object[keys.length + (names ? 2 : 0)];
			for (int i = 0; i < keys.length; i++) {
				Object value = Objects.requireNonNull(read.get(keys[i]));
				values[i] = (value instanceof FileTime time) ? time.to(TimeUnit.NANOSECONDS)
						: ((Number) value).longValue();
			}
			if (names) {
				values[keys.length] = ((UserPrincipal) Objects.requireNonNull(read.get("owner"))).getName();
				values[keys.length + 1] = ((UserPrincipal) Objects.requireNonNull(read.get("group"))).getName();
			}
			return values;
		}
		catch (IOException | RuntimeException ex) {
			return why(ex);
		}
	}

	/**
	 * Gives a path an owner and a group by number ({@code chown}, or {@code lchown} where
	 * a link is not followed).
	 * @param path the path
	 * @param follow whether a link in the last name is followed
	 * @param uid the owner, -1 to leave it
	 * @param gid the group, -1 to leave it
	 * @return 0, or why not
	 */
	public static int setOwner(String path, boolean follow, int uid, int gid) {
		if (path.isEmpty()) {
			return MISSING;
		}
		try {
			LinkOption[] options = follow ? new LinkOption[0] : new LinkOption[] { LinkOption.NOFOLLOW_LINKS };
			if (uid != -1) {
				Files.setAttribute(Path.of(path), "unix:uid", uid, options);
			}
			if (gid != -1) {
				Files.setAttribute(Path.of(path), "unix:gid", gid, options);
			}
			return 0;
		}
		catch (IOException | RuntimeException ex) {
			return why(ex);
		}
	}

	/**
	 * Makes a hard link at a path to what a target names, a link in its last name not
	 * followed ({@code link(2)}).
	 * @param target what the link is made to
	 * @param path where the link is made
	 * @return 0, or why not as WASI preview 1 numbers it ({@code errno}), which the
	 * {@code %make-hard-link} of both WASM backends answers as it is
	 */
	public static int hardLink(String target, String path) {
		try {
			Files.createLink(Path.of(path), Path.of(target));
			return 0;
		}
		catch (IOException | RuntimeException ex) {
			return errno(ex);
		}
	}

	// The preview 1 errno of why an operation failed: the exception's class, or the
	// words a JDK gives after the paths.
	private static int errno(Exception failed) {
		if (failed instanceof NoSuchFileException || failed instanceof InvalidPathException) {
			return 44;
		}
		if (failed instanceof java.nio.file.FileAlreadyExistsException) {
			return 20;
		}
		if (failed instanceof AccessDeniedException) {
			return 2;
		}
		String why = failed instanceof FileSystemException named ? named.getReason() : null;
		if (why == null) {
			return 29;
		}
		if (why.startsWith("Too many levels of symbolic links")) {
			return 32;
		}
		return switch (why) {
			case "Operation not permitted" -> 63;
			case "Not a directory" -> 54;
			case "Invalid cross-device link" -> 75;
			case "File name too long" -> 37;
			default -> 29;
		};
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

	/**
	 * The JVM backend's {@code %file-unix}.
	 * @param path the path, a quoted string
	 * @param follow nil ({@code null}) where a link is not followed
	 * @param names nil ({@code null}) where the owner and the group are not named
	 * @return the list of {@code Long}s and quoted strings, or why not, a {@code Long}
	 */
	// nil is null in the compiled representation, a cons an Object[] of its car and cdr.
	@SuppressWarnings("NullAway")
	public static Object fileUnix(Object path, Object follow, Object names) {
		Object answer = unix(text(path), follow != null, names != null);
		if (!(answer instanceof Object[] values)) {
			return ((Number) answer).longValue();
		}
		Object list = null;
		for (int i = values.length - 1; i >= 0; i--) {
			Object value = values[i] instanceof String name ? "\"" + name + "\"" : values[i];
			list = new Object[] { value, list };
		}
		return list;
	}

	/**
	 * The JVM backend's {@code %file-set-owner}.
	 * @param path the path, a quoted string
	 * @param follow nil ({@code null}) where a link is not followed
	 * @param uid a {@code Long}
	 * @param gid a {@code Long}
	 * @return 0 or why not, a {@code Long}
	 */
	public static Object fileSetOwner(Object path, Object follow, Object uid, Object gid) {
		return (long) setOwner(text(path), follow != null, ((Number) uid).intValue(), ((Number) gid).intValue());
	}

	/**
	 * The JVM backend's {@code %make-hard-link}.
	 * @param target the target, a quoted string
	 * @param path the path, a quoted string
	 * @return 0 or why not, a {@code Long}
	 */
	public static Object fileHardLink(Object target, Object path) {
		return (long) hardLink(text(target), text(path));
	}

	// A rontolisp string value carries its quotes.
	private static String text(Object value) {
		String quoted = (String) value;
		return quoted.substring(1, quoted.length() - 1);
	}

}
