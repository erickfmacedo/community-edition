/*
 * Copyright (C) 2005 Alfresco, Inc.
 *
 * Licensed under the Mozilla Public License version 1.1 
 * with a permitted attribution clause. You may obtain a
 * copy of the License at
 *
 *   http://www.alfresco.org/legal/license.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.alfresco.repo.search.impl.lucene;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.repo.search.IndexerException;
import org.alfresco.repo.search.MLAnalysisMode;
import org.alfresco.repo.search.QueryRegisterComponent;
import org.alfresco.repo.search.SearcherException;
import org.alfresco.repo.search.impl.lucene.fts.FullTextSearchIndexer;
import org.alfresco.repo.search.impl.lucene.index.IndexInfo;
import org.alfresco.repo.search.transaction.SimpleTransaction;
import org.alfresco.repo.search.transaction.SimpleTransactionManager;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.transaction.TransactionUtil;
import org.alfresco.repo.transaction.TransactionUtil.TransactionWork;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.util.GUID;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.store.Lock;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * This class is resource manager LuceneIndexers and LuceneSearchers. It supports two phase commit inside XA
 * transactions and outside transactions it provides thread local transaction support. TODO: Provide pluggable support
 * for a transaction manager TODO: Integrate with Spring transactions
 * 
 * @author andyh
 */

public class LuceneIndexerAndSearcherFactory2 implements LuceneIndexerAndSearcher, XAResource
{
    private static Log logger = LogFactory.getLog(LuceneIndexerAndSearcherFactory2.class);

    private DictionaryService dictionaryService;

    private NamespaceService nameSpaceService;

    private int queryMaxClauses;

    private int indexerBatchSize;

    /**
     * A map of active global transactions . It contains all the indexers a transaction has used, with at most one
     * indexer for each store within a transaction
     */

    private static Map<Xid, Map<StoreRef, LuceneIndexer2>> activeIndexersInGlobalTx = new HashMap<Xid, Map<StoreRef, LuceneIndexer2>>();

    /**
     * Suspended global transactions.
     */
    private static Map<Xid, Map<StoreRef, LuceneIndexer2>> suspendedIndexersInGlobalTx = new HashMap<Xid, Map<StoreRef, LuceneIndexer2>>();

    /**
     * Thread local indexers - used outside a global transaction
     */

    private static ThreadLocal<Map<StoreRef, LuceneIndexer2>> threadLocalIndexers = new ThreadLocal<Map<StoreRef, LuceneIndexer2>>();

    /**
     * The dafault timeout for transactions TODO: Respect this
     */

    private int timeout = DEFAULT_TIMEOUT;

    /**
     * Default time out value set to 10 minutes.
     */
    private static final int DEFAULT_TIMEOUT = 600000;

    /**
     * The node service we use to get information about nodes
     */

    private NodeService nodeService;

    private FullTextSearchIndexer luceneFullTextSearchIndexer;

    private String indexRootLocation;

    private ContentService contentService;

    private QueryRegisterComponent queryRegister;

    /** the maximum transformation time to allow atomically, defaulting to 20ms */
    private long maxAtomicTransformationTime = 20;

    private int indexerMaxFieldLength;

    private long writeLockTimeout;

    private long commitLockTimeout;

    private String lockDirectory;

    private MLAnalysisMode defaultMLIndexAnalysisMode = MLAnalysisMode.LOCALE_AND_ALL;

    private MLAnalysisMode defaultMLSearchAnalysisMode = MLAnalysisMode.LOCALE_AND_ALL_CONTAINING_LOCALES_AND_ALL;

    /**
     * Private constructor for the singleton TODO: FIt in with IOC
     */

    public LuceneIndexerAndSearcherFactory2()
    {
        super();
    }

