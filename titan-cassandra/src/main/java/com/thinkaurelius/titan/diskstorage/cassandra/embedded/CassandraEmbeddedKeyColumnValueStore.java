package com.thinkaurelius.titan.diskstorage.cassandra.embedded;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.thinkaurelius.titan.diskstorage.PermanentStorageException;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.TemporaryStorageException;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.util.RecordIterator;
import com.thinkaurelius.titan.diskstorage.util.StaticByteBuffer;

import org.apache.cassandra.db.*;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.filter.IDiskAtomFilter;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.dht.*;
import org.apache.cassandra.exceptions.IsBootstrappingException;
import org.apache.cassandra.exceptions.RequestTimeoutException;
import org.apache.cassandra.exceptions.UnavailableException;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.ThriftValidation;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import static com.thinkaurelius.titan.diskstorage.cassandra.CassandraTransaction.getTx;

public class CassandraEmbeddedKeyColumnValueStore implements KeyColumnValueStore {

    private static final Logger log = LoggerFactory.getLogger(CassandraEmbeddedKeyColumnValueStore.class);

    private final String keyspace;
    private final String columnFamily;
    private final CassandraEmbeddedStoreManager storeManager;


    public CassandraEmbeddedKeyColumnValueStore(
            String keyspace,
            String columnFamily,
            CassandraEmbeddedStoreManager storeManager) throws RuntimeException {
        this.keyspace = keyspace;
        this.columnFamily = columnFamily;
        this.storeManager = storeManager;
    }

    @Override
    public void close() throws StorageException {
    }

    static ByteBuffer getInternal(String keyspace,
                                         String columnFamily,
                                         ByteBuffer key,
                                         ByteBuffer column,
                                         org.apache.cassandra.db.ConsistencyLevel cl) throws StorageException {

        QueryPath slicePath = new QueryPath(columnFamily);

        SliceByNamesReadCommand namesCmd = new SliceByNamesReadCommand(
                keyspace, key.duplicate(), slicePath, Arrays.asList(column.duplicate()));

        List<Row> rows = read(namesCmd, cl);

        if (null == rows || 0 == rows.size())
            return null;

        if (1 < rows.size())
            throw new PermanentStorageException("Received " + rows.size()
                    + " rows from a single-key-column cassandra read");

        assert 1 == rows.size();

        Row r = rows.get(0);

        if (null == r) {
            log.warn("Null Row object retrieved from Cassandra StorageProxy");
            return null;
        }

        ColumnFamily cf = r.cf;
        if (null == cf)
            return null;

        if (cf.isMarkedForDelete())
            return null;

        IColumn c = cf.getColumn(column.duplicate());
        if (null == c)
            return null;

        // These came up during testing
        if (c.isMarkedForDelete())
            return null;

        return org.apache.cassandra.utils.ByteBufferUtil.clone(c.value());
    }

    @Override
    public void acquireLock(StaticBuffer key, StaticBuffer column,
                            StaticBuffer expectedValue, StoreTransaction txh) throws StorageException {
        throw new UnsupportedOperationException();
    }

    @Override
    public RecordIterator<StaticBuffer> getKeys(StoreTransaction txh) throws StorageException {
        final IPartitioner<?> partitioner = StorageService.getPartitioner();

        final Token minimumToken, maximumToken;
        if (partitioner instanceof RandomPartitioner) {
            minimumToken = ((RandomPartitioner) partitioner).getMinimumToken();
            maximumToken = new BigIntegerToken(RandomPartitioner.MAXIMUM);
        } else if (partitioner instanceof Murmur3Partitioner) {
            minimumToken = ((Murmur3Partitioner) partitioner).getMinimumToken();
            maximumToken = new LongToken(Murmur3Partitioner.MAXIMUM);
        } else if (partitioner instanceof ByteOrderedPartitioner) {
            //TODO: This makes the assumption that its an EdgeStore (i.e. 8 byte keys)
            minimumToken = new BytesToken(com.thinkaurelius.titan.diskstorage.util.ByteBufferUtil.zeroByteBuffer(8));
            maximumToken = new BytesToken(com.thinkaurelius.titan.diskstorage.util.ByteBufferUtil.oneByteBuffer(8));
        } else {
            throw new PermanentStorageException("This operation is only allowed when random partitioner (md5 or murmur3) is used.");
        }

        return new RecordIterator<StaticBuffer>() {
            private Iterator<Row> keys = getKeySlice(minimumToken,
                                                     maximumToken,
                                                     storeManager.getPageSize());

            private ByteBuffer lastSeenKey = null;

            @Override
            public boolean hasNext() throws StorageException {
                boolean hasNext = keys.hasNext();

                if (!hasNext && lastSeenKey != null) {
                    keys = getKeySlice(partitioner.getToken(lastSeenKey), maximumToken, storeManager.getPageSize());
                    hasNext = keys.hasNext();
                }

                return hasNext;
            }

            @Override
            public StaticBuffer next() throws StorageException {
                if (!hasNext())
                    throw new NoSuchElementException();

                Row row = keys.next();

                try {
                    return new StaticByteBuffer(row.key.key);
                } finally {
                    lastSeenKey = row.key.key;
                }
            }

            @Override
            public void close() throws StorageException {
                // nothing to clean-up here
            }
        };
    }

