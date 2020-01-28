/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
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
package org.iq80.leveldb.impl;

import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import org.iq80.leveldb.util.AbstractSeekingIterator;
import org.iq80.leveldb.util.DbIterator;

import java.util.Comparator;
import java.util.Map.Entry;

public final class SnapshotSeekingIterator extends AbstractSeekingIterator<ByteBuf, ByteBuf> {
    private final DbIterator iterator;
    private final SnapshotImpl snapshot;
    private final Comparator<ByteBuf> userComparator;

    public SnapshotSeekingIterator(DbIterator iterator, SnapshotImpl snapshot, Comparator<ByteBuf> userComparator) {
        this.iterator = iterator;
        this.snapshot = snapshot;
        this.userComparator = userComparator;
        this.snapshot.getVersion().retain();
    }

    public void close() {
        this.snapshot.getVersion().release();
    }

    @Override
    protected void seekToFirstInternal() {
        iterator.seekToFirst();
        findNextUserEntry(null);
    }

    @Override
    protected void seekInternal(ByteBuf targetKey) {
        iterator.seek(new InternalKey(targetKey, snapshot.getLastSequence(), ValueType.VALUE));
        findNextUserEntry(null);
    }

    @Override
    protected Entry<ByteBuf, ByteBuf> getNextElement() {
        if (!iterator.hasNext()) {
            return null;
        }

        Entry<InternalKey, ByteBuf> next = iterator.next();

        // find the next user entry after the key we are about to return
        findNextUserEntry(next.getKey().getUserKey());

        return Maps.immutableEntry(next.getKey().getUserKey(), next.getValue());
    }

    private void findNextUserEntry(ByteBuf deletedKey) {
        // if there are no more entries, we are done
        if (!iterator.hasNext()) {
            return;
        }

        do {
            // Peek the next entry and parse the key
            InternalKey internalKey = iterator.peek().getKey();

            // skip entries created after our snapshot
            if (internalKey.getSequenceNumber() > snapshot.getLastSequence()) {
                iterator.next();
                continue;
            }

            // if the next entry is a deletion, skip all subsequent entries for that key
            if (internalKey.getValueType() == ValueType.DELETION) {
                deletedKey = internalKey.getUserKey();
            } else if (internalKey.getValueType() == ValueType.VALUE) {
                // is this value masked by a prior deletion record?
                if (deletedKey == null || userComparator.compare(internalKey.getUserKey(), deletedKey) > 0) {
                    return;
                }
            }
            iterator.next();
        } while (iterator.hasNext());
    }

    @Override
    public String toString() {
        return "SnapshotSeekingIterator" +
                "(snapshot=" + snapshot +
                ", iterator=" + iterator +
                ')';
    }
}
