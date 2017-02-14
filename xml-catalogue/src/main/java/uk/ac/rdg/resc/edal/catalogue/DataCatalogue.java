/*******************************************************************************
 * Copyright (c) 2015 The University of Reading
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the University of Reading, nor the names of the
 *    authors or contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package uk.ac.rdg.resc.edal.catalogue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.Serializable;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.CacheConfiguration.TransactionalMode;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.MemoryUnit;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.config.PersistenceConfiguration.Strategy;
import net.sf.ehcache.config.SizeOfPolicyConfiguration;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;
import net.sf.ehcache.management.ManagementService;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.rdg.resc.edal.catalogue.jaxb.CacheInfo;
import uk.ac.rdg.resc.edal.catalogue.jaxb.CatalogueConfig;
import uk.ac.rdg.resc.edal.catalogue.jaxb.CatalogueConfig.DatasetStorage;
import uk.ac.rdg.resc.edal.catalogue.jaxb.DatasetConfig;
import uk.ac.rdg.resc.edal.catalogue.jaxb.VariableConfig;
import uk.ac.rdg.resc.edal.dataset.Dataset;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.feature.DiscreteFeature;
import uk.ac.rdg.resc.edal.graphics.exceptions.EdalLayerNotFoundException;
import uk.ac.rdg.resc.edal.graphics.utils.DatasetCatalogue;
import uk.ac.rdg.resc.edal.graphics.utils.EnhancedVariableMetadata;
import uk.ac.rdg.resc.edal.graphics.utils.FeatureCatalogue;
import uk.ac.rdg.resc.edal.graphics.utils.GraphicsUtils;
import uk.ac.rdg.resc.edal.graphics.utils.LayerNameMapper;
import uk.ac.rdg.resc.edal.graphics.utils.PlottingDomainParams;
import uk.ac.rdg.resc.edal.metadata.VariableMetadata;

/**
 * A catalogues which implements {@link DatasetCatalogue},
 * {@link DatasetStorage}, and {@link FeatureCatalogue}. Given a
 * {@link CacheConfiguration}, this is able to return {@link Dataset}s, and
 * {@link Collection}s of {@link DiscreteFeature}s given a single {@link String}
 * layer identifier.
 *
 * It also provides a cache of {@link DiscreteFeature}s for speed.
 *
 * @author Guy Griffiths
 */
public class DataCatalogue implements DatasetCatalogue, DatasetStorage, FeatureCatalogue {
    private static final Logger log = LoggerFactory.getLogger(DataCatalogue.class);

    private static final String WMS_CACHE_CONFIG = "ehcache.config";
    private static final String CACHE_MANAGER = "EDAL-CacheManager";
    private static final String FEATURE_CACHE = "featureCache";
    private static final int FC_SIZE = 512;
    private static final int FC_LIFETIME_SECONDS = 0;
    private static final int FC_MAX_CACHE_DEPTH = 4_000_000;
    final MemoryStoreEvictionPolicy FC_EVICTION_POLICY = MemoryStoreEvictionPolicy.LFU;
    private static final Strategy FC_PERSISTENCE_STRATEGY = Strategy.NONE;
    private static final TransactionalMode FC_TRANSACTIONAL_MODE = TransactionalMode.OFF;

    protected static CacheManager cacheManager;
    private Cache featureCache = null;
    private static MBeanServer mBeanServer;
    private static ObjectName cacheManagerObjectName;
    private boolean featureCacheEnabled = true;

    private static final String DS_CACHE = "datasetCache";
    private static final int DS_SIZE = 512;
    private static final int DS_LIFETIME_SECONDS = 0;
    private static final int DS_MAX_CACHE_DEPTH = 4_000_000;
    final MemoryStoreEvictionPolicy DS_EVICTION_POLICY = MemoryStoreEvictionPolicy.LFU;
    private static final Strategy DS_PERSISTENCE_STRATEGY = Strategy.NONE;
    private static final TransactionalMode DS_TRANSACTIONAL_MODE = TransactionalMode.OFF;
    private Cache datasetCache = null;
    private boolean datasetCacheEnabled = true;

