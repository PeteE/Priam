package com.netflix.priam.defaultimpl;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.priam.IConfiguration;
import com.netflix.priam.backup.Restore;
import com.netflix.priam.utils.CassandraTuner;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public class StandardTuner implements CassandraTuner
{
    private static final Logger logger = LoggerFactory.getLogger(StandardTuner.class);
    private static final String CL_BACKUP_PROPS_FILE = "/conf/commitlog_archiving.properties";
    protected final IConfiguration config;

    @Inject
    public StandardTuner(IConfiguration config)
    {
        this.config = config;
    }

    public void writeAllProperties(String yamlLocation, String hostname, String seedProvider) throws IOException
    {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        File yamlFile = new File(yamlLocation);
        Map map = (Map) yaml.load(new FileInputStream(yamlFile));
        map.put("cluster_name", config.getAppName());
        map.put("storage_port", config.getStoragePort());
        map.put("ssl_storage_port", config.getSSLStoragePort());
        map.put("start_rpc", config.isThriftEnabled());
        map.put("rpc_port", config.getThriftPort());
        map.put("start_native_transport", config.isNativeTransportEnabled());
        map.put("native_transport_port", config.getNativeTransportPort());
        map.put("listen_address", hostname);
        map.put("rpc_address", hostname);
        //Dont bootstrap in restore mode
        map.put("auto_bootstrap", !Restore.isRestoreEnabled(config));
        map.put("saved_caches_directory", config.getCacheLocation());
        map.put("commitlog_directory", config.getCommitLogLocation());
        map.put("data_file_directories", Lists.newArrayList(config.getDataFileLocation()));
        boolean enableIncremental = (config.getBackupHour() >= 0 && config.isIncrBackup()) && (CollectionUtils.isEmpty(config.getBackupRacs()) || config.getBackupRacs().contains(config.getRac()));
        map.put("incremental_backups", enableIncremental);
        map.put("endpoint_snitch", getSnitch());
        map.put("in_memory_compaction_limit_in_mb", config.getInMemoryCompactionLimit());
        map.put("compaction_throughput_mb_per_sec", config.getCompactionThroughput());
        map.put("partitioner", derivePartitioner(map.get("partitioner").toString(), config.getPartitioner()));

        map.put("memtable_total_space_in_mb", config.getMemtableTotalSpaceMB());
        map.put("stream_throughput_outbound_megabits_per_sec", config.getStreamingThroughputMB());
        map.put("multithreaded_compaction", config.getMultithreadedCompaction());

        map.put("max_hint_window_in_ms", config.getMaxHintWindowInMS());
        map.put("hinted_handoff_throttle_in_kb", config.getHintedHandoffThrottleKb());
        map.put("authenticator", config.getAuthenticator());
        map.put("authorizer", config.getAuthorizer());
        map.put("internode_compression", config.getInternodeCompression());
        map.put("dynamic_snitch", config.isDynamicSnitchEnabled());

        map.put("concurrent_reads", config.getConcurrentReadsCnt());
        map.put("concurrent_writes", config.getConcurrentWritesCnt());
        map.put("concurrent_compactors", config.getConcurrentCompactorsCnt());

        List<?> seedp = (List) map.get("seed_provider");
        Map<String, String> m = (Map<String, String>) seedp.get(0);
        m.put("class_name", seedProvider);

        configfureSecurity(map);
        configureGlobalCaches(config, map);
        //force to 1 until vnodes are properly supported
	    map.put("num_tokens", 1);

        logger.info(yaml.dump(map));
        yaml.dump(map, new FileWriter(yamlFile));

        configureCommitLogBackups();
    }

    /**
     * Overridable by derived classes to inject a wrapper snitch.
     *
     * @return Sntich to be used by this cluster
     */
    protected String getSnitch()
    {
        return config.getSnitch();
    }

    /**
     * Setup the cassandra 1.1 global cache values
     */
    private void configureGlobalCaches(IConfiguration config, Map yaml)
    {
        final String keyCacheSize = config.getKeyCacheSizeInMB();
        if(keyCacheSize != null)
        {
            yaml.put("key_cache_size_in_mb", Integer.valueOf(keyCacheSize));

            final String keyCount = config.getKeyCacheKeysToSave();
            if(keyCount != null)
                yaml.put("key_cache_keys_to_save", Integer.valueOf(keyCount));
        }

        final String rowCacheSize = config.getRowCacheSizeInMB();
        if(rowCacheSize != null)
        {
            yaml.put("row_cache_size_in_mb", Integer.valueOf(rowCacheSize));

            final String rowCount = config.getRowCacheKeysToSave();
            if(rowCount != null)
                yaml.put("row_cache_keys_to_save", Integer.valueOf(rowCount));
        }
    }

    String derivePartitioner(String fromYaml, String fromConfig)
    {
        if(fromYaml == null || fromYaml.isEmpty())
            return fromConfig;
        //this check is to prevent against overwriting an existing yaml file that has
        // a partitioner not RandomPartitioner or (as of cass 1.2) Murmur3Partitioner.
        //basically we don't want to hose existing deployments by changing the partitioner unexpectedly on them
        final String lowerCase = fromYaml.toLowerCase();
        if(lowerCase.contains("randomparti") || lowerCase.contains("murmur"))
            return fromConfig;
        return fromYaml;
    }

    protected void configfureSecurity(Map map)
    {
        //the client-side ssl settings
        Map clientEnc = (Map) map.get("client_encryption_options");
        clientEnc.put("enabled", config.isClientSslEnabled());

        //the server-side (internode) ssl settings
        Map serverEnc = (Map)map.get("server_encryption_options");
        serverEnc.put("internode_encryption", config.getInternodeEncryption());
    }

    protected void configureCommitLogBackups() throws IOException
    {
        if(!config.isBackingUpCommitLogs())
            return;
        Properties props = new Properties();
        props.put("archive_command", config.getCommitLogBackupArchiveCmd());
        props.put("restore_command", config.getCommitLogBackupRestoreCmd());
        props.put("restore_directories", config.getCommitLogBackupRestoreFromDirs());
        props.put("restore_point_in_time", config.getCommitLogBackupRestorePointInTime());

        FileOutputStream fos = null;
        try
        {
            fos = new FileOutputStream(new File(config.getCassHome() + CL_BACKUP_PROPS_FILE));
            props.store(fos, "cassandra commit log archive props, as written by priam");
        }
        finally
        {
            IOUtils.closeQuietly(fos);
        }
    }

    public void updateAutoBootstrap(String yamlFile, boolean autobootstrap) throws IOException
    {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        @SuppressWarnings("rawtypes")
        Map map = (Map) yaml.load(new FileInputStream(yamlFile));
        //Dont bootstrap in restore mode
        map.put("auto_bootstrap", autobootstrap);
        logger.info("Updating yaml" + yaml.dump(map));
        yaml.dump(map, new FileWriter(yamlFile));
    }
}
