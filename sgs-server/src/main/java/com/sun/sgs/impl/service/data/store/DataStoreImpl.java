/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.data.store;

import com.sun.sgs.app.ObjectNotFoundException;
import com.sun.sgs.app.TransactionAbortedException;
import com.sun.sgs.app.TransactionNotActiveException;
import com.sun.sgs.impl.kernel.StandardProperties;
import static com.sun.sgs.impl.service.data.store.
    DataStoreHeader.ALLOCATION_BLOCK_SIZE;
import static com.sun.sgs.impl.service.data.store.
    DataStoreHeader.FIRST_PLACEHOLDER_ID_KEY;
import static com.sun.sgs.impl.service.data.store.
    DataStoreHeader.NEXT_OBJ_ID_KEY;
import static com.sun.sgs.impl.service.data.store.
    DataStoreHeader.PLACEHOLDER_OBJ_VALUE;
import static com.sun.sgs.impl.service.data.store.
    DataStoreHeader.QUOTE_OBJ_VALUE;
import com.sun.sgs.impl.service.data.store.db.DataEncoding;
import com.sun.sgs.impl.service.data.store.db.DbCursor;
import com.sun.sgs.impl.service.data.store.db.DbDatabase;
import com.sun.sgs.impl.service.data.store.db.DbDatabaseException;
import com.sun.sgs.impl.service.data.store.db.DbEnvironment;
import com.sun.sgs.impl.service.data.store.db.DbEnvironmentFactory;
import com.sun.sgs.impl.service.data.store.db.DbTransaction;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import static com.sun.sgs.impl.sharedutil.Objects.checkNull;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;
import com.sun.sgs.kernel.AccessCoordinator;
import com.sun.sgs.service.Transaction;
import com.sun.sgs.service.TransactionParticipant;
import java.io.File;
import java.io.FileNotFoundException;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Queue;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * XXX: Implement recovery for prepared transactions after a crash.
 * -tjb@sun.com (11/07/2006)
 */

/**
 * Provides an implementation of <code>DataStore</code> based on the database
 * interface layer defined in the {@link
 * com.sun.sgs.impl.service.data.store.db} package. <p>
 *
 * Note that, although this class provides support for the {@link
 * TransactionParticipant#prepare TransactionParticipant.prepare} method, it
 * does not provide facilities for resolving prepared transactions after a
 * crash.  Callers can work around this limitation by insuring that the
 * transaction implementation calls {@link
 * TransactionParticipant#prepareAndCommit
 * TransactionParticipant.prepareAndCommit} to commit transactions on this
 * class.  The current transaction implementation calls
 * <code>prepareAndCommit</code> on durable participants, such as this class,
 * so the inability to resolve prepared transactions should have no effect at
 * present. <p>
 *
 * The {@link #DataStoreImpl(Properties, AccessCoordinator) constructor}
 * supports these public <a
 * href="../../../../app/doc-files/config-properties.html#DataStore">
 * properties</a>. <p>
 *
 * The constructor also passes the properties to {@link
 * DbEnvironmentFactory#getEnvironment DbEnvironmentFactory.getEnvironment},
 * which supports additional properties. <p>
 *
 * This class uses the {@link Logger} named
 * <code>com.sun.sgs.impl.service.data.store.DataStoreImpl</code> to log
 * information at the following logging levels: <p>
 *
 * <ul>
 * <li> {@link Level#SEVERE SEVERE} - Initialization failures
 * <li> {@link Level#CONFIG CONFIG} - Constructor properties, data store
 *	headers
 * <li> {@link Level#FINE FINE} - Allocating blocks of object IDs
 * <li> {@link Level#FINER FINER} - Transaction operations
 * <li> {@link Level#FINEST FINEST} - Name and object operations
 * </ul> <p>
 *
 * In addition, name and object operations that throw {@link
 * TransactionAbortedException} will log the failure to the {@code Logger}
 * named {@code com.sun.sgs.impl.service.data.store.DataStoreImpl.abort}, to
 * make it easier to debug concurrency conflicts.
 */
public class DataStoreImpl extends AbstractDataStore {

    /** The name of this class. */
    private static final String CLASSNAME =
	"com.sun.sgs.impl.service.data.store.DataStoreImpl";

    /**
     * The property that specifies the directory in which to store database
     * files.
     */
    public static final String DIRECTORY_PROPERTY = CLASSNAME + ".directory";

    /** The default directory for database files from the app root. */
    private static final String DEFAULT_DIRECTORY = "dsdb";

    /** The logger for this class. */
    static final LoggerWrapper logger =
	new LoggerWrapper(Logger.getLogger(CLASSNAME));

    /** The logger for transaction abort exceptions. */
    static final LoggerWrapper abortLogger =
	new LoggerWrapper(Logger.getLogger(CLASSNAME + ".abort"));

    /** The number of bytes in a SHA-1 message digest. */
    private static final int SHA1_SIZE = 20;

    /** A message digest for use by the current thread. */
    private static final ThreadLocal<MessageDigest> messageDigest =
	new ThreadLocal<MessageDigest>() {
	    protected MessageDigest initialValue() {
		try {
		    return MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
		    throw new AssertionError(e);
		}
	    }
        };

    /** The object data for a placeholder. */
    private static final byte[] PLACEHOLDER_DATA = { PLACEHOLDER_OBJ_VALUE };

    /** The directory in which to store database files. */
    private final String directory;

    /** Stores information about transactions. */
    private final TxnInfoTable<TxnInfo> txnInfoTable;

    /** The database environment. */
    private final DbEnvironment env;

    /**
     * The database that holds version and next object ID information.  This
     * information is stored in a separate database to avoid concurrency
     * conflicts between the object ID and other data.
     */
    private final DbDatabase infoDb;

    /** The database that stores class information. */
    private final DbDatabase classesDb;

    /** The database that maps object IDs to object bytes. */
    final DbDatabase oidsDb;

    /** The database that maps name bindings to object IDs. */
    private final DbDatabase namesDb;

    /**
     * Whether object allocations should create a placeholder at the end of
     * each allocation block.  These placeholders help to avoid allocation
     * concurrency conflicts when using BDB Java edition.
     */
    final boolean useAllocationBlockPlaceholders;

    /** Information about free object IDs. */
    final FreeObjectIds freeObjectIds;

    /**
     * Object to synchronize on when accessing txnCount, allOps and
     * shuttingDown.
     */
    private final Object txnCountLock = new Object();

    /** The number of currently active transactions. */
    private int txnCount = 0;

    /** Whether the data store is in the process of shutting down. */
    private boolean shuttingDown = false;

    /**
     * Records information about all active transactions.
     *
     * @param	<T> the type of information stored for each transaction
     */
    protected interface TxnInfoTable<T> {