    protected final CatalogueConfig config;
    protected Map<String, Dataset> datasets;
    private final Map<DatasetVariableId, EnhancedVariableMetadata> layerMetadata;
    protected final LayerNameMapper layerNameMapper;

    private DateTime lastUpdateTime = new DateTime();

    public DataCatalogue() {
        cacheManager = null;
        config = null;
        layerMetadata = null;
        layerNameMapper = null;
    }

    public DataCatalogue(CatalogueConfig config, LayerNameMapper layerNameMapper)
            throws IOException {
        /*
         * Initialise the storage for datasets and layer metadata.
         */
        datasets = new HashMap<>();
        layerMetadata = new HashMap<>();

        this.config = config;
        this.config.setDatasetLoadedHandler(this);
        this.config.loadDatasets();

        this.layerNameMapper = layerNameMapper;

        /*
         * We are using an in-memory cache with a configured memory size (as
         * opposed to a configured number of items in memory). This has the
         * advantage that we will get a hard limit on the amount of memory the
         * cache consumes. The disadvantage is that the size of each object
         * needs to be calculated prior to inserting it into the cache.
         *
         * The maxDepth property specified the maximum number of object
         * references to count before a warning is given.
         *
         * Now, we are generally caching 2 things:
         *
         * 1) Gridded map features which will generally have 256*256 ~= 65,000
         * values, but could easily be bigger
         *
         * 2) Collections of point features. A year's worth of EN3 data could
         * typically contain >15,000 features, each with a number of properties
         *
         * These can need to count a very large number of object references.
         * However, this calculation is actually pretty quick. Setting the max
         * depth to 4,000,000 seems to suppress the vast majority of warnings,
         * and doesn't impact performance noticeably.
         *
         * Cache configuration specified in resources/ehcache.xml
         */
        String ehcache_file = System.getProperty(WMS_CACHE_CONFIG);
        if (ehcache_file != null && !ehcache_file.isEmpty()) {
            cacheManager = CacheManager.create(System.getProperty(WMS_CACHE_CONFIG));
        }
        else {
            int maxCacheDepth = (FC_MAX_CACHE_DEPTH > DS_MAX_CACHE_DEPTH) ? FC_MAX_CACHE_DEPTH : DS_MAX_CACHE_DEPTH;
            cacheManager = CacheManager.create(new Configuration().name(CACHE_MANAGER)
                    .sizeOfPolicy(new SizeOfPolicyConfiguration().maxDepth(maxCacheDepth)));
        }

        if (cacheManager.cacheExists(FEATURE_CACHE) == false) {
             /*
              * Configure featureCache
              */
            CacheConfiguration cacheConfig = new CacheConfiguration(FEATURE_CACHE, 0)
                    .eternal(true)
                    .maxBytesLocalHeap(FC_SIZE, MemoryUnit.MEGABYTES)
                    .memoryStoreEvictionPolicy(FC_EVICTION_POLICY)
                    .persistence(new PersistenceConfiguration().strategy(FC_PERSISTENCE_STRATEGY))
                    .transactionalMode(FC_TRANSACTIONAL_MODE);
            featureCache = new Cache(cacheConfig);
            cacheManager.addCache(featureCache);
        } else {
            /*
             * Use parameters for featureCache from ehcache.xml config file if passed in as JVM parameter ehcache.config
             * - Update cache params in NwcmsConfig
             */
            featureCache = cacheManager.getCache(FEATURE_CACHE);
            CacheInfo catalogueCacheInfo = config.getCacheSettings();
            CacheConfiguration featureCacheConfiguration = featureCache.getCacheConfiguration();
            catalogueCacheInfo.setFeatureCacheInMemorySizeMB((int) (featureCacheConfiguration.getMaxBytesLocalHeap() / (1024 * 1024)));
            catalogueCacheInfo.setFeatureCacheElementLifetimeMinutes(featureCacheConfiguration.getTimeToLiveSeconds() / 60);
            catalogueCacheInfo.setFeatureCacheEnabled(true);
        }

        if (cacheManager.cacheExists(DS_CACHE) == false) {
             /*
              * Configure datasetCache
              */
            CacheConfiguration cacheConfig = new CacheConfiguration(DS_CACHE, 0)
                    .eternal(true)
                    .maxBytesLocalHeap(DS_SIZE, MemoryUnit.MEGABYTES)
                    .memoryStoreEvictionPolicy(DS_EVICTION_POLICY)
                    .persistence(new PersistenceConfiguration().strategy(DS_PERSISTENCE_STRATEGY))
                    .transactionalMode(DS_TRANSACTIONAL_MODE);

            datasetCache = new Cache(cacheConfig);
            cacheManager.addCache(datasetCache);
        } else {
            /*
             * Use parameters for featureCache from ehcache.xml config file if passed in as JVM parameter ehcache.config
             * - Update cache params in NwcmsConfig
             */
            datasetCache = cacheManager.getCache(DS_CACHE);
            CacheInfo catalogueCacheInfo = config.getCacheSettings();
            CacheConfiguration datasetCacheConfiguration = datasetCache.getCacheConfiguration();
            catalogueCacheInfo.setDatasetCacheInMemorySizeMB((int) (datasetCacheConfiguration.getMaxBytesLocalHeap() / (1024 * 1024)));
            catalogueCacheInfo.setDatasetCacheElementLifetimeMinutes(datasetCacheConfiguration.getTimeToLiveSeconds() / 60);
            catalogueCacheInfo.setDatasetCacheEnabled(true);
        }

        /*
         * Used to gather statistics about Ehcache
         */
        mBeanServer = ManagementFactory.getPlatformMBeanServer();
        try {
            cacheManagerObjectName = new ObjectName("net.sf.ehcache:type=CacheManager,name="
                            + cacheManager.getName());
        } catch (MalformedObjectNameException e) {
            throw new EdalException("unable to form cacheManager ObjectName", e);
        }

        if (!mBeanServer.isRegistered(cacheManagerObjectName)){
            ManagementService.registerMBeans(cacheManager, mBeanServer, true, true, true, true);
        }
    }

