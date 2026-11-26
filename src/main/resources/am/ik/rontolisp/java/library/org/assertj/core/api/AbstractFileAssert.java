// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import rontolisp.internal.Delta;
import rontolisp.internal.Shown;

// The checks of a file: whether it is there and what it is, its name and its parent,
// what it holds. A failure shows the file by its absolute path.
public abstract class AbstractFileAssert<SELF extends AbstractFileAssert<SELF>> extends AbstractAssert<SELF, File> {

    protected AbstractFileAssert(File actual) {
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
        return said(actual.exists(), "\nExpecting file:\n" + Shown.under(actual) + "\nto exist.");
    }

    public SELF doesNotExist() {
        isNotNull();
        return said(!actual.exists(), "\nExpecting file:\n" + Shown.under(actual) + "\nnot to exist");
    }

    public SELF isFile() {
        isNotNull();
        return said(actual.isFile(), "\nExpecting actual:\n " + Shown.of(actual) + "\nto be a file");
    }

    public SELF isDirectory() {
        isNotNull();
        return said(actual.isDirectory(), "\nExpecting file:\n" + Shown.under(actual) + "\n to be an existing directory.");
    }

    public SELF isAbsolute() {
        isNotNull();
        return said(actual.isAbsolute(), "\nExpecting actual:\n" + Shown.under(actual) + "\nto be an absolute path.");
    }

    public SELF isRelative() {
        isNotNull();
        return said(!actual.isAbsolute(), "\nExpecting actual:\n" + Shown.under(actual) + "\nto be a relative path.");
    }

    public SELF hasName(String expected) {
        isNotNull();
        return said(actual.getName().equals(expected), "\nExpecting\n" + Shown.under(actual) + "\nto have name:\n"
                + Shown.under(expected) + "\nbut had:\n" + Shown.under(actual.getName()));
    }

    public SELF hasFileName(String expected) {
        return hasName(expected);
    }

    public SELF hasParent(File expected) {
        isNotNull();
        File had = actual.getParentFile();
        if (had == null) {
            throw failure("\nExpecting file\n" + Shown.under(actual) + "\nto have parent:\n" + Shown.under(expected)
                    + "\nbut did not have one.");
        }
        return said(had.getAbsoluteFile().equals(expected.getAbsoluteFile()), "\nExpecting file\n" + Shown.under(actual) + "\nto have parent:\n"
                + Shown.under(expected) + "\nbut had:\n" + Shown.under(had) + ".");
    }

    public SELF hasParent(String expected) {
        return hasParent(new File(expected));
    }

    public SELF hasNoParent() {
        isNotNull();
        File had = actual.getParentFile();
        return said(had == null, "\nExpecting file (or directory):\n" + Shown.under(actual)
                + "\nnot to have a parent, but parent was:\n" + Shown.under(had));
    }

    private String extension() {
        String name = actual.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(dot + 1);
    }

    public SELF hasExtension(String expected) {
        isFile();
        String had = extension();
        if (had == null) {
            throw failure("\nExpecting\n" + Shown.under(actual) + "\nto have extension:\n" + Shown.under(expected)
                    + "\nbut had no extension.");
        }
        return said(had.equals(expected), "\nExpecting\n" + Shown.under(actual) + "\nto have extension:\n"
                + Shown.under(expected) + "\nbut had:\n" + Shown.under(had) + ".");
    }

    public SELF hasNoExtension() {
        isFile();
        String had = extension();
        return said(had == null, "\nExpected actual file:\n" + Shown.under(actual)
                + "\n not to have an extension, but extension was: " + Shown.of(had));
    }

    private byte[] bytes() {
        try {
            return Files.readAllBytes(actual.toPath());
        }
        catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public SELF hasContent(String expected) {
        isFile();
        String differences = Delta.between(Delta.lines(new String(bytes(), StandardCharsets.UTF_8)), Delta.lines(expected));
        return said(differences == null, "\nFile:\n" + Shown.under(actual)
                + "\nread with charset UTF-8 does not have the expected content:\n\n" + differences + "\n");
    }

    public SELF hasBinaryContent(byte[] expected) {
        isFile();
        String differences = Delta.bytes(bytes(), expected);
        return said(differences == null, "\nFile:\n" + Shown.under(actual) + "\n" + differences);
    }

    public SELF hasSize(long expected) {
        isFile();
        long size = actual.length();
        return said(size == expected, "\nExpecting file\n" + Shown.under(actual) + "\nto have a size of:\n  "
                + Shown.of(expected) + " bytes\nbut had:\n  " + Shown.of(size) + " bytes");
    }

    public SELF isEmpty() {
        isFile();
        return said(actual.length() == 0, "\nExpecting file " + Shown.of(actual) + " to be empty");
    }

    public SELF isNotEmpty() {
        isFile();
        return said(actual.length() > 0, "\nExpecting file " + Shown.of(actual) + " not to be empty");
    }

    private File[] listed() {
        File[] entries = actual.listFiles();
        return entries == null ? new File[0] : entries;
    }

    public SELF isEmptyDirectory() {
        isDirectory();
        File[] entries = listed();
        return said(entries.length == 0, "\nExpecting actual:\n" + Shown.under(actual)
                + "\nto be an empty directory but it contained:\n" + Shown.under(entries));
    }

    public SELF isNotEmptyDirectory() {
        isDirectory();
        return said(listed().length > 0, "\nExpecting actual not to be empty");
    }

    public SELF canRead() {
        isNotNull();
        return said(actual.canRead(), "\nExpecting actual:\n" + Shown.under(actual) + "\nto be readable.");
    }

    public AbstractStringAssert<?> content() {
        canRead();
        return carried(new StringAssert(new String(bytes(), StandardCharsets.UTF_8)));
    }

    public AbstractByteArrayAssert<?> binaryContent() {
        isFile();
        return carried(new ByteArrayAssert(bytes()));
    }
}
