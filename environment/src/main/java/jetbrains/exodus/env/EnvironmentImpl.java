/**
 * Copyright 2010 - 2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.env;

import jetbrains.exodus.BackupStrategy;
import jetbrains.exodus.ExodusException;
import jetbrains.exodus.core.dataStructures.Pair;
import jetbrains.exodus.gc.GarbageCollector;
import jetbrains.exodus.log.Log;
import jetbrains.exodus.log.LogUtil;
import jetbrains.exodus.log.Loggable;
import jetbrains.exodus.tree.ITree;
import jetbrains.exodus.tree.TreeMetaInfo;
import jetbrains.exodus.tree.btree.BTree;
import jetbrains.exodus.tree.btree.BTreeBalancePolicy;
import jetbrains.exodus.util.DeferredIO;
import jetbrains.exodus.util.IOUtil;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class EnvironmentImpl implements Environment {

    public static final long META_TREE_ID = 1;

    private static final org.apache.commons.logging.Log logging = LogFactory.getLog(EnvironmentImpl.class);

    private static final String ENVIRONMENT_PROPERTIES_FILE = "exodus.properties";

    @NotNull
    private final Log log;
    @NotNull
    private final EnvironmentConfig ec;
    private BTreeBalancePolicy balancePolicy;
    private MetaTree metaTree;
    private final AtomicLong structureId;
    private final TransactionSet txns;
    private final LinkedList<RunnableWithTxnRoot> txnSafeTasks;
    private final GarbageCollector gc;
    private final Object commitLock = new Object();
    private final Object metaLock = new Object();

    /**
     * Throwable caught during commit after which rollback of highAddress failed.
     * Generally, it should ne null, otherwise environment is inoperative:
     * no transaction can be started or committed in that state. Once environment became inoperative,
     * it will remain inoperative forever.
     */
    private volatile Throwable throwableOnCommit;

    private Throwable throwableOnClose;

    @SuppressWarnings({"ThisEscapedInObjectConstruction"})
    EnvironmentImpl(@NotNull final Log log, @NotNull final EnvironmentConfig ec) {
        this.log = log;
        this.ec = ec;
        applyEnvironmentSettings(log.getLocation(), ec);
        final Pair<MetaTree, Long> meta = MetaTree.create(this);
        metaTree = meta.getFirst();
        structureId = new AtomicLong(meta.getSecond());
        txns = new TransactionSet();
        txnSafeTasks = new LinkedList<RunnableWithTxnRoot>();
        gc = new GarbageCollector(this);

        throwableOnCommit = null;
        throwableOnClose = null;

        if (transactionTimeout() > 0) {
            new StuckTransactionMonitor(this);
        }

        if (logging.isInfoEnabled()) {
            logging.info("Exodus environment created: " + log.getLocation());
        }
    }

    @Override
    @NotNull
    public String getLocation() {
        return log.getLocation();
    }

    @Override
    public EnvironmentConfig getEnvironmentConfig() {
        return ec;
    }

    public GarbageCollector getGC() {
        return gc;
    }

    @Override
    @NotNull
    public StoreImpl openStore(@NotNull final String name,
                               @NotNull final StoreConfiguration config,
                               @NotNull final Transaction transaction) {
        final TransactionImpl txn = (TransactionImpl) transaction;
        return openStoreImpl(name, config, txn, getCurrentMetaInfo(name, txn));
    }

    @Override
    @Nullable
    public StoreImpl openStore(@NotNull final String name,
                               @NotNull final StoreConfiguration config,
                               @NotNull final Transaction transaction,
                               final boolean creationRequired) {
        final TransactionImpl txn = (TransactionImpl) transaction;
        final TreeMetaInfo metaInfo = getCurrentMetaInfo(name, txn);
        if (metaInfo == null && !creationRequired) {
            return null;
        }
        return openStoreImpl(name, config, txn, metaInfo);
    }

    @Override
    @NotNull
    public TransactionImpl beginTransaction() {
        return beginTransaction(false, null);
    }

    @Override
    @NotNull
    public TransactionImpl beginTransaction(final Runnable beginHook) {
        return beginTransaction(false, beginHook);
    }

    @NotNull
    @Override
    public Transaction beginReadonlyTransaction() {
        return beginReadonlyTransaction(null);
    }

    @NotNull
    @Override
    public TransactionImpl beginReadonlyTransaction(final Runnable beginHook) {
        checkIsOperative();
        return new ReadonlyTransaction(this, getCreatingThread(), beginHook);
    }

    @NotNull
    public TransactionImpl beginTransactionWithClonedMetaTree() {
        return beginTransaction(true, null);
    }

    @Override
    public void executeInTransaction(@NotNull final TransactionalExecutable executable) {
        final Transaction txn = beginTransaction();
        try {
            while (true) {
                executable.execute(txn);
                if (txn.flush()) {
                    break;
                }
                txn.revert();
            }
        } finally {
            txn.abort();
        }
    }

    @Override
    public void executeInReadonlyTransaction(@NotNull TransactionalExecutable executable) {
        final Transaction txn = beginReadonlyTransaction();
        try {
            executable.execute(txn);
        } finally {
            txn.abort();
        }
    }

    @Override
    public <T> T computeInTransaction(@NotNull TransactionalComputable<T> computable) {
        final Transaction txn = beginTransaction();
        try {
            while (true) {
                final T result = computable.compute(txn);
                if (txn.flush()) {
                    return result;
                }
                txn.revert();
            }
        } finally {
            txn.abort();
        }
    }

    @Override
    public <T> T computeInReadonlyTransaction(@NotNull TransactionalComputable<T> computable) {
        final Transaction txn = beginReadonlyTransaction();
        try {
            return computable.compute(txn);
        } finally {
            txn.abort();
        }
    }

    @Override
    public void executeTransactionSafeTask(@NotNull final Runnable task) {
        final long newestTxnRoot = getNewestTxnRootAddress();
        if (newestTxnRoot == Long.MIN_VALUE) {
            task.run();
        } else {
            synchronized (txnSafeTasks) {
                txnSafeTasks.addLast(new RunnableWithTxnRoot(task, newestTxnRoot));
            }
        }
    }

    @Override
    public void clear() {
        suspendGC();
        try {
            synchronized (commitLock) {
                synchronized (metaLock) {
                    checkInactive(false);
                    log.clear();
                    runAllTransactionSafeTasks();
                    Pair<MetaTree, Long> meta = MetaTree.create(this);
                    metaTree = meta.getFirst();
                    structureId.set(meta.getSecond());
                }
            }
        } finally {
            resumeGC();
        }
    }

    @SuppressWarnings({"AccessToStaticFieldLockedOnInstance"})
    @Override
    public void close() {
        // in order to avoid deadlock, do not finish gc inside lock
        // it is safe to invoke gc.finish() several times
        gc.finish();
        final double hitRate;
        synchronized (commitLock) {
            if (!isOpen()) {
                throw new IllegalStateException("Already closed, see cause for previous close stack trace", throwableOnClose);
            }
            checkInactive(ec.getEnvCloseForcedly());
            gc.saveUtilizationProfile();
            hitRate = log.getCacheHitRate() * 100;
            log.close();
            throwableOnClose = new Throwable();
            throwableOnCommit = EnvironmentClosedException.INSTANCE;
        }
        runAllTransactionSafeTasks();
        if (logging.isInfoEnabled()) {
            logging.info("Exodus log cache hit rate: " + hitRate + '%');
        }
    }

    @Override
    public boolean isOpen() {
        return throwableOnClose == null;
    }

    @Override
    public BackupStrategy getBackupStrategy() {
        return new BackupStrategy() {
            @Override
            public void beforeBackup() {
                suspendGC();
            }

            @Override
            public Iterable<Pair<File, String>> listFiles() {
                return new Iterable<Pair<File, String>>() {
                    final File[] files = IOUtil.listFiles(new File(log.getLocation()));
                    int i = 0;
                    File current;

                    @Override
                    public Iterator<Pair<File, String>> iterator() {
                        return new Iterator<Pair<File, String>>() {

                            @Override
                            public boolean hasNext() {
                                if (current != null) {
                                    return true;
                                }
                                while (i < files.length) {
                                    final File next = files[i++];
                                    if (next.isFile() && next.length() != 0 && next.getName().endsWith(LogUtil.LOG_FILE_EXTENSION)) {
                                        current = next;
                                        return true;
                                    }
                                }
                                return false;
                            }

                            @Override
                            public Pair<File, String> next() {
                                if (!hasNext()) {
                                    throw new NoSuchElementException();
                                }
                                final Pair<File, String> result = new Pair<File, String>(current, "");
                                current = null;
                                return result;
                            }

                            @Override
                            public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }
                };
            }

            @Override
            public void afterBackup() {
                resumeGC();
            }

            @Override
            public void onError(Throwable t) {
            }
        };
    }

    @Override
    public void truncateStore(@NotNull final String storeName, @NotNull final Transaction transaction) {
        truncateStoreImpl(storeName, (TransactionImpl) transaction);
    }

    @Override
    public void removeStore(@NotNull final String storeName, @NotNull final Transaction transaction) {
        final TransactionImpl txn = (TransactionImpl) transaction;
        final StoreImpl store = openStore(storeName, StoreConfiguration.USE_EXISTING, txn, false);
        if (store == null) {
            throw new ExodusException("Attempt to remove unknown store '" + storeName + '\'');
        }
        final ITree tree = store.openImmutableTree(txn.getMetaTree());
        txn.getMutableTrees().remove(storeName);
        txn.storeRemoved(storeName, tree);
    }

    @Override
    public long getDiskUsage() {
        return IOUtil.getDirectorySize(new File(getLocation()), LogUtil.LOG_FILE_EXTENSION, false);
    }

    @Override
    @NotNull
    public List<String> getAllStoreNames(@NotNull Transaction transaction) {
        return ((TransactionImpl) transaction).getAllStoreNames();
    }

    @NotNull
    public Log getLog() {
        return log;
    }

    @Override
    public void gc() {
        gc.wake();
    }

    @Override
    public void suspendGC() {
        gc.suspend();
    }

    @Override
    public void resumeGC() {
        gc.resume();
    }

    public boolean storeExists(@NotNull final String storeName) {
        return getMetaTree(null).getMetaInfo(storeName, this) != null;
    }

    public BTreeBalancePolicy getBTreeBalancePolicy() {
        // we don't care of possible race condition here
        if (balancePolicy == null) {
            balancePolicy = new BTreeBalancePolicy(ec.getTreeMaxPageSize());
        }
        return balancePolicy;
    }

    protected StoreImpl createStore(@NotNull final String name, @NotNull final TreeMetaInfo metaInfo) {
        return new StoreImpl(this, name, metaInfo);
    }

    protected void finishTransaction(@NotNull final TransactionImpl txn) {
        txns.remove(txn);
        runTransactionSafeTasks();
    }

    @NotNull
    protected TransactionImpl beginTransaction(boolean cloneMeta, Runnable beginHook) {
        checkIsOperative();
        return new TransactionImpl(this, getCreatingThread(), beginHook, cloneMeta);
    }

    protected Thread getCreatingThread() {
        return transactionTimeout() > 0 ? Thread.currentThread() : null;
    }

    /**
     * @return timeout for a transaction in milliseconds, or 0 if no timeout is configured
     */
    int transactionTimeout() {
        return ec.getEnvMonitorTxnsTimeout();
    }

    /**
     * Tries to load meta tree located at specified rootAddress.
     *
     * @param rootAddress tree root address.
     * @return tree instance or null if the address is not valid.
     */
    @Nullable
    BTree loadMetaTree(final long rootAddress) {
        if (rootAddress < 0 || rootAddress >= log.getHighAddress()) return null;
        return new BTree(log, rootAddress, getBTreeBalancePolicy(), false, META_TREE_ID);
    }

    @SuppressWarnings("OverlyNestedMethod")
    boolean commitTransaction(@NotNull final TransactionImpl txn, final boolean forceCommit) {
        if (flushTransaction(txn, forceCommit)) {
            // don't finish if flushTransaction throws exception
            finishTransaction(txn);
            return true;
        } else {
            return false;
        }
    }

    boolean flushTransaction(@NotNull final TransactionImpl txn, final boolean forceCommit) {
        if (!forceCommit && txn.isIdempotent()) {
            return true;
        }
        final Iterable<Loggable>[] expiredLoggables;
        synchronized (commitLock) {
            checkIsOperative();
            if (!txn.checkVersion(metaTree.root)) {
                // meta lock not needed 'cause write can only occur in another commit lock
                return false;
            }
            final long highAddress = log.getHighAddress();
            try {
                final MetaTree[] tree = new MetaTree[1];
                expiredLoggables = txn.doCommit(tree);
                synchronized (metaLock) {
                    txn.setMetaTree(metaTree = tree[0]);
                    txn.executeCommitHook();
                }
            } catch (Throwable t) { // pokémon exception handling to decrease try/catch block overhead
                logging.error("Failed to flush transaction", t);
                try {
                    log.setHighAddress(highAddress);
                } catch (Throwable th) {
                    throwableOnCommit = t; // inoperative on failing to update high address too
                    throw ExodusException.toExodusException(th, "Failed to rollback high address");
                }
                throw ExodusException.toExodusException(t, "Failed to flush transaction");
            }
        }
        gc.fetchExpiredLoggables(new ExpiredLoggableIterable(expiredLoggables));
        return true;
    }

    MetaTree getMetaTree(@Nullable final Runnable beginHook) {
        synchronized (metaLock) {
            if (beginHook != null) {
                beginHook.run();
            }
            return metaTree;
        }
    }

    MetaTree getMetaTreeUnsafe() {
        return metaTree;
    }

    TreeMetaInfo getCurrentMetaInfo(final String name, @NotNull final TransactionImpl txn) {
        return txn.getMetaTree().getMetaInfo(name, this);
    }

    /**
     * Opens or creates store just like openStore() with the same parameters does, but gets parameters
     * that are not annotated. This allows to pass, e.g., nullable transaction.
     *
     * @param name     store name
     * @param config   store configuration
     * @param txn      transaction, should not null if store doesn't exists
     * @param metaInfo target meta information
     * @return store object
     */
    @SuppressWarnings({"AssignmentToMethodParameter"})
    @NotNull
    StoreImpl openStoreImpl(final String name, StoreConfiguration config, @Nullable final TransactionImpl txn, @Nullable TreeMetaInfo metaInfo) {
        if (config.useExisting) { // this parameter requires to recalculate
            if (metaInfo == null) {
                throw new ExodusException("Can't restore meta information for store " + name);
            } else {
                // 'readonly' is ignored here
                config = TreeMetaInfo.toConfig(metaInfo);
            }
        }
        final StoreImpl result;
        if (metaInfo == null) {
            if (txn == null) {
                throw new ExodusException("Transaction required to create a new store");
            }
            final long structureId = allocateStructureId();
            metaInfo = TreeMetaInfo.load(this, config.duplicates, config.prefixing, structureId);
            result = createStore(name, metaInfo);
            txn.getMutableTree(result);
            txn.storeCreated(name, metaInfo);
        } else {
            final boolean hasDuplicates = metaInfo.hasDuplicates();
            if (hasDuplicates != config.duplicates) {
                throw new ExodusException("Attempt to open store '" + name + "' with duplicates = " +
                        config.duplicates + " while it was created with duplicates =" + hasDuplicates);
            }
            if (metaInfo.isKeyPrefixing() != config.prefixing) {
                if (!config.prefixing) {
                    throw new ExodusException("Attempt to open store '" + name +
                            "' with prefixing = false while it was created with prefixing = true");
                }
                // if we're trying to open existing store with prefixing which actually wasn't created as store
                // with prefixing due to lack of the PatriciaTree feature, then open store with existing config
                metaInfo = TreeMetaInfo.load(this, hasDuplicates, false, metaInfo.getStructureId());
            }
            result = createStore(name, metaInfo);
        }
        return result;
    }

    long getLastStructureId() {
        return structureId.get();
    }

    void registerTransaction(@NotNull final TransactionImpl txn) {
        // N.B! due to TransactionImpl.revert(), there can appear a txn which is already in the transaction set
        // any implementation of transaction set should process this well
        txns.add(txn);
    }

    boolean isRegistered(@NotNull final TransactionImpl txn) {
        return txns.contains(txn);
    }

    void runTransactionSafeTasks() {
        List<Runnable> tasksToRun = null;
        final long oldestTxnRoot = getOldestTxnRootAddress();
        synchronized (txnSafeTasks) {
            while (true) {
                if (!txnSafeTasks.isEmpty()) {
                    final RunnableWithTxnRoot r = txnSafeTasks.getFirst();
                    if (r.txnRoot < oldestTxnRoot) {
                        txnSafeTasks.removeFirst();
                        if (tasksToRun == null) {
                            tasksToRun = new ArrayList<Runnable>(4);
                        }
                        tasksToRun.add(r.runnable);
                        continue;
                    }
                }
                break;
            }
        }
        if (tasksToRun != null) {
            for (final Runnable task : tasksToRun) {
                task.run();
            }
        }
    }

    @Nullable
    TransactionImpl getOldestTransaction() {
        return txns.getOldestTransaction();
    }

    @Nullable
    TransactionImpl getNewestTransaction() {
        return txns.getNewestTransaction();
    }

    static boolean isUtilizationProfile(@NotNull final String storeName) {
        return GarbageCollector.isUtilizationProfile(storeName);
    }

    private long getOldestTxnRootAddress() {
        final TransactionImpl oldestTxn = getOldestTransaction();
        return oldestTxn == null ? Long.MAX_VALUE : oldestTxn.getRoot();
    }

    private long getNewestTxnRootAddress() {
        final TransactionImpl newestTxn = getNewestTransaction();
        return newestTxn == null ? Long.MIN_VALUE : newestTxn.getRoot();
    }

    private void runAllTransactionSafeTasks() {
        synchronized (txnSafeTasks) {
            for (final RunnableWithTxnRoot r : txnSafeTasks) {
                r.runnable.run();
            }
        }
        DeferredIO.getJobProcessor().waitForJobs(100);
    }

    private void checkInactive(boolean exceptionSafe) {
        final int txnCount = txns.size();
        if (txnCount > 0) {
            final String errorString = "Environment[" + getLocation() + "] is active: " + txnCount + " transaction(s) not finished";
            if (!exceptionSafe) {
                logging.error(errorString);
            } else if (logging.isInfoEnabled()) {
                logging.info(errorString);
            }
            if (logging.isDebugEnabled()) {
                for (TransactionImpl transaction : txns) {
                    logging.debug("Alive transaction: ", transaction.getTrace());
                }
            }
        }
        if (!exceptionSafe) {
            if (txnCount > 0) {
                throw new ExodusException("Finish all transactions before closing database environment");
            }
        }
    }

    private void checkIsOperative() {
        final Throwable t = throwableOnCommit;
        if (t != null) {
            throw ExodusException.toExodusException(t, "Environment is inoperative");
        }
    }

    private long allocateStructureId() {
        /**
         * <TRICK>
         * Allocates structure id so that 256 doesn't factor it. This ensures that corresponding byte iterable
         * will never end with zero byte, and any such id can be used as a key in meta tree without collision
         * with a string key (store name). String keys (according to StringBinding) do always end with zero byte.
         * </TRICK>
         */
        while (true) {
            final long result = structureId.incrementAndGet();
            if ((result & 0xff) != 0) {
                return result;
            }
        }
    }

    private void truncateStoreImpl(@NotNull final String storeName, @NotNull final TransactionImpl txn) {
        final StoreImpl store = openStore(storeName, StoreConfiguration.USE_EXISTING, txn, false);
        if (store == null) {
            throw new ExodusException("Attempt to truncate unknown store '" + storeName + '\'');
        }
        txn.storeRemoved(storeName, store.openImmutableTree(txn.getMetaTree()));
        final TreeMetaInfo metaInfoCloned = store.getMetaInfo().clone(allocateStructureId());
        txn.getMutableTree(new StoreImpl(this, storeName, metaInfoCloned));
        txn.storeCreated(storeName, metaInfoCloned);
    }

    private static void applyEnvironmentSettings(@NotNull final String location,
                                                 @NotNull final EnvironmentConfig ec) {
        final File propsFile = new File(location, ENVIRONMENT_PROPERTIES_FILE);
        if (propsFile.exists() && propsFile.isFile()) {
            try {
                final InputStream propsStream = new FileInputStream(propsFile);
                try {
                    final Properties envProps = new Properties();
                    envProps.load(propsStream);
                    for (final Map.Entry<Object, Object> entry : envProps.entrySet()) {
                        ec.setSetting(entry.getKey().toString(), entry.getValue());
                    }

                } finally {
                    propsStream.close();
                }
            } catch (IOException e) {
                throw ExodusException.toExodusException(e);
            }
        }
    }

    @SuppressWarnings({"AssignmentToCollectionOrArrayFieldFromParameter"})
    private static class ExpiredLoggableIterable implements Iterable<Loggable> {

        private final Iterable<Loggable>[] expiredLoggables;

        private ExpiredLoggableIterable(Iterable<Loggable>[] expiredLoggables) {
            this.expiredLoggables = expiredLoggables;
        }

        @Override
        public Iterator<Loggable> iterator() {

            return new Iterator<Loggable>() {
                private Iterator<Loggable> current = expiredLoggables[0].iterator();
                private int index = 0;

                @Override
                public boolean hasNext() {
                    //noinspection LoopConditionNotUpdatedInsideLoop
                    while (!current.hasNext()) {
                        if (++index == expiredLoggables.length) {
                            return false;
                        }
                        current = expiredLoggables[index].iterator();
                    }
                    return true;
                }

                @Override
                public Loggable next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException("No more loggables available");
                    }
                    return current.next();
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    private static class RunnableWithTxnRoot {

        private final Runnable runnable;
        private final long txnRoot;

        private RunnableWithTxnRoot(Runnable runnable, long txnRoot) {
            this.runnable = runnable;
            this.txnRoot = txnRoot;
        }
    }
}