    private Iterator<Row> getKeySlice(Token start, Token end, int pageSize) throws StorageException {
        IPartitioner<?> partitioner = StorageService.getPartitioner();

        /* Note: we need to fetch columns for each row as well to remove "range ghosts" */
        SlicePredicate predicate = new SlicePredicate().setSlice_range(new SliceRange()
                                                                        .setStart(ArrayUtils.EMPTY_BYTE_ARRAY)
                                                                        .setFinish(ArrayUtils.EMPTY_BYTE_ARRAY)
                                                                        .setCount(5));

        Range<RowPosition> range = new Range<RowPosition>(start.maxKeyBound(partitioner),
                                                          end.maxKeyBound(partitioner),
                                                          partitioner);

        List<Row> rows;

        try {
            IDiskAtomFilter filter = ThriftValidation.asIFilter(predicate, BytesType.instance);

            rows = StorageProxy.getRangeSlice(new RangeSliceCommand(keyspace,
                                                                    new ColumnParent(columnFamily),
                                                                    filter,
                                                                    range,
                                                                    null,
                                                                    pageSize), ConsistencyLevel.QUORUM);
        } catch (Exception e) {
            throw new PermanentStorageException(e);
        }

        return Iterators.filter(rows.iterator(), new Predicate<Row>() {
            @Override
            public boolean apply(@Nullable Row row) {
                return !(row == null || row.cf == null || row.cf.isMarkedForDelete() || row.cf.hasOnlyTombstones());
            }
        });
    }

    @Override
    public StaticBuffer[] getLocalKeyPartition() throws StorageException {
        return storeManager.getLocalKeyPartition();
    }


    @Override
    public String getName() {
        return columnFamily;
    }

    @Override
    public boolean containsKey(StaticBuffer key, StoreTransaction txh) throws StorageException {
        
        QueryPath slicePath = new QueryPath(columnFamily);
        // TODO key.asByteBuffer() may entail an unnecessary buffer copy
        ReadCommand sliceCmd = new SliceFromReadCommand(
                keyspace,          // Keyspace name
                key.asByteBuffer(),// Row key
                slicePath,         // ColumnFamily
                ByteBufferUtil.EMPTY_BYTE_BUFFER, // Start column name (empty means begin at first result)
                ByteBufferUtil.EMPTY_BYTE_BUFFER, // End column name (empty means max out the count)
                false,             // Reverse results? (false=no)
                1);                // Max count of Columns to return

        List<Row> rows = read(sliceCmd, getTx(txh).getReadConsistencyLevel().getDBConsistency());

        if (null == rows || 0 == rows.size())
            return false;
        
        /*
         * Find at least one live column
		 * 
		 * Note that the rows list may contain arbitrarily many
		 * marked-for-delete elements. Therefore, we can't assume that we're
		 * dealing with a singleton even though we set the maximum column count
		 * to 1.
		 */
        for (Row r : rows) {
            if (null == r || null == r.cf)
                continue;

            if (r.cf.isMarkedForDelete())
                continue;

            for (IColumn ic : r.cf)
                if (!ic.isMarkedForDelete())
                    return true;
        }

        return false;
    }