	/**
	 * Returns the information associated with the transaction, or null if
	 * none is found.
	 *
	 * @param	txn the transaction
	 * @return	the associated information, or null if none is found
	 * @throws	TransactionNotActiveException if the implementation
	 *              determines that the transaction is no longer active
	 * @throws	IllegalStateException if the implementation determines
	 *		that the specified transaction does not match the
	 *		current context
	 */
	T get(Transaction txn);

	/**
	 * Removes the information associated with the transaction.
	 *
	 * @param	txn the transaction
	 * @return	the previously associated information
	 * @throws	IllegalStateException if the transaction is not active,
	 *		or if the implementation determines that the specified
	 *		transaction does not match the current context
	 */
	T remove(Transaction txn);

	/**
	 * Sets the information associated with the transaction, which should
	 * not currently have associated information.
	 *
	 * @param	txn the transaction
	 * @param	info the associated information
	 */
	void set(Transaction txn, T info);
    }

    /**
     * An implementation of TxnInfoTable that uses a thread local to record
     * information about transactions, and requires that the same thread always
     * be used with a given transaction.
     */
    private static class ThreadTxnInfoTable<T> implements TxnInfoTable<T> {

	/**
	 * Provides information about the transaction for the current thread.
	 */
	private final ThreadLocal<Entry<T>> threadInfo =
	    new ThreadLocal<Entry<T>>();

	/** Stores a transaction and the associated information. */
	private static class Entry<T> {
	    final Transaction txn;
	    final T info;
	    Entry(Transaction txn, T info) {
		this.txn = txn;
		this.info = info;
	    }
	}

	/** Creates an instance. */
	ThreadTxnInfoTable() { }

	/* -- Implement TxnInfoTable -- */

	public T get(Transaction txn) {
	    Entry<T> entry = threadInfo.get();
	    if (entry == null) {
		return null;
	    } else if (!entry.txn.equals(txn)) {
		throw new IllegalStateException(
		    "Wrong transaction: Got " + txn + ", expected " +
		    entry.txn);
	    } else {
		return entry.info;
	    }
	}

	public T remove(Transaction txn) {
	    Entry<T> entry = threadInfo.get();
	    if (entry == null) {
		throw new IllegalStateException("Transaction not active");
	    } else if (!entry.txn.equals(txn)) {
		throw new IllegalStateException("Wrong transaction");
	    }
	    threadInfo.set(null);
	    return entry.info;
	}

	public void set(Transaction txn, T info) {
	    threadInfo.set(new Entry<T>(txn, info));
	}
    }

    /** Stores transaction information. */
    private class TxnInfo {

	/** The associated database transaction. */
	final DbTransaction dbTxn;

	/** Whether preparation of the transaction has started. */
	boolean prepared;

	/** Whether any changes have been made in this transaction. */
	boolean modified;

	/** The currently open cursor over the names database, or null. */
	private DbCursor namesCursor;

	/** The last key returned by the namesCursor or null. */
	private String lastNamesCursorKey;

	/** The currently open cursor over the oids database, or null. */
	private DbCursor oidsCursor;

	/** The last key returned by the oidsCursor or -1. */
	private long lastOidsCursorKey = -1;

	/**
	 * Information about object IDs available for allocation in this
	 * transaction, or null if this transaction has not done allocation.
	 */
	private ObjectIdInfo objectIdInfo = null;

	/**
	 * Object ID blocks whose last IDs were used during this transaction,
	 * or null if there were no such blocks.  The empty blocks will be
	 * rolled back on abort, and will have their placeholders removed on
	 * commit.
	 */
	private List<ObjectIdInfo> emptyObjectIdInfo = null;

	TxnInfo(Transaction txn, DbEnvironment env) {
	    dbTxn = env.beginTransaction(txn.getTimeout());
	}

	/**
	 * Prepares the transaction, first updating object ID information and
	 * closing cursors.
	 */
	void prepare(byte[] gid) {
	    prepareFreeObjectIds();
	    maybeCloseCursors(false);
	    dbTxn.prepare(gid);
	}

	/**
	 * Prepares and commits the transaction, first updating object ID
	 * information and closing cursors.
	 */
	void prepareAndCommit() {
	    prepareFreeObjectIds();
	    maybeCloseCursors(false);
	    dbTxn.commit();
	}

	/**
	 * Updates object ID information for a transaction that is going to be
	 * committed.  Returns object ID blocks that have more IDs to the free
	 * list, and updates allocation block placeholders for blocks that are
	 * empty.
	 */
	private void prepareFreeObjectIds() {
	    /* Move an empty objectIdInfo to emptyObjectIdInfo */
	    if (objectIdInfo != null && !objectIdInfo.hasNext()) {
		if (emptyObjectIdInfo == null) {
		    emptyObjectIdInfo = new LinkedList<ObjectIdInfo>();
		}
		emptyObjectIdInfo.add(objectIdInfo);
		objectIdInfo = null;
	    }
	    /*
	     * Remove placeholders for empty blocks.  Note that this operation
	     * may fail, because it operates on the database, so do it first
	     * and without holding the lock on the free object IDs.
	     */
	    if (useAllocationBlockPlaceholders && emptyObjectIdInfo != null) {
		for (ObjectIdInfo empty : emptyObjectIdInfo) {
		    long placeholder = empty.last();
		    byte[] key = DataEncoding.encodeLong(placeholder);
		    byte[] value = oidsDb.get(dbTxn, key, true);
		    if (value != null && isPlaceholderValue(value)) {
			boolean success = oidsDb.delete(dbTxn, key);
			assert success;
		    }
		}
	    }
	    freeObjectIds.prepare(objectIdInfo, emptyObjectIdInfo);
	    objectIdInfo = null;
	    emptyObjectIdInfo = null;
	}

	/**
	 * Commits the transaction, which should already have been prepared.
	 */
	void commit() {
	    dbTxn.commit();
	}

	/**
	 * Aborts the transaction, first updating object ID information and
	 * closing cursors.
	 */
	void abort() {
	    freeObjectIds.abort(objectIdInfo, emptyObjectIdInfo);
	    objectIdInfo = null;
	    emptyObjectIdInfo = null;
	    maybeCloseCursors(true);
	    dbTxn.abort();
	}

	/** Returns the next name in the names database. */
	String nextName(String name, DbDatabase names) {
	    if (namesCursor == null) {
		namesCursor = names.openCursor(dbTxn);
	    }
	    if (name == null) {
		lastNamesCursorKey = namesCursor.findFirst()
		    ? DataEncoding.decodeString(namesCursor.getKey()) : null;
	    } else {
		boolean matchesLast = name.equals(lastNamesCursorKey);
		if (!matchesLast) {
		    /*
		     * The name specified was not the last key returned, so
		     * search for the specified name
		     */
		    lastNamesCursorKey =
			namesCursor.findNext(DataEncoding.encodeString(name))
			? DataEncoding.decodeString(namesCursor.getKey())
			: null;
		    /* Record if we found an exact match */
		    matchesLast = name.equals(lastNamesCursorKey);
		}
		if (matchesLast) {
		    /* The last key was an exact match, so find the next one */
		    lastNamesCursorKey = namesCursor.findNext()
			? DataEncoding.decodeString(namesCursor.getKey())
			: null;
		}
	    }
	    return lastNamesCursorKey;
	}

