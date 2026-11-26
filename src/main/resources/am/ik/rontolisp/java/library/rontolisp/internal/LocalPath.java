// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

// A path of the host's file system: its text with one slash for each run of them and none
// at the end but the root's, and the names between the slashes. The empty path has one
// name, itself, that its iterator does not give; the root has none. What each member
// answers is a JDK's on a Unix host, read off its answers: which path is a name of which,
// what a path starts and ends with, how two resolve and relativize, what normalize keeps.
public final class LocalPath implements Path {

    private final String text;

    // the names, made when first asked for
    private String[] names;

    private LocalPath(String text) {
        this.text = text;
    }

    public static Path of(String first, String... more) {
        StringBuilder joined = new StringBuilder(Objects.requireNonNull(first));
        for (String part : more) {
            if (!part.isEmpty()) {
                if (joined.length() > 0) {
                    joined.append('/');
                }
                joined.append(part);
            }
        }
        String input = joined.toString();
        if (input.indexOf('\u0000') >= 0) {
            throw new InvalidPathException(input, "Nul character not allowed");
        }
        return new LocalPath(Disk.normal(input));
    }

    // The path a file: URI names, its escapes read as UTF-8; what it refuses is a JDK's,
    // in its order.
    public static Path of(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("Missing scheme");
        }
        if (!scheme.equalsIgnoreCase("file")) {
            throw new FileSystemNotFoundException("Provider \"" + scheme + "\" not installed");
        }
        String path = pathOf(uri);
        if (path.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Nul character not allowed");
        }
        return new LocalPath(Disk.normal(path));
    }

    // The path of a hierarchical URI with no authority, query or fragment.
    public static String pathOf(URI uri) {
        if (uri.isOpaque()) {
            throw new IllegalArgumentException("URI is not hierarchical");
        }
        if (uri.getRawAuthority() != null) {
            throw new IllegalArgumentException("URI has an authority component");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("URI has a fragment component");
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("URI has a query component");
        }
        String path = uri.getPath();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("URI path component is empty");
        }
        return path;
    }

    // A path as a URI's path: what a URI's path may hold as it is, the rest as % and two
    // hexadecimal digits of each of its UTF-8 bytes; a character past ASCII is kept
    // where OTHERS is, and it is no control or space character.
    public static String quoted(String path, boolean others) {
        StringBuilder out = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            if (literal(path.charAt(i), others)) {
                out.append(path.charAt(i++));
                continue;
            }
            // a run, so that the two halves of a pair are one character's bytes
            int start = i;
            while (i < path.length() && !literal(path.charAt(i), others)) {
                i++;
            }
            for (byte b : path.substring(start, i).getBytes(StandardCharsets.UTF_8)) {
                out.append('%').append("0123456789ABCDEF".charAt((b >> 4) & 15)).append("0123456789ABCDEF".charAt(b & 15));
            }
        }
        return out.toString();
    }

    private static boolean literal(char c, boolean others) {
        return c < 128 ? (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-_.!~*'();:@&=+$,/".indexOf(c) >= 0 : others && !Character.isISOControl(c) && !Character.isSpaceChar(c);
    }

    // The path of a path; one made by another provider is none here.
    private static LocalPath local(Path path) {
        return (LocalPath) Objects.requireNonNull(path);
    }

    private String[] names() {
        if (names == null) {
            if (text.isEmpty()) {
                names = new String[] { "" };
            }
            else {
                String rest = text.charAt(0) == '/' ? text.substring(1) : text;
                names = rest.isEmpty() ? new String[0] : rest.split("/");
            }
        }
        return names;
    }

    private static Path joined(boolean absolute, String[] names, int from, int to) {
        StringBuilder text = new StringBuilder(absolute ? "/" : "");
        for (int i = from; i < to; i++) {
            if (i > from) {
                text.append('/');
            }
            text.append(names[i]);
        }
        return new LocalPath(text.toString());
    }

    // The path the host is asked of: the empty path is the working directory.
    public String host() {
        return text.isEmpty() ? "." : text;
    }

    public boolean isAbsolute() {
        return text.startsWith("/");
    }

    public Path getRoot() {
        return isAbsolute() ? new LocalPath("/") : null;
    }

    public Path getFileName() {
        String[] all = names();
        if (all.length == 0) {
            return null;
        }
        if (all.length == 1 && !isAbsolute()) {
            return this;
        }
        return new LocalPath(all[all.length - 1]);
    }

    public Path getParent() {
        String[] all = names();
        if (all.length == 0 || all.length == 1 && !isAbsolute()) {
            return null;
        }
        if (all.length == 1) {
            return new LocalPath("/");
        }
        return new LocalPath(text.substring(0, text.lastIndexOf('/')));
    }

    public int getNameCount() {
        return names().length;
    }

    public Path getName(int index) {
        String[] all = names();
        if (index < 0 || index >= all.length) {
            throw new IllegalArgumentException();
        }
        return new LocalPath(all[index]);
    }

    public Path subpath(int beginIndex, int endIndex) {
        String[] all = names();
        if (beginIndex < 0 || beginIndex >= all.length || endIndex > all.length || beginIndex >= endIndex) {
            throw new IllegalArgumentException();
        }
        return joined(false, all, beginIndex, endIndex);
    }

    public boolean startsWith(Path other) {
        LocalPath that = local(other);
        if (that.isAbsolute() != isAbsolute()) {
            return false;
        }
        if (that.text.isEmpty() || text.isEmpty()) {
            return that.text.isEmpty() && text.isEmpty();
        }
        String[] mine = names();
        String[] theirs = that.names();
        if (theirs.length > mine.length) {
            return false;
        }
        for (int i = 0; i < theirs.length; i++) {
            if (!mine[i].equals(theirs[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean endsWith(Path other) {
        LocalPath that = local(other);
        if (that.isAbsolute()) {
            return that.text.equals(text);
        }
        if (that.text.isEmpty() || text.isEmpty()) {
            return that.text.isEmpty() && text.isEmpty();
        }
        String[] mine = names();
        String[] theirs = that.names();
        if (theirs.length > mine.length) {
            return false;
        }
        for (int i = 1; i <= theirs.length; i++) {
            if (!mine[mine.length - i].equals(theirs[theirs.length - i])) {
                return false;
            }
        }
        return true;
    }

    // The names with each . dropped and each .. dropped with the name before it; a ..
    // at the root is the root.
    private List<String> kept() {
        List<String> kept = new ArrayList<>();
        for (String name : names()) {
            if (name.equals(".") || name.isEmpty()) {
                continue;
            }
            if (name.equals("..")) {
                if (!kept.isEmpty() && !kept.get(kept.size() - 1).equals("..")) {
                    kept.remove(kept.size() - 1);
                    continue;
                }
                if (isAbsolute()) {
                    continue;
                }
            }
            kept.add(name);
        }
        return kept;
    }

    public Path normalize() {
        List<String> kept = kept();
        return joined(isAbsolute(), kept.toArray(new String[0]), 0, kept.size());
    }

    public Path resolve(Path other) {
        LocalPath that = local(other);
        if (that.isAbsolute() || text.isEmpty()) {
            return that;
        }
        if (that.text.isEmpty()) {
            return this;
        }
        return new LocalPath(text.equals("/") ? "/" + that.text : text + "/" + that.text);
    }

    public Path relativize(Path other) {
        LocalPath that = local(other);
        if (that.isAbsolute() != isAbsolute()) {
            throw new IllegalArgumentException("'other' is different type of Path");
        }
        List<String> from = kept();
        List<String> to = that.kept();
        int common = 0;
        while (common < from.size() && common < to.size() && from.get(common).equals(to.get(common))) {
            common++;
        }
        StringBuilder relative = new StringBuilder();
        for (int i = common; i < from.size(); i++) {
            if (from.get(i).equals("..")) {
                throw new IllegalArgumentException(
                        "Unable to compute relative  path from " + text + " to " + that.text);
            }
            relative.append(relative.length() > 0 ? "/.." : "..");
        }
        for (int i = common; i < to.size(); i++) {
            if (relative.length() > 0) {
                relative.append('/');
            }
            relative.append(to.get(i));
        }
        return new LocalPath(relative.toString());
    }

    public Path toAbsolutePath() {
        if (isAbsolute()) {
            return this;
        }
        String directory = Disk.workingDirectory();
        if (directory == null) {
            return this;
        }
        return new LocalPath(text.isEmpty() ? directory : directory.equals("/") ? "/" + text : directory + "/" + text);
    }

    // Its links followed, the absolute path with every link resolved and its . and ..
    // taken out; with NOFOLLOW_LINKS, the absolute path with its . taken out, a link and a
    // .. kept, and a refusal naming it.
    public Path toRealPath(LinkOption... options) throws IOException {
        boolean follow = true;
        for (LinkOption option : options) {
            follow &= Objects.requireNonNull(option) != LinkOption.NOFOLLOW_LINKS;
        }
        long[] stat = Disk.stat(host(), follow);
        String absolute = toAbsolutePath().toString();
        if (stat[Disk.KIND] == Disk.NONE) {
            throw unstated(follow ? text : absolute, stat[Disk.WHY]);
        }
        if (follow) {
            return new LocalPath(Disk.normal(Disk.real(absolute)));
        }
        StringBuilder kept = new StringBuilder();
        for (String name : absolute.split("/")) {
            if (!name.isEmpty() && !name.equals(".")) {
                kept.append('/').append(name);
            }
        }
        return new LocalPath(absolute.startsWith("/") ? (kept.length() == 0 ? "/" : kept.toString())
                : kept.length() == 0 ? "." : kept.substring(1));
    }

    // The exception a JDK throws where the host's stat of a file could not say what it is,
    // for why it could not (Disk.stat's).
    public static IOException unstated(String file, long why) {
        return why == Disk.NOT_THERE ? new NoSuchFileException(file)
                : why == Disk.REFUSED ? new AccessDeniedException(file)
                        : why == Disk.LOOPED ? new FileSystemException(file, null, Disk.LOOP_WORDS)
                                : new FileSystemException(file);
    }

    // file:// and the absolute path, a directory's ending in a slash, each character past
    // ASCII escaped. Where the working directory has no name, a relative path is
    // file: and the path.
    public URI toUri() {
        String path = text.isEmpty() && Disk.workingDirectory() == null ? "." : toAbsolutePath().toString();
        if (!path.endsWith("/") && Disk.kind(path) == Disk.DIRECTORY) {
            path = path + "/";
        }
        return URI.create((path.startsWith("/") ? "file://" : "file:") + quoted(path, false));
    }

    public FileSystem getFileSystem() {
        return LocalFileSystem.HOST;
    }

    public Iterator<Path> iterator() {
        List<Path> each = new ArrayList<>();
        if (!text.isEmpty()) {
            for (String name : names()) {
                each.add(new LocalPath(name));
            }
        }
        return each.iterator();
    }

    public int compareTo(Path other) {
        return text.compareTo(local(other).text);
    }

    public boolean equals(Object other) {
        return other instanceof LocalPath that && text.equals(that.text);
    }

    public int hashCode() {
        return text.hashCode();
    }

    public String toString() {
        return text;
    }
}