    public CatalogueConfig getConfig() {
        return config;
    }

    public void shutdown() {
        CatalogueConfig.shutdown();
    }

    /**
     * Configures the cache used to store features
     *
     * @param cacheConfig
     *            The (new) configuration to use for the cache. Must not be
     *            <code>null</code>
     * @param cacheName
     *           The name of the cache to be configured
      */
    public void setCache(CacheInfo cacheConfig, String cacheName)  {
        MemoryStoreEvictionPolicy memoryStoreEviction;
        Strategy persistenceStrategy;
        TransactionalMode transactionalMode;
        long lifetimeSeconds;
        long configLifetimeSeconds;
        long cacheSizeMB;
        long configCacheSizeMB;
        Cache cache;
        boolean cacheCurrentlyEnabled;
        boolean requestCacheEnabled;

        if (cacheName.equals(FEATURE_CACHE)) {
            cache = featureCache;
            // Default values
            cacheSizeMB = FC_SIZE;
            lifetimeSeconds = FC_LIFETIME_SECONDS;
            memoryStoreEviction = FC_EVICTION_POLICY;
            persistenceStrategy = FC_PERSISTENCE_STRATEGY;
            transactionalMode = FC_TRANSACTIONAL_MODE;
            cacheCurrentlyEnabled = cacheConfig.isFeatureCacheEnabled();
            requestCacheEnabled = featureCacheEnabled;
            configLifetimeSeconds = (long) (cacheConfig.getFeatureCacheElementLifetimeMinutes() * 60);
            configCacheSizeMB = cacheConfig.getFeatureCacheInMemorySizeMB();
        } else if (cacheName.equals(DS_CACHE)) {
            cache = datasetCache;
            // Default values
            cacheSizeMB = DS_SIZE;
            lifetimeSeconds = DS_LIFETIME_SECONDS;
            memoryStoreEviction = DS_EVICTION_POLICY;
            persistenceStrategy = DS_PERSISTENCE_STRATEGY;
            transactionalMode = DS_TRANSACTIONAL_MODE;
            cacheCurrentlyEnabled = cacheConfig.isDatasetCacheEnabled();
            requestCacheEnabled = datasetCacheEnabled;
            configLifetimeSeconds = (long) (cacheConfig.getDatasetCacheElementLifetimeMinutes() * 60);
            configCacheSizeMB = cacheConfig.getDatasetCacheInMemorySizeMB();
        } else {
            throw new EdalException("No cache named:" + cacheName);
        }

        if (cache != null &&
            cacheCurrentlyEnabled == requestCacheEnabled &&
            configCacheSizeMB == cache.getCacheConfiguration().getMaxBytesLocalHeap() / (1024 * 1024) &&
            configLifetimeSeconds == cache.getCacheConfiguration().getTimeToLiveSeconds()) {
            /*
             * We are not changing anything about the cache.
             */
            return;
        }

        if (cacheCurrentlyEnabled) {
            if (cacheManager.cacheExists(cacheName)) {
                /*
                * Update cache configuration
                */
                CacheConfiguration config = cache.getCacheConfiguration();
                config.setTimeToLiveSeconds(configLifetimeSeconds);
                config.setMaxBytesLocalHeap((long) configCacheSizeMB * 1024 * 1024);
            } else {
                /*
                 * - Precedence:
                 *  - Admin config
                 *  - XML file "ehcache.config"
                 *  - Default values
                 */

                /*
                 * XML config
                 */
                String ehcache_file = System.getProperty("ehcache.config");
                if (ehcache_file != null && !ehcache_file.isEmpty()) {
                    CacheManager tmpCacheManager = CacheManager.create(System.getProperty("ehcache.config"));
                    Cache tmpCache = cacheManager.getCache(cacheName);
                    cacheSizeMB = tmpCache.getCacheConfiguration().getMaxBytesLocalHeap() / (1024 * 1024);
                    lifetimeSeconds = tmpCache.getCacheConfiguration().getTimeToLiveSeconds();
                    memoryStoreEviction = tmpCache.getCacheConfiguration().getMemoryStoreEvictionPolicy();
                    persistenceStrategy = tmpCache.getCacheConfiguration().getPersistenceConfiguration().getStrategy();
                    transactionalMode = tmpCache.getCacheConfiguration().getTransactionalMode();
                }

                /*
                 * Admin
                 */
                if (cacheName.equals(FEATURE_CACHE)) {
                    if (cacheConfig.getFeatureCacheInMemorySizeMB() != 0) {
                        cacheSizeMB = configCacheSizeMB;
                    }
                    if (cacheConfig.getFeatureCacheElementLifetimeMinutes() != 0) {
                        lifetimeSeconds = configLifetimeSeconds;
                    }
                    featureCacheEnabled = requestCacheEnabled;
                } else {
                    if (cacheConfig.getDatasetCacheInMemorySizeMB() != 0) {
                        cacheSizeMB = configCacheSizeMB;
                    }
                    if (cacheConfig.getDatasetCacheElementLifetimeMinutes() != 0) {
                        lifetimeSeconds = configLifetimeSeconds;
                    }
                    datasetCacheEnabled = requestCacheEnabled;
                }

                /*
                 * Configure and create cache
                 */
                CacheConfiguration config = new CacheConfiguration(cacheName, 0)
                        .eternal(lifetimeSeconds == 0)
                        .maxBytesLocalHeap(cacheSizeMB, MemoryUnit.MEGABYTES)
                        .timeToLiveSeconds(lifetimeSeconds)
                        .memoryStoreEvictionPolicy(memoryStoreEviction)
                        .persistence(new PersistenceConfiguration().strategy(persistenceStrategy))
                        .transactionalMode(transactionalMode);

                cache = new Cache(config);
                cacheManager.addCache(cache);
            }
        } else {
            /*
             * Remove existing cache to free up memory
             */
            cacheManager.removeCache(cacheName);
        }
    }

