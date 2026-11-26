// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import rontolisp.internal.Delta;
import rontolisp.internal.Shown;

// The checks of a path: whether it is there and what it is, its name, what it holds.
public abstract class AbstractPathAssert<SELF extends AbstractPathAssert<SELF>> extends AbstractComparableAssert<SELF, Path> {

    protected AbstractPathAssert(Path actual) {
        super(actual);
    }

    private SELF said(boolean holds, String text) {
        if (!holds) {
            throw failure(text);
        }
        return myself;
    }

    public SELF exists() {
        isNotNull();
        return said(Files.exists(actual), "\nExpecting path:\n" + Shown.under(actual) + "\nto exist (symbolic links were followed).");
    }

    public SELF doesNotExist() {
        isNotNull();
        return said(!Files.exists(actual), "\nExpecting path:\n" + Shown.under(actual) + "\nnot to exist");
    }

    public SELF isRegularFile() {
        exists();
        return said(Files.isRegularFile(actual), "\nExpecting path:\n" + Shown.under(actual) + "\nto be a regular file.");
    }

    public SELF isDirectory() {
        exists();
        return said(Files.isDirectory(actual), "\nExpecting path:\n" + Shown.under(actual) + "\nto be a directory.");
    }

    public SELF isAbsolute() {
        isNotNull();
        return said(actual.isAbsolute(), "\nExpecting actual:\n" + Shown.under(actual) + "\nto be an absolute path.");
    }

    public SELF isRelative() {
        isNotNull();
        return said(!actual.isAbsolute(), "\nExpecting actual:\n" + Shown.under(actual) + "\nto be a relative path.");
    }

    public SELF hasFileName(String fileName) {
        isNotNull();
        Path had = actual.getFileName();
        return said(had != null && had.toString().equals(fileName), "\nExpecting\n" + Shown.under(actual)
                + "\nto have name:\n" + Shown.under(fileName) + "\nbut had:\n" + Shown.under(had));
    }

    private String extension() {
        String name = String.valueOf(actual.getFileName());
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(dot + 1);
    }

    public SELF hasExtension(String expectedExtension) {
        isRegularFile();
        String had = extension();
        if (had == null) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto have extension:\n" + Shown.under(expectedExtension)
                    + "\nbut had no extension.");
        }
        return said(had.equals(expectedExtension), "\nExpecting\n" + Shown.under(actual) + "\nto have extension:\n"
                + Shown.under(expectedExtension) + "\nbut had:\n" + Shown.under(had) + ".");
    }

    public SELF hasNoExtension() {
        isRegularFile();
        String had = extension();
        return said(had == null, "\nExpected actual path:\n" + Shown.under(actual)
                + "\n not to have an extension, but extension was: " + Shown.of(had));
    }

    private byte[] bytes() {
        try {
            return Files.readAllBytes(actual);
        }
        catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public SELF hasContent(String expected) {
        isRegularFile();
        String differences = Delta.between(Delta.lines(new String(bytes(), StandardCharsets.UTF_8)), Delta.lines(expected));
        return said(differences == null, "\nPath:\n" + Shown.under(actual)
                + "\nread with charset UTF-8 does not have the expected content:\n\n" + differences + "\n");
    }

    public SELF hasBinaryContent(byte[] expected) {
        isRegularFile();
        String differences = Delta.bytes(bytes(), expected);
        return said(differences == null, "\nPath:\n" + Shown.under(actual) + "\n" + differences);
    }

    public SELF hasSize(long expectedSize) {
        isRegularFile();
        long size = bytes().length;
        return said(size == expectedSize, "\nExpecting path\n" + Shown.under(actual) + "\nto have a size of:\n  "
                + Shown.of(expectedSize) + " bytes\nbut had:\n  " + Shown.of(size) + " bytes");
    }

    public SELF isEmptyFile() {
        isRegularFile();
        return said(bytes().length == 0, "\nExpecting path " + Shown.of(actual) + " to be empty");
    }

    public SELF isNotEmptyFile() {
        isRegularFile();
        return said(bytes().length > 0, "\nExpecting path " + Shown.of(actual) + " not to be empty");
    }

    private List<Path> listed() {
        try (Stream<Path> entries = Files.list(actual)) {
            return new ArrayList<>(entries.toList());
        }
        catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public SELF isEmptyDirectory() {
        isDirectory();
        List<Path> entries = listed();
        return said(entries.isEmpty(), "\nExpecting actual:\n" + Shown.under(actual)
                + "\nto be an empty directory but it contained:\n" + Shown.under(entries));
    }

    public SELF isNotEmptyDirectory() {
        isDirectory();
        return said(!listed().isEmpty(), "\nExpecting path " + Shown.of(actual) + " not to be empty");
    }

    public AbstractStringAssert<?> content() {
        isRegularFile();
        return carried(new StringAssert(new String(bytes(), StandardCharsets.UTF_8)));
    }

    public AbstractByteArrayAssert<?> binaryContent() {
        isRegularFile();
        return carried(new ByteArrayAssert(bytes()));
    }
}