    /**
     * Setter for getting the node service via IOC Used in the Spring container
     * 
     * @param nodeService
     */

    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }

    public void setDictionaryService(DictionaryService dictionaryService)
    {
        this.dictionaryService = dictionaryService;
    }

    public void setNameSpaceService(NamespaceService nameSpaceService)
    {
        this.nameSpaceService = nameSpaceService;
    }

    public void setLuceneFullTextSearchIndexer(FullTextSearchIndexer luceneFullTextSearchIndexer)
    {
        this.luceneFullTextSearchIndexer = luceneFullTextSearchIndexer;
    }

    public void setIndexRootLocation(String indexRootLocation)
    {
        this.indexRootLocation = indexRootLocation;
    }

    public void setQueryRegister(QueryRegisterComponent queryRegister)
    {
        this.queryRegister = queryRegister;
    }

    /**
     * Set the maximum average transformation time allowed to a transformer in order to have the transformation
     * performed in the current transaction. The default is 20ms.
     * 
     * @param maxAtomicTransformationTime
     *            the maximum average time that a text transformation may take in order to be performed atomically.
     */
    public void setMaxAtomicTransformationTime(long maxAtomicTransformationTime)
    {
        this.maxAtomicTransformationTime = maxAtomicTransformationTime;
    }

    /**
     * Check if we are in a global transactoin according to the transaction manager
     * 
     * @return
     */

    private boolean inGlobalTransaction()
    {
        try
        {
            return SimpleTransactionManager.getInstance().getTransaction() != null;
        }
        catch (SystemException e)
        {
            return false;
        }
    }

    /**
     * Get the local transaction - may be null oif we are outside a transaction.
     * 
     * @return
     * @throws IndexerException
     */
    private SimpleTransaction getTransaction() throws IndexerException
    {
        try
        {
            return SimpleTransactionManager.getInstance().getTransaction();
        }
        catch (SystemException e)
        {
            throw new IndexerException("Failed to get transaction", e);
        }
    }

    /**
     * Get an indexer for the store to use in the current transaction for this thread of control.
     * 
     * @param storeRef -
     *            the id of the store
     */
    public LuceneIndexer2 getIndexer(StoreRef storeRef) throws IndexerException
    {
        // register to receive txn callbacks
        // TODO: make this conditional on whether the XA stuff is being used
        // directly on not
        AlfrescoTransactionSupport.bindLucene(this);

        if (inGlobalTransaction())
        {
            SimpleTransaction tx = getTransaction();
            // Only find indexers in the active list
            Map<StoreRef, LuceneIndexer2> indexers = activeIndexersInGlobalTx.get(tx);
            if (indexers == null)
            {
                if (suspendedIndexersInGlobalTx.containsKey(tx))
                {
                    throw new IndexerException("Trying to obtain an index for a suspended transaction.");
                }
                indexers = new HashMap<StoreRef, LuceneIndexer2>();
                activeIndexersInGlobalTx.put(tx, indexers);
                try
                {
                    tx.enlistResource(this);
                }
                // TODO: what to do in each case?
                catch (IllegalStateException e)
                {
                    throw new IndexerException("", e);
                }
                catch (RollbackException e)
                {
                    throw new IndexerException("", e);
                }
                catch (SystemException e)
                {
                    throw new IndexerException("", e);
                }
            }
            LuceneIndexer2 indexer = indexers.get(storeRef);
            if (indexer == null)
            {
                indexer = createIndexer(storeRef, getTransactionId(tx, storeRef));
                indexers.put(storeRef, indexer);
            }
            return indexer;
        }
        else
        // A thread local transaction
        {
            return getThreadLocalIndexer(storeRef);
        }

    }

    private LuceneIndexer2 getThreadLocalIndexer(StoreRef storeRef)
    {
        Map<StoreRef, LuceneIndexer2> indexers = threadLocalIndexers.get();
        if (indexers == null)
        {
            indexers = new HashMap<StoreRef, LuceneIndexer2>();
            threadLocalIndexers.set(indexers);
        }
        LuceneIndexer2 indexer = indexers.get(storeRef);
        if (indexer == null)
        {
            indexer = createIndexer(storeRef, GUID.generate());
            indexers.put(storeRef, indexer);
        }
        return indexer;
    }

    /**
     * Get the transaction identifier uised to store it in the transaction map.
     * 
     * @param tx
     * @return
     */
    private static String getTransactionId(Transaction tx, StoreRef storeRef)
    {
        if (tx instanceof SimpleTransaction)
        {
            SimpleTransaction simpleTx = (SimpleTransaction) tx;
            return simpleTx.getGUID();
        }
        else
        {
            Map<StoreRef, LuceneIndexer2> indexers = threadLocalIndexers.get();
            if (indexers != null)
            {
                LuceneIndexer2 indexer = indexers.get(storeRef);
                if (indexer != null)
                {
                    return indexer.getDeltaId();
                }
            }
            return null;
        }
    }

    /**
     * Encapsulate creating an indexer
     * 
     * @param storeRef
     * @param deltaId
     * @return
     */
    private LuceneIndexerImpl2 createIndexer(StoreRef storeRef, String deltaId)
    {
        LuceneIndexerImpl2 indexer = LuceneIndexerImpl2.getUpdateIndexer(storeRef, deltaId, this);
        indexer.setNodeService(nodeService);
        indexer.setDictionaryService(dictionaryService);
        // indexer.setLuceneIndexLock(luceneIndexLock);
        indexer.setLuceneFullTextSearchIndexer(luceneFullTextSearchIndexer);
        indexer.setContentService(contentService);
        indexer.setMaxAtomicTransformationTime(maxAtomicTransformationTime);
        return indexer;
    }

    /**
     * Encapsulate creating a searcher over the main index
     */
    public LuceneSearcher2 getSearcher(StoreRef storeRef, boolean searchDelta) throws SearcherException
    {
        String deltaId = null;
        LuceneIndexer2 indexer = null;
        if (searchDelta)
        {
            deltaId = getTransactionId(getTransaction(), storeRef);
            if (deltaId != null)
            {
                indexer = getIndexer(storeRef);
            }
        }
        LuceneSearcher2 searcher = getSearcher(storeRef, indexer);
        return searcher;
    }

    /**
     * Get a searcher over the index and the current delta
     * 
     * @param storeRef
     * @param deltaId
     * @return
     * @throws SearcherException
     */
    private LuceneSearcher2 getSearcher(StoreRef storeRef, LuceneIndexer2 indexer) throws SearcherException
    {
        LuceneSearcherImpl2 searcher = LuceneSearcherImpl2.getSearcher(storeRef, indexer, this);
        searcher.setNamespacePrefixResolver(nameSpaceService);
        // searcher.setLuceneIndexLock(luceneIndexLock);
        searcher.setNodeService(nodeService);
        searcher.setDictionaryService(dictionaryService);
        searcher.setQueryRegister(queryRegister);
        return searcher;
    }

    /*
     * XAResource implementation
     */

    public void commit(Xid xid, boolean onePhase) throws XAException
    {
        try
        {
            // TODO: Should be remembering overall state
            // TODO: Keep track of prepare responses
            Map<StoreRef, LuceneIndexer2> indexers = activeIndexersInGlobalTx.get(xid);
            if (indexers == null)
            {
                if (suspendedIndexersInGlobalTx.containsKey(xid))
                {
                    throw new XAException("Trying to commit indexes for a suspended transaction.");
                }
                else
                {
                    // nothing to do
                    return;
                }
            }

            if (onePhase)
            {
                if (indexers.size() == 0)
                {
                    return;
                }
                else if (indexers.size() == 1)
                {
                    for (LuceneIndexer2 indexer : indexers.values())
                    {
                        indexer.commit();
                    }
                    return;
                }
                else
                {
                    throw new XAException("Trying to do one phase commit on more than one index");
                }
            }
            else
            // two phase
            {
                for (LuceneIndexer2 indexer : indexers.values())
                {
                    indexer.commit();
                }
                return;
            }
        }
        finally
        {
            activeIndexersInGlobalTx.remove(xid);
        }
    }

    public void end(Xid xid, int flag) throws XAException
    {
        Map<StoreRef, LuceneIndexer2> indexers = activeIndexersInGlobalTx.get(xid);
        if (indexers == null)
        {
            if (suspendedIndexersInGlobalTx.containsKey(xid))
            {
                throw new XAException("Trying to commit indexes for a suspended transaction.");
            }
            else
            {
                // nothing to do
                return;
            }
        }
        if (flag == XAResource.TMSUSPEND)
        {
            activeIndexersInGlobalTx.remove(xid);
            suspendedIndexersInGlobalTx.put(xid, indexers);
        }
        else if (flag == TMFAIL)
        {
            activeIndexersInGlobalTx.remove(xid);
            suspendedIndexersInGlobalTx.remove(xid);
        }
        else if (flag == TMSUCCESS)
        {
            activeIndexersInGlobalTx.remove(xid);
        }
    }

    public void forget(Xid xid) throws XAException
    {
        activeIndexersInGlobalTx.remove(xid);
        suspendedIndexersInGlobalTx.remove(xid);
    }

    public int getTransactionTimeout() throws XAException
    {
        return timeout;
    }

    public boolean isSameRM(XAResource xar) throws XAException
    {
        return (xar instanceof LuceneIndexerAndSearcherFactory2);
    }

    public int prepare(Xid xid) throws XAException
    {
        // TODO: Track state OK, ReadOnly, Exception (=> rolled back?)
        Map<StoreRef, LuceneIndexer2> indexers = activeIndexersInGlobalTx.get(xid);
        if (indexers == null)
        {
            if (suspendedIndexersInGlobalTx.containsKey(xid))
            {
                throw new XAException("Trying to commit indexes for a suspended transaction.");
            }
            else
            {
                // nothing to do
                return XAResource.XA_OK;
            }
        }
        boolean isPrepared = true;
        boolean isModified = false;
        for (LuceneIndexer2 indexer : indexers.values())
        {
            try
            {
                isModified |= indexer.isModified();
                indexer.prepare();
            }
            catch (IndexerException e)
            {
                isPrepared = false;
            }
        }
        if (isPrepared)
        {
            if (isModified)
            {
                return XAResource.XA_OK;
            }
            else
            {
                return XAResource.XA_RDONLY;
            }
        }
        else
        {
            throw new XAException("Failed to prepare: requires rollback");
        }
    }

    public Xid[] recover(int arg0) throws XAException
    {
        // We can not rely on being able to recover at the moment
        // Avoiding for performance benefits at the moment
        // Assume roll back and no recovery - in the worst case we get an unused
        // delta
        // This should be there to avoid recovery of partial commits.
        // It is difficult to see how we can mandate the same conditions.
        return new Xid[0];
    }

    public void rollback(Xid xid) throws XAException
    {
        // TODO: What to do if all do not roll back?
        try
        {
            Map<StoreRef, LuceneIndexer2> indexers = activeIndexersInGlobalTx.get(xid);
            if (indexers == null)
            {
                if (suspendedIndexersInGlobalTx.containsKey(xid))
                {
                    throw new XAException("Trying to commit indexes for a suspended transaction.");
                }
                else
                {
                    // nothing to do
                    return;
                }
            }
            for (LuceneIndexer2 indexer : indexers.values())
            {
                indexer.rollback();
            }
        }
        finally
        {
            activeIndexersInGlobalTx.remove(xid);
        }
    }

    public boolean setTransactionTimeout(int timeout) throws XAException
    {
        this.timeout = timeout;
        return true;
    }

    public void start(Xid xid, int flag) throws XAException
    {
        Map<StoreRef, LuceneIndexer2> active = activeIndexersInGlobalTx.get(xid);
        Map<StoreRef, LuceneIndexer2> suspended = suspendedIndexersInGlobalTx.get(xid);
        if (flag == XAResource.TMJOIN)
        {
            // must be active
            if ((active != null) && (suspended == null))
            {
                return;
            }
            else
            {
                throw new XAException("Trying to rejoin transaction in an invalid state");
            }

        }
        else if (flag == XAResource.TMRESUME)
        {
            // must be suspended
            if ((active == null) && (suspended != null))
            {
                suspendedIndexersInGlobalTx.remove(xid);
                activeIndexersInGlobalTx.put(xid, suspended);
                return;
            }
            else
            {
                throw new XAException("Trying to rejoin transaction in an invalid state");
            }

        }
        else if (flag == XAResource.TMNOFLAGS)
        {
            if ((active == null) && (suspended == null))
            {
                return;
            }
            else
            {
                throw new XAException("Trying to start an existing or suspended transaction");
            }
        }
        else
        {
            throw new XAException("Unkown flags for start " + flag);
        }

    }

    /*
     * Thread local support for transactions
     */

    /**
     * Commit the transaction
     */

    public void commit() throws IndexerException
    {
        try
        {
            Map<StoreRef, LuceneIndexer2> indexers = threadLocalIndexers.get();
            if (indexers != null)
            {
                for (LuceneIndexer2 indexer : indexers.values())
                {
                    try
                    {
                        indexer.commit();
                    }
                    catch (IndexerException e)
                    {
                        rollback();
                        throw e;
                    }
                }
            }
        }
        finally
        {
            if (threadLocalIndexers.get() != null)
            {
                threadLocalIndexers.get().clear();
                threadLocalIndexers.set(null);
            }
        }
    }

    /**
     * Prepare the transaction TODO: Store prepare results
     * 
     * @return
     */
    public int prepare() throws IndexerException
    {
        boolean isPrepared = true;
        boolean isModified = false;
        Map<StoreRef, LuceneIndexer2> indexers = threadLocalIndexers.get();
        if (indexers != null)
        {
            for (LuceneIndexer2 indexer : indexers.values())
            {
                try
                {
                    isModified |= indexer.isModified();
                    indexer.prepare();
                }
                catch (IndexerException e)
                {
                    isPrepared = false;
                    throw new IndexerException("Failed to prepare: requires rollback", e);
                }
            }
        }
        if (isPrepared)
        {
            if (isModified)
            {
                return XAResource.XA_OK;
            }
            else
            {
                return XAResource.XA_RDONLY;
            }
        }
        else
        {
            throw new IndexerException("Failed to prepare: requires rollback");
        }
    }

    /**
     * Roll back the transaction
     */
    public void rollback()
    {
        Map<StoreRef, LuceneIndexer2> indexers = threadLocalIndexers.get();

        if (indexers != null)
        {
            for (LuceneIndexer2 indexer : indexers.values())
            {
                try
                {
                    indexer.rollback();
                }
                catch (IndexerException e)
                {

                }
            }
        }

        if (threadLocalIndexers.get() != null)
        {
            threadLocalIndexers.get().clear();
            threadLocalIndexers.set(null);
        }

    }

    public void flush()
    {
        // TODO: Needs fixing if we expose the indexer in JTA
        Map<StoreRef, LuceneIndexer2> indexers = threadLocalIndexers.get();

        if (indexers != null)
        {
            for (LuceneIndexer2 indexer : indexers.values())
            {
                indexer.flushPending();
            }
        }
    }

    public void setContentService(ContentService contentService)
    {
        this.contentService = contentService;
    }

    public String getIndexRootLocation()
    {
        return indexRootLocation;
    }

    public int getIndexerBatchSize()
    {
        return indexerBatchSize;
    }

    public void setIndexerBatchSize(int indexerBatchSize)
    {
        this.indexerBatchSize = indexerBatchSize;
    }

    public String getLockDirectory()
    {
        return lockDirectory;
    }

    public void setLockDirectory(String lockDirectory)
    {
        this.lockDirectory = lockDirectory;
        // Set the lucene lock file via System property
        // org.apache.lucene.lockDir
        System.setProperty("org.apache.lucene.lockDir", lockDirectory);
        // Make sure the lock directory exists
        File lockDir = new File(lockDirectory);
        if (!lockDir.exists())
        {
            lockDir.mkdirs();
        }
        // clean out any existing locks when we start up

        File[] children = lockDir.listFiles();
        if (children != null)
        {
            for (int i = 0; i < children.length; i++)
            {
                File child = children[i];
                if (child.isFile())
                {
                    if (child.exists() && !child.delete() && child.exists())
                    {
                        throw new IllegalStateException("Failed to delete " + child);
                    }
                }
            }
        }
    }

    public int getQueryMaxClauses()
    {
        return queryMaxClauses;
    }

    public void setQueryMaxClauses(int queryMaxClauses)
    {
        this.queryMaxClauses = queryMaxClauses;
        BooleanQuery.setMaxClauseCount(this.queryMaxClauses);
    }

    public void setWriteLockTimeout(long timeout)
    {
        this.writeLockTimeout = timeout;
    }

    public void setCommitLockTimeout(long timeout)
    {
        this.commitLockTimeout = timeout;
    }

    public long getCommitLockTimeout()
    {
        return commitLockTimeout;
    }

    public long getWriteLockTimeout()
    {
        return writeLockTimeout;
    }

    public void setLockPollInterval(long time)
    {
        Lock.LOCK_POLL_INTERVAL = time;
    }

    public int getIndexerMaxFieldLength()
    {
        return indexerMaxFieldLength;
    }

    public void setIndexerMaxFieldLength(int indexerMaxFieldLength)
    {
        this.indexerMaxFieldLength = indexerMaxFieldLength;
        System.setProperty("org.apache.lucene.maxFieldLength", "" + indexerMaxFieldLength);
    }

    /**
     * This component is able to <i>safely</i> perform backups of the Lucene indexes while the server is running.
     * <p>
     * It can be run directly by calling the {@link #backup() } method, but the convenience {@link LuceneIndexBackupJob}
     * can be used to call it as well.
     * 
     * @author Derek Hulley
     */
    public static class LuceneIndexBackupComponent
    {

        private TransactionService transactionService;

        private LuceneIndexerAndSearcher factory;

        private NodeService nodeService;

        private String targetLocation;

        public LuceneIndexBackupComponent()
        {
        }

        /**
         * Provides transactions in which to perform the work
         * 
         * @param transactionService
         */
        public void setTransactionService(TransactionService transactionService)
        {
            this.transactionService = transactionService;
        }

        /**
         * Set the Lucene index factory that will be used to control the index locks
         * 
         * @param factory
         *            the index factory
         */
        public void setFactory(LuceneIndexerAndSearcher factory)
        {
            this.factory = factory;
        }

        /**
         * Used to retrieve the stores
         * 
         * @param nodeService
         *            the node service
         */
        public void setNodeService(NodeService nodeService)
        {
            this.nodeService = nodeService;
        }

        /**
         * Set the directory to which the backup will be copied
         * 
         * @param targetLocation
         *            the backup directory
         */
        public void setTargetLocation(String targetLocation)
        {
            this.targetLocation = targetLocation;
        }

        /**
         * Backup the Lucene indexes
         */
        public void backup()
        {
            TransactionWork<Object> backupWork = new TransactionWork<Object>()
            {
                public Object doWork() throws Exception
                {
                    backupImpl();
                    return null;
                }
            };
            TransactionUtil.executeInUserTransaction(transactionService, backupWork);
        }

        private void backupImpl()
        {
            // create the location to copy to
            final File targetDir = new File(targetLocation);
            if (targetDir.exists() && !targetDir.isDirectory())
            {
                throw new AlfrescoRuntimeException("Target location is a file and not a directory: " + targetDir);
            }
            final File targetParentDir = targetDir.getParentFile();
            if (targetParentDir == null)
            {
                throw new AlfrescoRuntimeException("Target location may not be a root directory: " + targetDir);
            }
            final File tempDir = new File(targetParentDir, "indexbackup_temp");

            factory.doWithAllWriteLocks(new WithAllWriteLocksWork<Object>()
            {
                public Object doWork()
                {
                    try
                    {
                        File indexRootDir = new File(factory.getIndexRootLocation());
                        // perform the copy
                        backupDirectory(indexRootDir, tempDir, targetDir);
                        return null;
                    }
                    catch (Throwable e)
                    {
                        throw new AlfrescoRuntimeException(
                                "Failed to copy Lucene index root: \n"
                                        + "   Index root: " + factory.getIndexRootLocation() + "\n" + "   Target: "
                                        + targetDir, e);
                    }
                }
            });

            if (logger.isDebugEnabled())
            {
                logger.debug("Backed up Lucene indexes: \n" + "   Target directory: " + targetDir);
            }
        }

        /**
         * Makes a backup of the source directory via a temporary folder
         */
        private void backupDirectory(File sourceDir, File tempDir, File targetDir) throws Exception
        {
            if (!sourceDir.exists())
            {
                // there is nothing to copy
                return;
            }
            // delete the files from the temp directory
            if (tempDir.exists())
            {
                FileUtils.deleteDirectory(tempDir);
                if (tempDir.exists())
                {
                    throw new AlfrescoRuntimeException("Temp directory exists and cannot be deleted: " + tempDir);
                }
            }
            // copy to the temp directory
            FileUtils.copyDirectory(sourceDir, tempDir, true);
            // check that the temp directory was created
            if (!tempDir.exists())
            {
                throw new AlfrescoRuntimeException("Copy to temp location failed");
            }
            // delete the target directory
            FileUtils.deleteDirectory(targetDir);
            if (targetDir.exists())
            {
                throw new AlfrescoRuntimeException("Failed to delete older files from target location");
            }
            // rename the temp to be the target
            tempDir.renameTo(targetDir);
            // make sure the rename worked
            if (!targetDir.exists())
            {
                throw new AlfrescoRuntimeException("Failed to rename temporary directory to target backup directory");
            }
        }
    }

    /**
     * Job that lock uses the {@link LuceneIndexBackupComponent} to perform safe backups of the Lucene indexes.
     * 
     * @author Derek Hulley
     */
    public static class LuceneIndexBackupJob implements Job
    {
        /** KEY_LUCENE_INDEX_BACKUP_COMPONENT = 'luceneIndexBackupComponent' */
        public static final String KEY_LUCENE_INDEX_BACKUP_COMPONENT = "luceneIndexBackupComponent";

        /**
         * Locks the Lucene indexes and copies them to a backup location
         */
        public void execute(JobExecutionContext context) throws JobExecutionException
        {
            JobDataMap jobData = context.getJobDetail().getJobDataMap();
            LuceneIndexBackupComponent backupComponent = (LuceneIndexBackupComponent) jobData
                    .get(KEY_LUCENE_INDEX_BACKUP_COMPONENT);
            if (backupComponent == null)
            {
                throw new JobExecutionException("Missing job data: " + KEY_LUCENE_INDEX_BACKUP_COMPONENT);
            }
            // perform the backup
            backupComponent.backup();
        }
    }

    public <R> R doWithAllWriteLocks(WithAllWriteLocksWork<R> lockWork)
    {
        // get all the available stores
        List<StoreRef> storeRefs = nodeService.getStores();

        IndexInfo.LockWork<R> currentLockWork = null;

        for (int i = storeRefs.size() - 1; i >= 0; i--)
        {
            if (currentLockWork == null)
            {
                currentLockWork = new CoreLockWork<R>(getIndexer(storeRefs.get(i)), lockWork);
            }
            else
            {
                currentLockWork = new NestingLockWork<R>(getIndexer(storeRefs.get(i)), currentLockWork);
            }
        }

        if (currentLockWork != null)
        {
            try
            {
                return currentLockWork.doWork();
            }
            catch (Throwable exception)
            {

                // Re-throw the exception
                if (exception instanceof RuntimeException)
                {
                    throw (RuntimeException) exception;
                }
                else
                {
                    throw new RuntimeException("Error during run with lock.", exception);
                }
            }

        }
        else
        {
            return null;
        }
    }

    private static class NestingLockWork<R> implements IndexInfo.LockWork<R>
    {
        IndexInfo.LockWork<R> lockWork;

        LuceneIndexer2 indexer;

        NestingLockWork(LuceneIndexer2 indexer, IndexInfo.LockWork<R> lockWork)
        {
            this.indexer = indexer;
            this.lockWork = lockWork;
        }

        public R doWork() throws Exception
        {
            return indexer.doWithWriteLock(lockWork);
        }
    }

    private static class CoreLockWork<R> implements IndexInfo.LockWork<R>
    {
        WithAllWriteLocksWork<R> lockWork;

        LuceneIndexer2 indexer;

        CoreLockWork(LuceneIndexer2 indexer, WithAllWriteLocksWork<R> lockWork)
        {
            this.indexer = indexer;
            this.lockWork = lockWork;
        }

        public R doWork() throws Exception
        {
            return indexer.doWithWriteLock(new IndexInfo.LockWork<R>()
            {
                public R doWork()
                {
                    try
                    {
                        return lockWork.doWork();
                    }
                    catch (Throwable exception)
                    {

                        // Re-throw the exception
                        if (exception instanceof RuntimeException)
                        {
                            throw (RuntimeException) exception;
                        }
                        else
                        {
                            throw new RuntimeException("Error during run with lock.", exception);
                        }
                    }
                }
            });
        }
    }

    public MLAnalysisMode getDefaultMLIndexAnalysisMode()
    {
        return defaultMLIndexAnalysisMode;
    }

    public void  setDefaultMLIndexAnalysisMode(String mode)
    {
        defaultMLIndexAnalysisMode = MLAnalysisMode.getMLAnalysisMode(mode);
    }
    
    public MLAnalysisMode getDefaultMLSearchAnalysisMode()
    {
       return defaultMLSearchAnalysisMode;
    }
    
    public void  setDefaultMLSearchAnalysisMode(String mode)
    {
        defaultMLSearchAnalysisMode = MLAnalysisMode.getMLAnalysisMode(mode);
    }
    
    
    
}
