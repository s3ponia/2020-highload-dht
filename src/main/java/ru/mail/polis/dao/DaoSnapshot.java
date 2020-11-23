package ru.mail.polis.dao;

import ru.mail.polis.dao.s3ponia.ICell;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

public interface DaoSnapshot {

    /**
     * Provides iterator over records that fits in hash range from start to end.
     * @param start minimum hash to fit in range
     * @param end maximum hash to fit in range
     * @return a {@code Iterator<Record>}
     */
    Iterator<ICell> range(final long start, final long end);

    /**
     * Calculates hash over range from start to end.
     * @param start minimum hash to fit in range
     * @param end maximum hash to fit in range
     * @return a {@code byte[]}
     */
    byte[] hash(final long start, final long end);

    /**
     * Saves snapshot to given path.
     * @param path path where will be saved all data as in DiskTable.
     */
    void saveTo(final Path path) throws IOException;

    /**
     * Saves all records in range from start to end by given path.
     * @param path File's path to save
     * @param start minimum hash to fit in range
     * @param end maximum hash to fit in range
     */
    void saveTo(final Path path, final long start, final long end) throws IOException;
}