    /**
     * Removes a dataset from the catalogue. This will also delete any config
     * information about the dataset from the config file.
     *
     * @param id
     *            The ID of the dataset to remove
     */
    public void removeDataset(String id) {
        datasets.remove(id);
        config.removeDataset(config.getDatasetInfo(id));
    }

    /**
     * Changes a dataset's ID. This will also change the name in the saved
     * config file.
     *
     * @param oldId
     *            The old ID
     * @param newId
     *            The new ID
     */
    public void changeDatasetId(String oldId, String newId) {
        Dataset dataset = datasets.get(oldId);
        datasets.remove(oldId);
        datasets.put(newId, dataset);
        config.changeDatasetId(config.getDatasetInfo(oldId), newId);

        if (datasetCacheEnabled) {
            datasetCache.remove(oldId);
            datasetCache.put(new Element(newId, dataset));
        }
    }

    @Override
    public synchronized void datasetLoaded(Dataset dataset, Collection<VariableConfig> variables) {
        /*
         * If we already have a dataset with this ID, it will be replaced. This
         * is exactly what we want.
         */
        datasets.put(dataset.getId(), dataset);

        /*
         * Re-sort the datasets map according to the titles of the datasets, so
         * that they appear in the menu in this order.
         */
        List<Map.Entry<String, Dataset>> entryList = new ArrayList<Map.Entry<String, Dataset>>(
                datasets.entrySet());
        try {
            Collections.sort(entryList, new Comparator<Map.Entry<String, Dataset>>() {
                public int compare(Map.Entry<String, Dataset> d1, Map.Entry<String, Dataset> d2) {
                    return config.getDatasetInfo(d1.getKey()).getTitle()
                            .compareTo(config.getDatasetInfo(d2.getKey()).getTitle());
                }
            });
        } catch (NullPointerException e) {
            log.error("Problem when sorting datasets", e);
            /*
             * Sometimes this gives a NullPointerException with remote datasets
             * which are unavailable (?)
             *
             * It's been seen a couple of times on the issue tracker, but I've
             * been unable to reproduce it. I think it may be that the title is
             * not getting set correctly (or at all?). Perhaps this needs some
             * more robust checking in the CatalogueConfig object?
             *
             * Since sorting the datasets by title isn't critical, we can ignore
             * the error.
             */
        }

        datasets = new LinkedHashMap<String, Dataset>();
        for (Map.Entry<String, Dataset> entry : entryList) {
            datasets.put(entry.getKey(), entry.getValue());
        }

        /*
         * Now add the layer metadata to a map for future reference
         */
        for (VariableConfig variable : variables) {
            DatasetVariableId id = new DatasetVariableId(variable.getParentDataset().getId(),
                    variable.getId());
            layerMetadata.put(id, variable);
        }
        lastUpdateTime = new DateTime();

        /*
         * The config has changed, so we save it.
         */
        try {
            config.save();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public DateTime getLastUpdateTime() {
        return lastUpdateTime;
    }

    @Override
    public Collection<Dataset> getAllDatasets() {
        /*
         * This catalogue stores all possible datasets, but this method must
         * only return those which are available (i.e. not disabled and ready to
         * go)
         */
        List<Dataset> allDatasets = new ArrayList<Dataset>();
        for (Dataset dataset : datasets.values()) {
            DatasetConfig datasetInfo = config.getDatasetInfo(dataset.getId());
            if (datasetInfo != null && !datasetInfo.isDisabled() && datasetInfo.isReady()) {
                allDatasets.add(dataset);
            }
        }
        return allDatasets;
    }

    @Override
    public Dataset getDatasetFromId(String datasetId) {
        if (datasets.containsKey(datasetId)) {
            return datasets.get(datasetId);
        } else {
            return null;
        }
    }

    public DatasetConfig getDatasetInfo(String datasetId) {
        return config.getDatasetInfo(datasetId);
    }

    @Override
    public EnhancedVariableMetadata getLayerMetadata(final VariableMetadata variableMetadata)
            throws EdalLayerNotFoundException {
        DatasetVariableId key = new DatasetVariableId(variableMetadata.getDataset().getId(),
                variableMetadata.getId());
        if (layerMetadata.containsKey(key)) {
            return layerMetadata.get(key);
        } else {
            throw new EdalLayerNotFoundException("No layer exists for the variable: "
                    + variableMetadata.getId() + " in the dataset: "
                    + variableMetadata.getDataset().getId());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public FeaturesAndMemberName getFeaturesForLayer(String layerName, PlottingDomainParams params)
            throws EdalException {
        String variable = layerNameMapper.getVariableIdFromLayerName(layerName);
        Collection<? extends DiscreteFeature<?, ?>> mapFeatures;
        if (featureCacheEnabled) {
            CacheKey key = new CacheKey(layerName, params);
            Element element = featureCache.get(key);

            if (element != null && element.getObjectValue() != null) {
                /*
                 * This is why we added the SuppressWarnings("unchecked").
                 */
                mapFeatures = (Collection<? extends DiscreteFeature<?, ?>>) element.getObjectValue();
            } else {
                mapFeatures = doExtraction(layerName, variable, params);
            }
        } else {
            mapFeatures = doExtraction(layerName, variable, params);
        }
        return new FeaturesAndMemberName(mapFeatures, variable);
    }

    private Collection<? extends DiscreteFeature<?, ?>> doExtraction(String layerName,
            String variable, PlottingDomainParams params) {
        Dataset dataset;
        String datasetId = layerNameMapper.getDatasetIdFromLayerName(layerName);
        if (datasetCacheEnabled) {
            Element element = datasetCache.get(datasetId);

            if (element != null && element.getObjectValue() != null) {
                dataset = (Dataset) element.getObjectValue();
            } else {
                dataset = getDatasetFromLayerName(layerName);
                datasetCache.put(new Element(datasetId, dataset));
            }
        } else {
            dataset = getDatasetFromLayerName(layerName);
        }
        return GraphicsUtils.extractGeneralMapFeatures(dataset, variable, params);
    }

    private Dataset getDatasetFromLayerName(String layerName) {
        return getDatasetFromId(layerNameMapper.getDatasetIdFromLayerName(layerName));
    }

    private static class CacheKey implements Serializable {
        private static final long serialVersionUID = 1L;
        final String id;
        final PlottingDomainParams params;

        public CacheKey(String id, PlottingDomainParams params) {
            super();
            this.id = id;
            this.params = params;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((id == null) ? 0 : id.hashCode());
            result = prime * result + ((params == null) ? 0 : params.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            CacheKey other = (CacheKey) obj;
            if (id == null) {
                if (other.id != null)
                    return false;
            } else if (!id.equals(other.id))
                return false;
            if (params == null) {
                if (other.params != null)
                    return false;
            } else if (!params.equals(other.params))
                return false;
            return true;
        }
    }

    private class DatasetVariableId {
        String datasetId;
        String variableId;

        public DatasetVariableId(String datasetId, String variableId) {
            super();
            this.datasetId = datasetId;
            this.variableId = variableId;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + getOuterType().hashCode();
            result = prime * result + ((datasetId == null) ? 0 : datasetId.hashCode());
            result = prime * result + ((variableId == null) ? 0 : variableId.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            DatasetVariableId other = (DatasetVariableId) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            if (datasetId == null) {
                if (other.datasetId != null)
                    return false;
            } else if (!datasetId.equals(other.datasetId))
                return false;
            if (variableId == null) {
                if (other.variableId != null)
                    return false;
            } else if (!variableId.equals(other.variableId))
                return false;
            return true;
        }

        private DataCatalogue getOuterType() {
            return DataCatalogue.this;
        }
    }
}