    @Override
    public List<Entry> getSlice(KeySliceQuery query, StoreTransaction txh) throws StorageException {

        QueryPath slicePath = new QueryPath(columnFamily);
        ReadCommand sliceCmd = new SliceFromReadCommand(
                keyspace,                      // Keyspace name
                query.getKey().asByteBuffer(), // Row key
                slicePath,                     // ColumnFamily
                query.getSliceStart().asByteBuffer(),  // Start column name (empty means begin at first result)
                query.getSliceEnd().asByteBuffer(),   // End column name (empty means max out the count)
                false,                         // Reverse results? (false=no)
                query.getLimit());             // Max count of Columns to return

        List<Row> slice = read(sliceCmd, getTx(txh).getReadConsistencyLevel().getDBConsistency());

        if (null == slice || 0 == slice.size())
            return new ArrayList<Entry>(0);

        int sliceSize = slice.size();
        if (1 < sliceSize)
            throw new PermanentStorageException("Received " + sliceSize + " rows for single key");

        Row r = slice.get(0);

        if (null == r) {
            log.warn("Null Row object retrieved from Cassandra StorageProxy");
            return new ArrayList<Entry>(0);
        }

        ColumnFamily cf = r.cf;

        if (null == cf) {
            log.debug("null ColumnFamily (\"{}\")", columnFamily);
            return new ArrayList<Entry>(0);
        }

        if (cf.isMarkedForDelete())
            return new ArrayList<Entry>(0);

        return cfToEntries(cf, query.getSliceEnd());
    }

    @Override
    public void mutate(StaticBuffer key, List<Entry> additions,
                       List<StaticBuffer> deletions, StoreTransaction txh) throws StorageException {
        Map<StaticBuffer, KCVMutation> mutations = ImmutableMap.of(key, new
                KCVMutation(additions, deletions));
        mutateMany(mutations, txh);
    }


    public void mutateMany(Map<StaticBuffer, KCVMutation> mutations,
                           StoreTransaction txh) throws StorageException {
        storeManager.mutateMany(ImmutableMap.of(columnFamily, mutations), txh);
    }

    private static List<Row> read(ReadCommand cmd, org.apache.cassandra.db.ConsistencyLevel clvl) throws StorageException {
        ArrayList<ReadCommand> cmdHolder = new ArrayList<ReadCommand>(1);
        cmdHolder.add(cmd);
        return read(cmdHolder, clvl);
    }

    private static List<Row> read(List<ReadCommand> cmds, org.apache.cassandra.db.ConsistencyLevel clvl) throws StorageException {
        try {
            return StorageProxy.read(cmds, clvl);
        } catch (IOException e) {
            throw new PermanentStorageException(e);
        } catch (UnavailableException e) {
            throw new TemporaryStorageException(e);
        } catch (RequestTimeoutException e) {
            throw new PermanentStorageException(e);
        } catch (IsBootstrappingException e) {
            throw new TemporaryStorageException(e);
        }
    }


    private List<Entry> cfToEntries(ColumnFamily cf,
                                    StaticBuffer columnEnd) throws StorageException {

        assert !cf.isMarkedForDelete();

        // Estimate size of Entry list, ignoring deleted columns
        int resultSize = 0;
        for (ByteBuffer col : cf.getColumnNames()) {
            IColumn icol = cf.getColumn(col);
            if (null == icol)
                throw new PermanentStorageException("Unexpected null IColumn");

            if (icol.isMarkedForDelete())
                continue;

            resultSize++;
        }

        // Instantiate return collection
        List<Entry> result = new ArrayList<Entry>(resultSize);

        /*
         * We want to call columnEnd.equals() on column name ByteBuffers in the
         * loop below. But columnEnd is a StaticBuffer, and it doesn't have an
         * equals() method that accepts ByteBuffer. We create a ByteBuffer copy
         * of columnEnd just for equals() comparisons in the for loop below.
         * 
         * TODO remove this if StaticBuffer's equals() accepts ByteBuffer
         */
        ByteBuffer columnEndBB = columnEnd.asByteBuffer();

        // Populate Entries into return collection
        for (ByteBuffer col : cf.getColumnNames()) {

            IColumn icol = cf.getColumn(col);
            if (null == icol)
                throw new PermanentStorageException("Unexpected null IColumn");

            if (icol.isMarkedForDelete())
                continue;

            ByteBuffer name = org.apache.cassandra.utils.ByteBufferUtil.clone(icol.name());
            ByteBuffer value = org.apache.cassandra.utils.ByteBufferUtil.clone(icol.value());

            if (columnEndBB.equals(name))
                continue;

            result.add(new ByteBufferEntry(name, value));
        }

        return result;
    }


}
