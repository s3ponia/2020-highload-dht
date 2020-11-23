/*
 * Copyright 2020 (c) Odnoklassniki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.mail.polis.dao;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.mail.polis.Record;
import ru.mail.polis.dao.s3ponia.ICell;
import ru.mail.polis.dao.s3ponia.Value;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Storage interface.
 *
 * @author Vadim Tsesko
 * @author Dmitry Schitinin
 */
public interface DAO extends Closeable {

    /**
     * Provides iterator (possibly empty) over {@link Record}s starting at "from" key (inclusive)
     * in <b>ascending</b> order according to {@link Record#compareTo(Record)}.
     * N.B. The iterator should be obtained as fast as possible, e.g.
     * one should not "seek" to start point ("from" element) in linear time ;)
     */
    @NotNull
    Iterator<Record> iterator(@NotNull ByteBuffer from) throws IOException;
    
    /**
     * Provides iterator (possibly empty) over {@link ICell}s starting at "from" key (inclusive)
     * in <b>ascending</b> order according to {@link ICell#compareTo(ICell)}.
     */
    @NotNull
    Iterator<ICell> cellsIterator(@NotNull ByteBuffer from) throws IOException;

    /**
     * Provides iterator (possibly empty) over {@link Record}s starting at "from" key (inclusive)
     * until given "to" key (exclusive) in <b>ascending</b> order according to {@link Record#compareTo(Record)}.
     * N.B. The iterator should be obtained as fast as possible, e.g.
     * one should not "seek" to start point ("from" element) in linear time ;)
     */
    @NotNull
    default Iterator<Record> range(
            @NotNull ByteBuffer from,
            @Nullable ByteBuffer to) throws IOException {
        if (to == null) {
            return iterator(from);
        }

        if (from.compareTo(to) > 0) {
            return Iters.empty();
        }

        final Record bound = Record.of(to, ByteBuffer.allocate(0));
        return Iters.until(iterator(from), bound);
    }

    /**
     * Obtains {@link Record} corresponding to given key.
     *
     * @throws NoSuchElementException if no such record
     */
    @NotNull
    default ByteBuffer get(@NotNull ByteBuffer key) throws IOException, NoSuchElementException {
        final var temp = getValue(key);
        
        if (temp.isDead()) {
            throw new NoSuchElementException("Not found");
        }
        
        return temp.getValue();
    }
    
    /**
     * Obtains {@link Record} corresponding to given key.
     *
     * @throws NoSuchElementException if no such record
     */
    @NotNull
    default Value getValue(@NotNull ByteBuffer key) throws IOException {
        final Iterator<ICell> iter = cellsIterator(key);
        if (!iter.hasNext()) {
            return Value.ABSENT;
        }
        
        final ICell next = iter.next();
        if (next.getKey().equals(key)) {
            return next.getValue();
        } else {
            return Value.ABSENT;
        }
    }

    /**
     * Inserts or updates value by given key.
     */
    void upsert(
            @NotNull ByteBuffer key,
            @NotNull ByteBuffer value) throws IOException;
    
    /**
     * Inserts or updates value by given key at given time.
     */
    void upsertWithTimeStamp(
            @NotNull ByteBuffer key,
            @NotNull ByteBuffer value,
            final long timeStamp) throws IOException;

    /**
     * Removes value by given key.
     */
    void remove(@NotNull ByteBuffer key) throws IOException;
    
    /**
     * Inserts or updates value by given key at given time.
     */
    void removeWithTimeStamp(
            @NotNull ByteBuffer key,
            final long timeStamp) throws IOException;

    /**
     * Perform compaction
     */
    default void compact() throws IOException {
        // Implement me when you get to stage 3
    }

    /**
     * Constructs recent snapshot of Dao.
     * @return a {@code DaoSnapshot}
     */
    default DaoSnapshot snapshot() {
        throw new UnsupportedOperationException();
    }
}
