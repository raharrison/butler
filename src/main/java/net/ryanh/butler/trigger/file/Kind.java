package net.ryanh.butler.trigger.file;

/**
 * What kind of entry {@code file.appeared} is watching for.
 */
public enum Kind {

    /**
     * Regular files, judged settled by their own size and mtime.
     */
    FILE,

    /**
     * Directories, judged settled by an aggregate over everything beneath them, since a directory's
     * own size and mtime say nothing about a file still being written inside it.
     */
    DIR
}