	/** Returns the next object ID in the oids database. */
	long nextObjectId(long oid, DbDatabase oids) {
	    if (oidsCursor == null) {
		oidsCursor = oids.openCursor(dbTxn);
	    }
	    if (oid == -1) {
		lastOidsCursorKey = oidsCursor.findFirst()
		    ? DataEncoding.decodeLong(oidsCursor.getKey()) : -1;
	    } else {
		boolean matchesLast = (oid == lastOidsCursorKey);
		if (!matchesLast) {
		    /*
		     * The OID specified was not the last key returned, so
		     * search for the specified OID
		     */
		    lastOidsCursorKey =
			oidsCursor.findNext(DataEncoding.encodeLong(oid))
			? DataEncoding.decodeLong(oidsCursor.getKey()) : -1;
		    /* Record if we found an exact match */
		    matchesLast = (oid == lastOidsCursorKey);
		}
		if (matchesLast) {
		    /* The last key was an exact match, so find the next one */
		    lastOidsCursorKey = oidsCursor.findNext()
			? DataEncoding.decodeLong(oidsCursor.getKey()) : -1;
		}
	    }
	    /* Skip placeholders */
	    while (lastOidsCursorKey != -1 &&
		   isPlaceholderValue(oidsCursor.getValue()))
	    {
		lastOidsCursorKey = oidsCursor.findNext()
		    ? DataEncoding.decodeLong(oidsCursor.getKey()) : -1;
	    }
	    return lastOidsCursorKey;
	}

	/**
	 * Close the cursors if they are open.  Always null the cursor fields,
	 * since the Berkeley DB API doesn't permit closing a cursor after an
	 * attempt to close it.  If forAbort is true, then we are aborting the
	 * transaction.  In that case, ignore abort exceptions when closing the
	 * cursors, to make sure we complete the operations needed on abort.
	 */
	private void maybeCloseCursors(boolean forAbort) {
	    if (namesCursor != null) {
		try {
		    namesCursor.close();
		} catch (TransactionAbortedException e) {
		    if (forAbort) {
			logger.logThrow(
			    Level.FINEST, e,
			    "Exception closing names cursor during abort");
		    } else {
			throw e;
		    }
		} finally {
		    namesCursor = null;
		}
	    }
	    if (oidsCursor != null) {
		try {
		    oidsCursor.close();
		} catch (TransactionAbortedException e) {
		    if (forAbort) {
			logger.logThrow(
			    Level.FINEST, e,
			    "Exception closing OIDs cursor during abort");
		    } else {
			throw e;
		    }
		} finally {
		    oidsCursor = null;
		}
	    }
	}

	/**
	 * Returns information about free object IDs available for allocation
	 * in this transaction, or null if no IDs are already available.
	 */
	ObjectIdInfo getObjectIdInfo() {
	    if (objectIdInfo == null) {
		objectIdInfo = freeObjectIds.get();
		if (objectIdInfo != null) {
		    objectIdInfo.initTxn();
		}
	    } else if (!objectIdInfo.hasNext()) {
		if (emptyObjectIdInfo == null) {
		    emptyObjectIdInfo = new LinkedList<ObjectIdInfo>();
		}
		emptyObjectIdInfo.add(objectIdInfo);
		objectIdInfo = null;
	    }
	    return objectIdInfo;
	}

	/**
	 * Creates and stores information about newly available free object
	 * IDs.
	 */
	ObjectIdInfo createObjectIdInfo(
	    long firstObjectId, long lastObjectId)
	{
	    assert objectIdInfo == null;
	    objectIdInfo = freeObjectIds.create(firstObjectId, lastObjectId);
	    return objectIdInfo;
	}
    }

    /** The default implementation of Scheduler. */
    private static class BasicScheduler implements Scheduler {
	public TaskHandle scheduleRecurringTask(Runnable task, long period) {
	    final ScheduledExecutorService executor =
		Executors.newSingleThreadScheduledExecutor();
	    executor.scheduleAtFixedRate(
		task, period, period, TimeUnit.MILLISECONDS);
	    return new TaskHandle() {
		public synchronized void cancel() {
		    if (executor.isShutdown()) {
			throw new IllegalStateException(
			    "Task is already cancelled");
		    }
		    executor.shutdownNow();
		}
	    };
	}
    }

    /**
     * Stores information about the databases that constitute the data
     * store.
     */
    private static class Databases {
	DbDatabase info, classes, oids, names;
    }

    /** Stores information about free object IDs. */
    private static final class FreeObjectIds {

	/**
	 * Available allocation blocks, with the lowest ID block first.
	 * Synchronize on the FreeObjectIds instance when accessing this field.
	 */
	private final Queue<ObjectIdInfo> freeObjectIdInfo =
	    new PriorityQueue<ObjectIdInfo>();

	/**
	 * The set of object IDs of placeholders for allocation blocks that are
	 * still in use, or null if placeholders are not being used.
	 * Synchronize on the FreeObjectIds instance when accessing the
	 * contents of this field.
	 */
	private final SortedSet<Long> placeholderOids;

	/** Creates an instance of this class. */
	FreeObjectIds(boolean usePlaceholders) {
	    placeholderOids = usePlaceholders ? new TreeSet<Long>() : null;
	}

	/** Obtains a block of object IDs, or null if none are available. */
	synchronized ObjectIdInfo get() {
	    return freeObjectIdInfo.poll();
	}

	/**
	 * Updates object ID information for a transaction that is going to be
	 * committed.  Returns the object ID block, if not null, to the free
	 * list, and updates placeholders for the empty blocks.
	 */
	synchronized void prepare(ObjectIdInfo info,
				  List<ObjectIdInfo> emptyObjectIdInfo)
	{
	    if (info != null) {
		assert info.hasNext();
		freeObjectIdInfo.add(info);
	    }
	    if (placeholderOids != null && emptyObjectIdInfo != null) {
		for (ObjectIdInfo empty : emptyObjectIdInfo) {
		    placeholderOids.remove(empty.last());
		}
	    }
	}

	/**
	 * Updates object ID information for a transaction that is being
	 * aborted.  Rolls back the allocations in all blocks.
	 */
	synchronized void abort(ObjectIdInfo info,
				List<ObjectIdInfo> emptyObjectIdInfo)
	{
	    if (info != null) {
		info.abort();
		freeObjectIdInfo.add(info);
	    }
	    if (emptyObjectIdInfo != null) {
		for (ObjectIdInfo empty : emptyObjectIdInfo) {
		    empty.abort();
		    freeObjectIdInfo.add(empty);
		}
	    }
	}

