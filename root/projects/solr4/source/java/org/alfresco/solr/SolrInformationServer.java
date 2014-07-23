/*
 * Copyright (C) 2014 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */

package org.alfresco.solr;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;
import java.util.Set;

import org.alfresco.httpclient.AuthenticationException;
import org.alfresco.opencmis.dictionary.CMISStrictDictionaryService;
import org.alfresco.repo.dictionary.DictionaryComponent;
import org.alfresco.repo.dictionary.M2Model;
import org.alfresco.repo.dictionary.NamespaceDAO;
import org.alfresco.repo.search.adaptor.lucene.QueryConstants;
import org.alfresco.service.cmr.dictionary.TypeDefinition;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.adapters.IOpenBitSet;
import org.alfresco.solr.adapters.ISimpleOrderedMap;
import org.alfresco.solr.adapters.SolrOpenBitSetAdapter;
import org.alfresco.solr.adapters.SolrSimpleOrderedMap;
import org.alfresco.solr.client.AclChangeSet;
import org.alfresco.solr.client.AclReaders;
import org.alfresco.solr.client.AlfrescoModel;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.alfresco.solr.tracker.Tracker;
import org.alfresco.solr.tracker.TrackerStats;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrInfoMBean;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.util.RefCounted;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the Solr4 implementation of the information server (index).
 * @author Ahmed Owian
 */
public class SolrInformationServer implements InformationServer
{

    private AlfrescoCoreAdminHandler adminHandler;
    private SolrCore core;
    private TrackerState trackerState = new TrackerState();
    private TrackerStats trackerStats = new TrackerStats(this);
    private AlfrescoSolrDataModel dataModel;
    private String alfrescoVersion;
    private int authorityCacheSize;
    private int filterCacheSize;
    private int pathCacheSize;
    private boolean transformContent = true;
    private long lag;
    private long holeRetention;
    
    // Metadata pulling control
    private boolean skipDescendantAuxDocsForSpecificTypes;
    private Set<QName> typesForSkippingDescendantAuxDocs = new HashSet<QName>();
    private BooleanQuery skippingDocsQuery;
    protected final static Logger log = LoggerFactory.getLogger(SolrInformationServer.class);

    public SolrInformationServer(AlfrescoCoreAdminHandler adminHandler, SolrCore core)
    {
        this.adminHandler = adminHandler;
        this.core = core;

        Properties p = core.getResourceLoader().getCoreProperties();
        alfrescoVersion = p.getProperty("alfresco.version", "4.2.2");
        authorityCacheSize = Integer.parseInt(p.getProperty("solr.authorityCache.size", "64"));
        filterCacheSize = Integer.parseInt(p.getProperty("solr.filterCache.size", "64"));
        pathCacheSize = Integer.parseInt(p.getProperty("solr.pathCache.size", "64"));
        transformContent = Boolean.parseBoolean(p.getProperty("alfresco.index.transformContent", "true"));
        lag = Integer.parseInt(p.getProperty("alfresco.lag", "1000"));
        holeRetention = Integer.parseInt(p.getProperty("alfresco.hole.retention", "3600000"));
        
        dataModel = AlfrescoSolrDataModel.getInstance();
        

        skipDescendantAuxDocsForSpecificTypes = Boolean.parseBoolean(p.getProperty("alfresco.metadata.skipDescendantAuxDocsForSpecificTypes", "false"));

        if (skipDescendantAuxDocsForSpecificTypes)
        {
            int i = 0;
            skippingDocsQuery = new BooleanQuery();
            for (String key = new StringBuilder(PROP_PREFIX_PARENT_TYPE).append(i).toString(); p.containsKey(key); key = new StringBuilder(PROP_PREFIX_PARENT_TYPE).append(++i)
                    .toString())
            {
                String qName = p.getProperty(key);
                if ((null != qName) && !qName.isEmpty())
                {
                    QName typeQName = QName.resolveToQName(/*dataModel.getNamespaceDAO()*/null, qName); // TODO
                    TypeDefinition type = dataModel.getDictionaryService(CMISStrictDictionaryService.DEFAULT).getType(typeQName);
                    if (null != type)
                    {
                        typesForSkippingDescendantAuxDocs.add(typeQName);
                        skippingDocsQuery.add(new TermQuery(new Term(QueryConstants.FIELD_TYPE, typeQName.toString())), Occur.SHOULD);
                    }
                }
            }
        }
    }

    public String getAlfrescoVersion()
    {
        return this.alfrescoVersion;
    }
    
    @Override
    public void afterInitModels()
    {
        this.dataModel.afterInitModels();
    }

