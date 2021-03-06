package ru.mail.polis.dao.s3ponia;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.Iters;

import javax.annotation.concurrent.GuardedBy;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PersistenceDAO implements DAO {
    private final DiskManager manager;
    private final long maxMemory;
    private static final double THRESHOLD = 0.7;

    private final ReentrantReadWriteLock readWriteLock =
            new ReentrantReadWriteLock();

    @GuardedBy("readWriteLock")
    private TableSet tableSet;
    
    /**
     * PersistenceDAO's constructor.
     * @param data directory for saving
     * @param maxMemory maximum memory
     * @throws IOException rethrow ioexception
     */
    public PersistenceDAO(final File data, final long maxMemory) throws IOException {
        this.manager = new DiskManager(Paths.get(data.getAbsolutePath(),
                DiskManager.META_PREFIX + data.getName() + DiskManager.META_EXTENSION));
        final NavigableMap<Integer, Table> diskSet = new TreeMap<>();
        for (final var diskTable : this.manager.diskTables()) {
            diskSet.put(diskTable.getGeneration(), diskTable);
        }
        // Closing all files and clear list of diskTables and fileNames
        this.manager.close();
        this.tableSet = new TableSet(
                new MemTable(this.manager.getGeneration()),
                Collections.emptySet(),
                diskSet,
                this.manager.getGeneration()
        );
        this.maxMemory = maxMemory;
    }

    private void flush() throws IOException {
        final TableSet snapshot;
        readWriteLock.writeLock().lock();
        try {
            snapshot = this.tableSet;
            if (snapshot.memTable.size() == 0L) {
                return;
            }
            this.tableSet = snapshot.beforeFlush();
        } finally {
            readWriteLock.writeLock().unlock();
        }

        this.manager.save(snapshot.memTable.iterator(ByteBuffer.allocate(0)), snapshot.generation);

        final var dest = this.manager.diskTableFromGeneration(snapshot.generation);

        readWriteLock.writeLock().lock();
        try {
            this.tableSet = this.tableSet.afterFlush(snapshot.memTable, dest, snapshot.generation);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    public static PersistenceDAO of(final File data, final long memorySize) throws IOException {
        return new PersistenceDAO(data, memorySize);
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull final ByteBuffer from) {
        final var newest = cellsIterator(from);
        final var removeDead = Iterators.filter(newest, el -> !el.getValue().isDead());

        return Iterators.transform(removeDead, c -> Record.of(c.getKey(), c.getValue().getValue()));
    }
    
    @NotNull
    @Override
    public Iterator<ICell> cellsIterator(@NotNull final ByteBuffer from) {
        final TableSet snapshot;
        readWriteLock.readLock().lock();
        try {
            snapshot = this.tableSet;
        } finally {
            readWriteLock.readLock().unlock();
        }
    
        final var diskIterators = new ArrayList<Iterator<ICell>>();
        diskIterators.add(snapshot.memTable.iterator(from));
        snapshot.diskTables.forEach((a, table) -> diskIterators.add(table.iterator(from)));
        snapshot.flushingTables.forEach(table -> diskIterators.add(table.iterator(from)));
        final var merge = Iterators.mergeSorted(diskIterators, ICell::compareTo);
    
        return Iters.collapseEquals(merge, ICell::getKey);
    }
    
    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) throws IOException {
        upsertWithTimeStamp(key, value, System.currentTimeMillis());
    }
    
    @Override
    public void upsertWithTimeStamp(@NotNull final ByteBuffer key,
                                    @NotNull final ByteBuffer value,
                                    final long timeStamp) throws IOException {
        final boolean flushPending;
        readWriteLock.readLock().lock();
        try {
            this.tableSet.memTable.upsertWithTimeStamp(key, value, timeStamp);
            flushPending = this.tableSet.memTable.size() >= THRESHOLD * maxMemory;
        } finally {
            readWriteLock.readLock().unlock();
        }
    
        if (flushPending) {
            flush();
        }
    }
    
    @Override
    public void remove(@NotNull final ByteBuffer key) throws IOException {
        removeWithTimeStamp(key, System.currentTimeMillis());
    }
    
    @Override
    public void removeWithTimeStamp(@NotNull final ByteBuffer key, final long timeStamp) throws IOException {
        final boolean flushPending;
        readWriteLock.readLock().lock();
        try {
            this.tableSet.memTable.removeWithTimeStamp(key, timeStamp);
            flushPending = this.tableSet.memTable.size() >= THRESHOLD * maxMemory;
        } finally {
            readWriteLock.readLock().unlock();
        }
    
        if (flushPending) {
            flush();
        }
    }
    
    @Override
    public void close() throws IOException {
        if (tableSet.memTable.size() != 0) {
            flush();
        }
        for (final var diskTable : this.tableSet.diskTables.values()) {
            diskTable.close();
        }
    }

    @Override
    public void compact() throws IOException {
        final TableSet snapshot;
        readWriteLock.readLock().lock();
        try {
            snapshot = this.tableSet;
        } finally {
            readWriteLock.readLock().unlock();
        }

        final var point = ByteBuffer.allocate(0);
        final var iterators = new ArrayList<Iterator<ICell>>();
        iterators.add(snapshot.memTable.iterator(point));
        snapshot.diskTables.forEach((a, table) -> iterators.add(table.iterator(point)));
        final var merge = Iterators.mergeSorted(iterators, ICell::compareTo);
        final var newest = Iters.collapseEquals(merge, ICell::getKey);

        readWriteLock.writeLock().lock();
        try {
            this.tableSet = this.tableSet.beforeCompact();
        } finally {
            readWriteLock.writeLock().unlock();
        }

        this.manager.clear();
        this.manager.save(newest, snapshot.generation);
        final var dest = this.manager.diskTableFromGeneration(snapshot.generation);

        readWriteLock.writeLock().lock();
        try {
            this.tableSet = this.tableSet.afterCompact(snapshot.diskTables, dest, snapshot.generation);
        } finally {
            readWriteLock.writeLock().unlock();
        }

        for (final var diskTable : snapshot.diskTables.values()) {
            diskTable.close();
            Files.delete(((DiskTable) diskTable).getFilePath());
        }
    }
}
