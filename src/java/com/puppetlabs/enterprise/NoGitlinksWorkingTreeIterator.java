package com.puppetlabs.enterprise;

import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.WorkingTreeOptions;
import org.eclipse.jgit.util.FS;

/**
 * Working directory iterator for standard Java IO.
 * <p/>
 * This iterator uses the standard <code>java.io</code> package to read the
 * specified working directory as part of a {@link TreeWalk}.
 */
public class NoGitlinksWorkingTreeIterator extends WorkingTreeIterator {
    /**
     * the starting directory. This directory should correspond to the root of
     * the repository.
     */
    protected final File directory;

    /**
     * the file system abstraction which will be necessary to perform certain
     * file system operations.
     */
    protected final FS fs;

    /**
     * Create a new iterator to traverse the work tree and its children.
     *
     * @param repo the repository whose working tree will be scanned.
     */
    public NoGitlinksWorkingTreeIterator(Repository repo) {
        this(repo.getWorkTree(), repo.getFS(),
                repo.getConfig().get(WorkingTreeOptions.KEY));
        initRootIterator(repo);
    }

    /**
     * Create a new iterator to traverse the given directory and its children.
     *
     * @param root    the starting directory. This directory should correspond to
     *                the root of the repository.
     * @param fs      the file system abstraction which will be necessary to perform
     *                certain file system operations.
     * @param options working tree options to be used
     */
    public NoGitlinksWorkingTreeIterator(final File root, FS fs, WorkingTreeOptions options) {
        super(options);
        directory = root;
        this.fs = fs;
        init(entries());
    }

    /**
     * Create a new iterator to traverse a subdirectory.
     *
     * @param p    the parent iterator we were created from.
     * @param fs   the file system abstraction which will be necessary to perform
     *             certain file system operations.
     * @param root the subdirectory. This should be a directory contained within
     *             the parent directory.
     */
    protected NoGitlinksWorkingTreeIterator(final WorkingTreeIterator p, final File root,
                                            FS fs) {
        super(p);
        directory = root;
        this.fs = fs;
        init(entries());
    }

    @Override
    public AbstractTreeIterator createSubtreeIterator(final ObjectReader reader)
            throws IncorrectObjectTypeException, IOException {
        return new NoGitlinksWorkingTreeIterator(this, ((FileEntry) current()).getFile(), fs);
    }

    private Entry[] entries() {
        final File[] all = directory.listFiles();
        if (all == null)
            return EOF;
        final Entry[] r = new Entry[all.length];
        for (int i = 0; i < r.length; i++)
            r[i] = new FileEntry(all[i], fs);
        return r;
    }

    /**
     * Wrapper for a standard Java IO file
     */
    static public class FileEntry extends Entry {
        private final FileMode mode;

        private FS.Attributes attributes;

        private FS fs;

        /**
         * Create a new file entry.
         *
         * @param f  file
         * @param fs file system
         */
        public FileEntry(File f, FS fs) {
            this.fs = fs;
            f = fs.normalize(f);
            attributes = fs.getAttributes(f);
            if (attributes.isSymbolicLink())
                mode = FileMode.SYMLINK;
            else if (attributes.isDirectory()) {
                if (new File(f, Constants.DOT_GIT).exists())
                    mode = FileMode.GITLINK;
                else
                    mode = FileMode.TREE;
            } else if (attributes.isExecutable())
                mode = FileMode.EXECUTABLE_FILE;
            else
                mode = FileMode.REGULAR_FILE;
        }

        @Override
        public FileMode getMode() {
            return mode;
        }

        @Override
        public String getName() {
            return attributes.getName();
        }

        @Override
        public long getLength() {
            return attributes.getLength();
        }

        @Override
        public long getLastModified() {
            return attributes.getLastModifiedTime();
        }

        @Override
        public InputStream openInputStream() throws IOException {
            if (fs.isSymLink(getFile()))
                return new ByteArrayInputStream(fs.readSymLink(getFile())
                        .getBytes(
                                Constants.CHARACTER_ENCODING));
            else
                return new FileInputStream(getFile());
        }

        /**
         * Get the underlying file of this entry.
         *
         * @return the underlying file of this entry
         */
        public File getFile() {
            return attributes.getFile();
        }
    }

    /**
     * @return The root directory of this iterator
     */
    public File getDirectory() {
        return directory;
    }

    /**
     * @return The location of the working file. This is the same as {@code new
     * File(getDirectory(), getEntryPath())} but may be faster by
     * reusing an internal File instance.
     */
    public File getEntryFile() {
        return ((FileEntry) current()).getFile();
    }

    @Override
    protected byte[] idSubmodule(final Entry e) {
        if (repository == null)
            return idSubmodule(getDirectory(), e);
        return super.idSubmodule(e);
    }
}