    @Override
    public AclReport checkAclInIndex(Long arg0, AclReport arg1)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void checkCache() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public IndexHealthReport checkIndexTransactions(IndexHealthReport arg0, Long arg1, Long arg2, IOpenBitSet arg3,
                long arg4, IOpenBitSet arg5, long arg6) throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public NodeReport checkNodeCommon(NodeReport arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void commit() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void deleteByAclChangeSetId(Long arg0) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void deleteByAclId(Long arg0) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void deleteByNodeId(Long arg0) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void deleteByTransactionId(Long arg0) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public List<AlfrescoModel> getAlfrescoModels()
    {
//        return this.dataModel.getAlfrescoModels();
        return null;
    }

    @Override
    public Iterable<Entry<String, Object>> getCoreStats() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DictionaryComponent getDictionaryService(String alternativeDictionary)
    {
        return this.dataModel.getDictionaryService(alternativeDictionary);
    }

    @Override
    public int getDocSetSize(String targetTxId, String targetTxCommitTime) throws IOException
    {
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();
            BooleanQuery query = new BooleanQuery();
            query.add(new TermQuery(new Term(QueryConstants.FIELD_TXID, targetTxId)), Occur.MUST);
            query.add(new TermQuery(new Term(QueryConstants.FIELD_TXCOMMITTIME, targetTxCommitTime)), Occur.MUST);
            DocSet set = solrIndexSearcher.getDocSet(query);

            return set.size();
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    @Override
    public Set<Long> getErrorDocIds() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long getHoleRetention()
    {
        return this.holeRetention;
    }

    @Override
    public M2Model getM2Model(QName modelQName)
    {
        return this.dataModel.getM2Model(modelQName);
    }

    @Override
    public Map<String, Set<String>> getModelErrors()
    {
//        return dataModel.getModelErrors();
        return null; // TODO
    }

    @Override
    public NamespaceDAO getNamespaceDAO()
    {
//        return this.dataModel.getNamespaceDAO();
        return null; // TODO
    }

    @Override
    public IOpenBitSet getOpenBitSetInstance()
    {
        return new SolrOpenBitSetAdapter();
    }

    @Override
    public int getRegisteredSearcherCount()
    {
        HashSet<String> keys = new HashSet<String>();

        for (String key : core.getInfoRegistry().keySet())
        {
            SolrInfoMBean mBean = core.getInfoRegistry().get(key);
            if (mBean != null)
            {
                if (mBean.getName().equals(SolrIndexSearcher.class.getName()))
                {
                    if (!key.equals("searcher"))
                    {
                        keys.add(key);
                    }
                }
            }
        }

        log.info(".... registered Searchers for " + core.getName() + " = " + keys.size());
        return keys.size();

    }

    @Override
    public <T> ISimpleOrderedMap<T> getSimpleOrderedMapInstance()
    {
        return new SolrSimpleOrderedMap<T>();
    }

    @Override
    public TrackerStats getTrackerStats()
    {
        return this.trackerStats;
    }

    @Override
    public TrackerState getTrackerInitialState() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public TrackerState getTrackerState()
    {
        return this.trackerState;
    }

    @Override
    public long indexAcl(List<AclReaders> arg0, boolean arg1) throws IOException
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void indexAclTransaction(AclChangeSet arg0, boolean arg1) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void indexNode(Node node, boolean overwrite) throws IOException, AuthenticationException, JSONException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void indexTransaction(Transaction info, boolean overwrite) throws IOException
    {
        AddUpdateCommand cmd = new AddUpdateCommand(null); // TODO: Add SolrQueryRequest req
        cmd.overwrite = overwrite;
        SolrInputDocument input = new SolrInputDocument();
        input.addField(QueryConstants.FIELD_ID, "TX-" + info.getId());
        input.addField(QueryConstants.FIELD_TXID, info.getId());
        input.addField(QueryConstants.FIELD_INTXID, info.getId());
        input.addField(QueryConstants.FIELD_TXCOMMITTIME, info.getCommitTimeMs());
        cmd.solrDoc = input;
//        cmd.doc = toDocument(cmd.getSolrInputDocument(), core.getLatestSchema(), dataModel);
        core.getUpdateHandler().addDoc(cmd);
    }

    
    
    @Override
    public boolean isInIndex(String arg0, long arg1) throws IOException
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean putModel(M2Model model)
    {
        return this.dataModel.putModel(model);
    }

    @Override
    public void rollback() throws IOException
    {
        // TODO Auto-generated method stub
    }

}