	/** Creates and returns a new block of object IDs. */
	ObjectIdInfo create(long firstObjectId, long lastObjectId) {
	    assert firstObjectId >= 0;
	    assert lastObjectId > firstObjectId;
	    ObjectIdInfo info = new ObjectIdInfo(firstObjectId, lastObjectId);
	    if (placeholderOids != null) {
		synchronized (this) {
		    placeholderOids.add(lastObjectId);
		}
	    }
	    return info;
	}

	/**
	 * Returns the object ID of the lowest-numbered object allocation block
	 * placeholder currently in use, or -1 if none.  This method should
	 * only be called if placeholders are in use.
	 */
	synchronized long getFirstPlaceholder() {
	    return placeholderOids.isEmpty() ? -1 : placeholderOids.first();
	}
    }

    /** Stores information about object IDs available for allocation. */
    private static final class ObjectIdInfo
	implements Comparable<ObjectIdInfo>
    {
	/** The first object ID in this block. */
	private final long firstObjectId;

	/**
	 * The next object ID to use for creating an object.  Valid if not
	 * greater than lastObjectId.
	 */
	private long nextObjectId;

	/**
	 * The last object ID that is free for allocating an object before
	 * needing to obtain more IDs from the database.
	 */
	private final long lastObjectId;

	/**
	 * The value of nextObjectId at the start of the transaction, and which
	 * it should be set to if the transaction aborts.
	 */
	private long abortNextObjectId;

	/**
	 * Creates an instance with the specified first and last object IDs.
	 */
	ObjectIdInfo(long firstObjectId, long lastObjectId) {
	    this.firstObjectId = firstObjectId;
	    nextObjectId = firstObjectId;
	    this.lastObjectId = lastObjectId;
	    abortNextObjectId = nextObjectId;
	}

	/** Implement Comparable<ObjectIdInfo>, ordered by object ID. */
	public int compareTo(ObjectIdInfo other) {
	    return Long.signum(lastObjectId - other.lastObjectId);
	}

	/** Use object ID for comparison. */
	public boolean equals(Object object) {
	    return object instanceof ObjectIdInfo &&
		lastObjectId == ((ObjectIdInfo) object).lastObjectId;
	}

	/** Use object ID for hash code. */
	public int hashCode() {
	    return (int) (lastObjectId >> 32) & (int) lastObjectId;
	}

	/**
	 * Records the initial value of nextObjectId, so that it can be rolled
	 * back if the transaction aborts.
	 */
	void initTxn() {
	    abortNextObjectId = nextObjectId;
	}

	/** Returns whether there are more object IDs available. */
	boolean hasNext() {
	    return nextObjectId <= lastObjectId;
	}

	/** Returns the next object ID. */
	long next() {
	    assert hasNext();
	    return nextObjectId++;
	}

	/**
	 * Sets nextObjectId back to the value from the start of the
	 * transaction, for when the transaction aborts.
	 */
	void abort() {
	    nextObjectId = abortNextObjectId;
	}

	/** Returns the first object ID in this block. */
	long first() {
	    return firstObjectId;
	}

	/** Returns the last object ID in this block. */
	long last() {
	    return lastObjectId;
	}
    }

    /**
     * Creates an instance of this class configured with the specified
     * properties and access coordinator.  See the {@linkplain DataStoreImpl
     * class documentation} for a list of supported properties.
     *
     * @param	properties the properties for configuring this instance
     * @param	accessCoordinator the access coordinator
     * @throws	DataStoreException if there is a problem with the database
     * @throws	IllegalArgumentException if any of the properties are invalid,
     *		as specified in the class documentation
     */
    public DataStoreImpl(Properties properties,
			 AccessCoordinator accessCoordinator)
    {
	this(properties, accessCoordinator, new BasicScheduler());
    }

    /**
     * Creates an instance of this class configured with the specified
     * properties, access coordinator, and scheduler.  See the {@linkplain
     * DataStoreImpl class documentation} for a list of supported properties.
     *
     * @param	properties the properties for configuring this instance
     * @param	accessCoordinator the access coordinator
     * @param	scheduler the scheduler used to schedule periodic tasks
     * @throws	DataStoreException if there is a problem with the database
     * @throws	IllegalArgumentException if any of the properties are invalid,
     *		as specified in the class documentation}
     */
    public DataStoreImpl(Properties properties, 
			 AccessCoordinator accessCoordinator,
			 Scheduler scheduler)
    {
	super(accessCoordinator, logger, abortLogger);
	logger.log(
	    Level.CONFIG, "Creating DataStoreImpl properties:{0}", properties);
	PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);
	String specifiedDirectory =
	    wrappedProps.getProperty(DIRECTORY_PROPERTY);
	if (specifiedDirectory == null) {
	    String rootDir =
		properties.getProperty(StandardProperties.APP_ROOT);
	    if (rootDir == null) {
		throw new IllegalArgumentException(
		    "A value for the property " + StandardProperties.APP_ROOT +
		    " must be specified");
	    }
	    specifiedDirectory = rootDir + File.separator + DEFAULT_DIRECTORY;
	}
	/*
	 * Use an absolute path to avoid problems on Windows.
	 * -tjb@sun.com (02/16/2007)
	 */
	directory = new File(specifiedDirectory).getAbsolutePath();
	txnInfoTable = getTxnInfoTable(TxnInfo.class);
	DbTransaction dbTxn = null;
	boolean done = false;
	try {
	    File directoryFile = new File(specifiedDirectory).getAbsoluteFile();
            if (!directoryFile.exists()) {
                logger.log(Level.INFO, "Creating database directory : " +
                           directoryFile.getAbsolutePath());
                if (!directoryFile.mkdirs()) {
                    throw new DataStoreException("Unable to create database " +
                                                 "directory : " +
                                                 directoryFile.getName());
                }
	    }
	    env = DbEnvironmentFactory.getEnvironment(
		directory, properties, scheduler);
	    dbTxn = env.beginTransaction(Long.MAX_VALUE);
	    Databases dbs = getDatabases(dbTxn);
	    infoDb = dbs.info;
	    classesDb = dbs.classes;
	    oidsDb = dbs.oids;
	    namesDb = dbs.names;
	    useAllocationBlockPlaceholders =
		env.useAllocationBlockPlaceholders();
	    freeObjectIds = new FreeObjectIds(useAllocationBlockPlaceholders);
	    removeUnusedAllocationPlaceholders(dbTxn);
	    done = true;
	    dbTxn.commit();
	} catch (RuntimeException e) { 
	    throw handleException(
		null, Level.SEVERE, e, "DataStore initialization");
	} catch (Error e) {
	    logger.logThrow(
		Level.SEVERE, e, "DataStore initialization failed");
	    throw e;
	} finally {
	    if (dbTxn != null && !done) {
		try {
		    dbTxn.abort();
		} catch (RuntimeException e) {
		    logger.logThrow(Level.FINE, e, "Exception during abort");
		}
	    }
	}
    }

    /**
     * Opens or creates the Berkeley DB databases associated with this data
     * store.
     */
    private Databases getDatabases(DbTransaction dbTxn) {
	Databases dbs = new Databases();
	boolean create = false;
	try {
	    dbs.info = env.openDatabase(dbTxn, "info", false);
	    int minorVersion = DataStoreHeader.verify(dbs.info, dbTxn);
	    if (logger.isLoggable(Level.CONFIG)) {
		logger.log(Level.CONFIG, "Found existing header {0}",
			   DataStoreHeader.headerString(minorVersion));
	    }
	} catch (FileNotFoundException e) {
	    try {
		dbs.info = env.openDatabase(dbTxn, "info", true);
	    } catch (FileNotFoundException e2) {
		throw new DataStoreException(
		    "Problem creating database: " + e2.getMessage(), e2);
	    }
	    DataStoreHeader.create(dbs.info, dbTxn);
	    if (logger.isLoggable(Level.CONFIG)) {
		logger.log(Level.CONFIG, "Created new header {0}",
			   DataStoreHeader.headerString());
	    }
	    create = true;
	}
	try {
	    dbs.classes = env.openDatabase(dbTxn, "classes", create);
	} catch (FileNotFoundException e) {
	    throw new DataStoreException(
		"Classes database not found: " + e.getMessage(), e);
	}
	try {
	    dbs.oids = env.openDatabase(dbTxn, "oids", create);
	} catch (FileNotFoundException e) {
	    throw new DataStoreException(
		"Oids database not found: " + e.getMessage(), e);
	}
	try {
	    dbs.names = env.openDatabase(dbTxn, "names", create);
	} catch (FileNotFoundException e) {
	    throw new DataStoreException(
		"Names database not found: " + e.getMessage(), e);
	}
	return dbs;
    }

    /**
     * Removes any unused allocation block placeholders and updates the ID of
     * the first placeholder.
     */
    private void removeUnusedAllocationPlaceholders(DbTransaction dbTxn) {
	byte[] firstPlaceholderKey =
	    DataEncoding.encodeLong(FIRST_PLACEHOLDER_ID_KEY);
	long placeholderOid = DataEncoding.decodeLong(
	    infoDb.get(dbTxn, firstPlaceholderKey, true));
	if (placeholderOid < 0) {
	    logger.log(Level.FINEST, "No allocation placeholders");
	    return;
	}
	DbCursor cursor = oidsDb.openCursor(dbTxn);
	try {
	    while (cursor.findNext(DataEncoding.encodeLong(placeholderOid))) {
		byte[] key = cursor.getKey();
		if (DataEncoding.decodeLong(key) != placeholderOid) {
		    if (logger.isLoggable(Level.FINEST)) {
			logger.log(Level.FINEST,
				   "Placeholder oid:{0,number,#} not found",
				   placeholderOid);
		    }
		} else if (isPlaceholderValue(cursor.getValue())) {
		    boolean success = oidsDb.delete(dbTxn, key);
		    assert success;
		    if (logger.isLoggable(Level.FINEST)) {
			logger.log(Level.FINEST,
				   "Removed placeholder at oid:{0,number,#}",
				   placeholderOid);
		    }
		} else {
		    if (logger.isLoggable(Level.FINEST)) {
			logger.log(Level.FINEST,
				   "Ignoring oid:{0,number,#} that does not" +
				   " refer to a placeholder",
				   placeholderOid);
		    }
		}
		placeholderOid += ALLOCATION_BLOCK_SIZE;
	    }
	    infoDb.put(
		dbTxn, firstPlaceholderKey, DataEncoding.encodeLong(-1));
	} finally {
	    cursor.close();
	}
    }

    /* -- Implement AbstractDataStore's DataStore methods -- */

    /** {@inheritDoc} */
    protected long createObjectInternal(Transaction txn) {
	TxnInfo txnInfo = checkTxn(txn);
	ObjectIdInfo objectIdInfo = txnInfo.getObjectIdInfo();
	if (objectIdInfo == null) {
	    logger.log(Level.FINE, "Allocate more object IDs");
	    long newNextObjectId;
	    long newLastObjectId;
	    DbTransaction dbTxn = env.beginTransaction(txn.getTimeout());
	    boolean done = false;
	    try {
		newNextObjectId = DataStoreHeader.getNextId(
		    NEXT_OBJ_ID_KEY, infoDb, dbTxn, ALLOCATION_BLOCK_SIZE);
		newLastObjectId =
		    newNextObjectId + ALLOCATION_BLOCK_SIZE - 1;
		maybeUpdateAllocationBlockPlaceholders(
		    dbTxn, newLastObjectId);
		done = true;
		dbTxn.commit();
	    } finally {
		if (!done) {
		    dbTxn.abort();
		}
	    }
	    objectIdInfo = txnInfo.createObjectIdInfo(
		newNextObjectId, newLastObjectId);
	}
	long result = objectIdInfo.next();
	if (useAllocationBlockPlaceholders && result == objectIdInfo.first()) {
	    /*
	     * Create the placeholder when using the first ID in the block.
	     * Don't do this when storing the allocation and placeholder
	     * information in the info database because this transaction may
	     * already be holding a lock on the location in the OIDs database.
	     */
	    oidsDb.put(txnInfo.dbTxn,
		       DataEncoding.encodeLong(objectIdInfo.last()),
		       PLACEHOLDER_DATA);
	}
	return result;
    }

    /** {@inheritDoc} */
    protected void markForUpdateInternal(Transaction txn, long oid) {
	TxnInfo txnInfo = checkTxn(txn);
	oidsDb.markForUpdate(txnInfo.dbTxn, DataEncoding.encodeLong(oid));
    }

    /** {@inheritDoc} */
    protected byte[] getObjectInternal(
	Transaction txn, long oid, boolean forUpdate)
    {
	TxnInfo txnInfo = checkTxn(txn);
	byte[] result = oidsDb.get(
	    txnInfo.dbTxn, DataEncoding.encodeLong(oid), forUpdate);
	if (result == null || isPlaceholderValue(result)) {
	    throw new ObjectNotFoundException("Object not found: " + oid);
	}
	return decodeValue(result);
    }

    /** {@inheritDoc} */
    protected void setObjectInternal(Transaction txn, long oid, byte[] data) {
	TxnInfo txnInfo = checkTxn(txn);
	oidsDb.put(
	    txnInfo.dbTxn, DataEncoding.encodeLong(oid), encodeValue(data));
	txnInfo.modified = true;
    }

    /** {@inheritDoc} */
    protected void setObjectsInternal(
	Transaction txn, long[] oids, byte[][] dataArray)
    {
	TxnInfo txnInfo = checkTxn(txn);
	for (int i = 0; i < oids.length; i++) {
	    oidsDb.put(txnInfo.dbTxn, DataEncoding.encodeLong(oids[i]),
		       encodeValue(dataArray[i]));
	}
	txnInfo.modified = true;
    }

    /** {@inheritDoc} */
    protected void removeObjectInternal(Transaction txn, long oid) {
	TxnInfo txnInfo = checkTxn(txn);
	byte[] key = DataEncoding.encodeLong(oid);
	boolean found = oidsDb.delete(txnInfo.dbTxn, key);
	if (!found) {
	    throw new ObjectNotFoundException("Object not found: " + oid);
	}
	txnInfo.modified = true;
    }

    /** {@inheritDoc} */
    protected BindingValue getBindingInternal(Transaction txn, String name) {
	TxnInfo txnInfo = checkTxn(txn);
	byte[] value = namesDb.get(
	    txnInfo.dbTxn, DataEncoding.encodeString(name), false);
	if (value == null) {
	    return new BindingValue(-1, txnInfo.nextName(name, namesDb));
	} else {
	    return new BindingValue(DataEncoding.decodeLong(value), null);
	}
    }

    /** {@inheritDoc} */
    protected BindingValue setBindingInternal(
	Transaction txn, String name, long oid)
    {
	TxnInfo txnInfo = checkTxn(txn);
	byte[] key = DataEncoding.encodeString(name);
	byte[] oldValue = namesDb.get(txnInfo.dbTxn, key, true);
	namesDb.put(txnInfo.dbTxn, key, DataEncoding.encodeLong(oid));
	txnInfo.modified = true;
	if (oldValue != null) {
	    return new BindingValue(1, null);
	} else {
	    return new BindingValue(-1, txnInfo.nextName(name, namesDb));
	}
    }

    /** {@inheritDoc} */
    protected BindingValue removeBindingInternal(
	Transaction txn, String name)
    {
	TxnInfo txnInfo = checkTxn(txn);
	boolean found = namesDb.delete(
	    txnInfo.dbTxn, DataEncoding.encodeString(name));
	if (found) {
	    txnInfo.modified = true;
	    return new BindingValue(1, txnInfo.nextName(name, namesDb));
	} else {
	    return new BindingValue(-1, txnInfo.nextName(name, namesDb));
	}
    }
    
    /**
     * {@inheritDoc} <p>
     *
     * This implementation uses a single cursor, so it provides better
     * performance when used to iterate over names in order.
     */
    protected String nextBoundNameInternal(Transaction txn, String name) {
	TxnInfo txnInfo = checkTxn(txn);
	return txnInfo.nextName(name, namesDb);
    }

    /** {@inheritDoc} */
    protected void shutdownInternal() {
	synchronized (txnCountLock) {
	    shuttingDown = true;
	    while (txnCount > 0) {
		try {
		    logger.log(Level.FINEST,
			       "shutdown waiting for {0} transactions",
			       txnCount);
		    txnCountLock.wait();
		} catch (InterruptedException e) {
		    // loop until shutdown is complete
		    logger.log(Level.FINEST, "DataStore shutdown " +
			       "interrupt ignored");
		}
	    }
	    if (txnCount < 0) {
		return; // return silently
	    }
	    
	    infoDb.close();
	    classesDb.close();
	    oidsDb.close();
	    namesDb.close();
	    env.close();
	    txnCount = -1;
	}
    }

    /** {@inheritDoc} */
    protected int getClassIdInternal(Transaction txn, byte[] classInfo) {
	checkTxn(txn);
	byte[] hashKey = getKeyFromClassInfo(classInfo);
	boolean done = false;
	/*
	 * Use a separate transaction when obtaining the class ID so that
	 * the ID will be available for other transactions to use right
	 * away.  This approach means that the class info will be
	 * registered even if the main transaction fails.  If any
	 * transaction wants to register a new class, though, it's very
	 * likely that the class will be needed, even if that transaction
	 * aborts, so it makes sense to commit this operation separately to
	 * improve concurrency.  -tjb@sun.com (05/23/2007)
	 *
	 * Use full transaction isolation to insure consistency when
	 * concurrently allocating new class IDs.  -tjb@sun.com (03/17/2009)
	 */
	DbTransaction dbTxn = env.beginTransaction(txn.getTimeout(), true);
	try {
	    int result;
	    byte[] hashValue = classesDb.get(dbTxn, hashKey, false);
	    if (hashValue != null) {
		result = DataEncoding.decodeInt(hashValue);
	    } else {
		DbCursor cursor = classesDb.openCursor(dbTxn);
		try {
		    result = cursor.findLast()
			? getClassIdFromKey(cursor.getKey()) + 1 : 1; 
		    byte[] idKey = getKeyFromClassId(result);
		    boolean success =
			cursor.putNoOverwrite(idKey, classInfo);
		    if (!success) {
			throw new DataStoreException(
			    "Class ID key already present");
		    }
		} finally {
		    cursor.close();
		}
		boolean success = classesDb.putNoOverwrite(
		    dbTxn, hashKey, DataEncoding.encodeInt(result));
		if (!success) {
		    throw new DataStoreException(
			"Class hash already present");
		}
	    }
	    done = true;
	    dbTxn.commit();
	    return result;
	} finally {
	    if (!done) {
		dbTxn.abort();
	    }
	}
    }

    /** {@inheritDoc} */
    protected byte[] getClassInfoInternal(Transaction txn, int classId)
	throws ClassInfoNotFoundException
    {
	checkTxn(txn);
	byte[] key = getKeyFromClassId(classId);
	byte[] result;
	boolean done = false;
	DbTransaction dbTxn = env.beginTransaction(txn.getTimeout());
	try {
	    result = classesDb.get(dbTxn, key, false);
	    done = true;
	    dbTxn.commit();
	} finally {
	    if (!done) {
		dbTxn.abort();
	    }
	}
	if (result != null) {
	    return result;
	} else {
	    throw new ClassInfoNotFoundException(
		"No information found for class ID " + classId);
	}
    }

    /** {@inheritDoc} */
    protected long nextObjectIdInternal(Transaction txn, long oid) {
	TxnInfo txnInfo = checkTxn(txn);
	return txnInfo.nextObjectId(oid, oidsDb);
    }

    /* -- Implement AbstractDataStore's TransactionParticipant methods -- */

    /** {@inheritDoc} */
    protected boolean prepareInternal(Transaction txn) {
	TxnInfo txnInfo = checkTxnNoJoin(txn);
	txn.checkTimeout();
	if (txnInfo.prepared) {
	    throw new IllegalStateException(
		"Transaction has already been prepared");
	}
	if (txnInfo.modified) {
	    byte[] tid = txn.getId();
	    /*
	     * Berkeley DB requires transaction IDs to be at least 128 bytes
	     * long.  -tjb@sun.com (11/07/2006)
	     */
	    byte[] gid = new byte[128];
	    /*
	     * The current transaction implementation uses 8 byte transaction
	     * IDs.  -tjb@sun.com (03/22/2007)
	     */
	    assert tid.length < 128 : "Transaction ID is too long";
	    System.arraycopy(tid, 0, gid, 128 - tid.length, tid.length);
	    txnInfo.prepare(gid);
	    txnInfo.prepared = true;
	} else {
	    /*
	     * Make sure to clear the transaction information, regardless of
	     * whether the Berkeley DB commit operation succeeds, since
	     * Berkeley DB doesn't permit operating on its transaction object
	     * after commit is called.
	     */
	    try {
		txnInfoTable.remove(txn);
		txnInfo.prepareAndCommit();
	    } finally {
		decrementTxnCount();
	    } 
	}
	return !txnInfo.modified;
    }

    /** {@inheritDoc} */
    protected void commitInternal(Transaction txn) {
	TxnInfo txnInfo = checkTxnNoJoin(txn);
	if (!txnInfo.prepared) {
	    throw new IllegalStateException(
		"Transaction has not been prepared");
	}
	/*
	 * Make sure to clear the transaction information, regardless of
	 * whether the Berkeley DB commit operation succeeds, since Berkeley DB
	 * doesn't permit operating on its transaction object after commit is
	 * called.
	 */
	txnInfoTable.remove(txn);
	try {
	    txnInfo.commit();
	} finally {
	    decrementTxnCount();
	}
    }

    /** {@inheritDoc} */
    protected void prepareAndCommitInternal(Transaction txn) {
	TxnInfo txnInfo = checkTxnNoJoin(txn);
	txn.checkTimeout();
	if (txnInfo.prepared) {
	    throw new IllegalStateException(
		"Transaction has already been prepared");
	}
	/*
	 * Make sure to clear the transaction information, regardless of
	 * whether the Berkeley DB commit operation succeeds, since Berkeley DB
	 * doesn't permit operating on its transaction object after commit is
	 * called.
	 */
	txnInfoTable.remove(txn);
	try {
	    txnInfo.prepareAndCommit();
	} finally {
	    decrementTxnCount();
	}
    }

    /** {@inheritDoc} */
    protected void abortInternal(Transaction txn) {
	checkNull("txn", txn);
	TxnInfo txnInfo = txnInfoTable.remove(txn);
	if (txnInfo == null) {
	    throw new IllegalStateException("Transaction is not active");
	}
	try {
	    txnInfo.abort();
	} finally {
	    decrementTxnCount();
	}
    }
    
    /* -- Other AbstractDataStore methods -- */

    /**
     * {@inheritDoc} <p>
     *
     * This implementation converts {@link DbDatabaseException} to {@link
     * DataStoreException}.
     */
    @Override
    protected RuntimeException handleException(
	Transaction txn, Level level, RuntimeException e, String operation)
    {
	if (e instanceof DbDatabaseException) {
	    e = new DataStoreException(
		operation + " failed: " + e.getMessage(), e);
	}
	return super.handleException(txn, level, e, operation);
    }

    /* -- Other public methods -- */

    /**
     * Returns a string representation of this object.
     *
     * @return	a string representation of this object
     */
    public String toString() {
	return "DataStoreImpl[directory=\"" + directory + "\"]";
    }

    /* -- Protected methods -- */

    /**
     * Returns the table that will be used to store transaction information.
     * Note that this method will be called during instance construction.
     *
     * @param	<T> the type of the information to be stored
     * @param	txnInfoType a class representing the type of the information to
     *		be stored
     * @return	the table
     */
    protected <T> TxnInfoTable<T> getTxnInfoTable(Class<T> txnInfoType) {
	return new ThreadTxnInfoTable<T>();
    }

    /**
     * Returns the next available transaction ID, and reserves the specified
     * number of IDs.
     *
     * @param	count the number of IDs to reserve
     * @param	timeout the transaction timeout in milliseconds
     * @return	the next available transaction ID
     */
    protected long getNextTxnId(int count, long timeout) {
	try {
	    return getNextId(DataStoreHeader.NEXT_TXN_ID_KEY, count, timeout);
	} catch (RuntimeException e) {
	    throw handleException(
		null, Level.FINE, e, "getNextTxnId count:" + count);
	}
    }

    /**
     * Explicitly joins a new transaction.
     *
     * @param	txn the transaction to join
     */
    protected void joinNewTransaction(Transaction txn) {
	try {
	    joinTransaction(txn);
	} catch (RuntimeException e) {
	    throw handleException(
		txn, Level.FINER, e, "joinNewTransaction txn:" + txn);
	}
    }

    /* -- Private methods -- */

    /**
     * Checks that the correct transaction is in progress, and join if none is
     * in progress.  The op argument, if non-null, specifies the operation
     * being performed under the specified transaction.
     */
    private TxnInfo checkTxn(Transaction txn) {
	if (txn == null) {
	    throw new NullPointerException("Transaction must not be null");
	}
	TxnInfo txnInfo = txnInfoTable.get(txn);
	if (txnInfo == null) {
	    txnInfo = joinTransaction(txn);
	} else if (txnInfo.prepared) {
	    throw new IllegalStateException(
		"Transaction has been prepared");
	}
	return txnInfo;
    }

    /**
     * Joins the specified transaction, checking first to see if the data store
     * is currently shutting down, and returning the new TxnInfo.
     */
    private TxnInfo joinTransaction(Transaction txn) {
	synchronized (txnCountLock) {
	    if (txnCount < 0) {
		throw new IllegalStateException("Service is shut down");
	    } else if (shuttingDown) {
		throw new IllegalStateException("Service is shutting down");
	    }
	    txnCount++;
	}
	boolean joined = false;
	try {
	    txn.join(this);
	    joined = true;
	    if (logger.isLoggable(Level.FINER)) {
		logger.log(Level.FINER, "join txn:{0}, thread:{1}",
			   txn, Thread.currentThread().getName());
	    }
	} finally {
	    if (!joined) {
		decrementTxnCount();
	    }
	}
	TxnInfo txnInfo = new TxnInfo(txn, env);
	txnInfoTable.set(txn, txnInfo);
	return txnInfo;
    }

    /**
     * Checks that the correct transaction is in progress, throwing an
     * exception if the transaction has not been joined.  Checks if the store
     * is shutting down, but does not check the prepared state of the
     * transaction.
     */
    private TxnInfo checkTxnNoJoin(Transaction txn) {
	if (txn == null) {
	    throw new NullPointerException("Transaction must not be null");
	}
	TxnInfo txnInfo = txnInfoTable.get(txn);
	if (txnInfo == null) {
	    throw new IllegalStateException("Transaction is not active");
	} else if (getTxnCount() < 0) {
	    throw new IllegalStateException("DataStore is shutting down");
	}
	return txnInfo;
    }

    /** Returns the current transaction count. */
    private int getTxnCount() {
	synchronized (txnCountLock) {
	    return txnCount;
	}
    }

    /**
     * Decrements the current transaction count.  If the argument is not null,
     * tallies the operations that were recorded for the transaction.
     */
    private void decrementTxnCount() {
	synchronized (txnCountLock) {
	    txnCount--;
	    if (txnCount <= 0) {
		txnCountLock.notifyAll();
	    }
	}
    }

    /**
     * Returns the next available ID stored under the specified key, and
     * increments the stored value by the specified amount.  Uses the specified
     * timeout when creating a DB transaction.
     */
    private long getNextId(long key, int blockSize, long timeout) {
	DbTransaction dbTxn = env.beginTransaction(timeout);
	boolean done = false;
	try {
	    long id = DataStoreHeader.getNextId(
		key, infoDb, dbTxn, blockSize);
	    done = true;
	    dbTxn.commit();
	    return id;
	} finally {
	    if (!done) {
		dbTxn.abort();
	    }
	}
    }

    /** Converts a database key to a class ID. */
    private static int getClassIdFromKey(byte[] key) {
	assert key[0] == DataStoreHeader.CLASS_ID_PREFIX;
	return DataEncoding.decodeInt(key, 1);
    }

    /** Converts a class ID to a database key. */
    private static byte[] getKeyFromClassId(int classId) {
	byte[] key = new byte[5];
	key[0] = DataStoreHeader.CLASS_ID_PREFIX;
	DataEncoding.encodeInt(classId, key, 1);
	return key;
    }

    /** Converts class information to a database key. */
    private byte[] getKeyFromClassInfo(byte[] classInfo) {
	byte[] keyBytes = new byte[1 + SHA1_SIZE];
	keyBytes[0] = DataStoreHeader.CLASS_HASH_PREFIX;
	MessageDigest md = messageDigest.get();
	try {
	    md.update(classInfo);
	    int numBytes = md.digest(keyBytes, 1, SHA1_SIZE);
	    assert numBytes == SHA1_SIZE;
	    return keyBytes;
	} catch (DigestException e) {
	    throw new AssertionError(e);
	}
    }

    /**
     * Checks if an object value read from the database is from a placeholder,
     * meaning there is no object present.
     */
    static boolean isPlaceholderValue(byte[] value) {
	return value.length > 0 && value[0] == PLACEHOLDER_OBJ_VALUE;
    }

    /**
     * Encodes an object value for writing to the database to quote it's first
     * byte if it would conflict with PLACEHOLDER_OBJ_VALUE or QUOTE_OBJ_VALUE.
     */
    private static byte[] encodeValue(byte[] value) {
	if (value.length > 0) {
	    if (value[0] == PLACEHOLDER_OBJ_VALUE ||
		value[0] == QUOTE_OBJ_VALUE)
	    {
		byte[] result = new byte[value.length + 1];
		result[0] = QUOTE_OBJ_VALUE;
		System.arraycopy(value, 0, result, 1, value.length);
		return result;
	    }
	}
	return value;
    }

    /**
     * Decodes an object value read from the database to account for quoting of
     * its first byte.  Throws an exception if the value represents a
     * placeholder.
     */
    private static byte[] decodeValue(byte[] value) {
	if (value.length > 0) {
	    if (value[0] == PLACEHOLDER_OBJ_VALUE) {
		throw new IllegalArgumentException(
		    "Attempt to decode a placeholder as an object value");
	    } else if (value[0] == QUOTE_OBJ_VALUE) {
		byte[] result = new byte[value.length - 1];
		System.arraycopy(value, 1, result, 0, value.length - 1);
		return result;
	    }
	}
	return value;
    }

    /**
     * Notes the first placeholder when starting to use a new allocation block
     * with the specified object ID at its end, if using allocation block
     * placeholders.
     */
    private void maybeUpdateAllocationBlockPlaceholders(
	DbTransaction dbTxn, long placeholderOid)
    {
	if (useAllocationBlockPlaceholders) {
	    long firstPlaceholderOid = freeObjectIds.getFirstPlaceholder();
	    if (firstPlaceholderOid == -1) {
		firstPlaceholderOid = placeholderOid;
	    }
	    infoDb.put(dbTxn,
		       DataEncoding.encodeLong(FIRST_PLACEHOLDER_ID_KEY),
		       DataEncoding.encodeLong(firstPlaceholderOid));
	    if (logger.isLoggable(Level.FINEST)) {
		logger.log(Level.FINEST,
			   "Note first placeholder oid:{0,number,#}",
			   firstPlaceholderOid);
	    }
	}
    }

    /**
     * Store raw data for the specified object ID.  The value is used as the
     * literal data, without checking for placeholders or quoted values.  This
     * method is intended for testing.
     *
     * @param	txn the transaction under which the operation should take place
     * @param	oid the object ID
     * @param	data the data
     */
    void setObjectRaw(Transaction txn, long oid, byte[] data) {
	TxnInfo txnInfo = checkTxn(txn);
	oidsDb.put(txnInfo.dbTxn, DataEncoding.encodeLong(oid), data);
    }

    /**
     * Get raw data for the specified object ID.  The value returned is the
     * literal data, without checking for placeholders or quoted values.  This
     * method is intended for testing.
     *
     * @param	txn the transaction under which the operation should take place
     * @param	oid the object ID
     * @return	the data or null if the object ID is not found
     */
    byte[] getObjectRaw(Transaction txn, long oid) {
	TxnInfo txnInfo = checkTxn(txn);
	return oidsDb.get(txnInfo.dbTxn, DataEncoding.encodeLong(oid), false);
    }

    /**
     * Gets the next object ID after the one specified, or -1 if there are no
     * more objects.  If oid is -1, then returns the first object ID.  The
     * values for the object IDs will not be checked, so this method can be
     * used to obtain object IDs for placeholders.  This method is intended for
     * testing.
     *
     * @param	txn the transaction under which the operation should take place
     * @param	oid the object ID or -1
     * @return	the next object ID or -1
     */
    long nextObjectIdRaw(Transaction txn, long oid) {
	TxnInfo txnInfo = checkTxn(txn);
	DbCursor cursor = oidsDb.openCursor(txnInfo.dbTxn);
	try {
	    boolean found =  (oid < 0)
		? cursor.findFirst()
		: cursor.findNext(DataEncoding.encodeLong(oid + 1));
	    if (found) {
		return DataEncoding.decodeLong(cursor.getKey());
	    } else {
		return -1;
	    }
	} finally {
	    cursor.close();
	}
    }
}
