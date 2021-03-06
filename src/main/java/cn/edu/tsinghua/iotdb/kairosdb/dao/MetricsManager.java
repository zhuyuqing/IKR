package cn.edu.tsinghua.iotdb.kairosdb.dao;

import cn.edu.tsinghua.iotdb.kairosdb.conf.Config;
import cn.edu.tsinghua.iotdb.kairosdb.conf.ConfigDescriptor;
import cn.edu.tsinghua.iotdb.kairosdb.query.result.MetricResult;
import cn.edu.tsinghua.iotdb.kairosdb.query.result.MetricValueResult;
import cn.edu.tsinghua.iotdb.kairosdb.query.result.QueryDataPoint;
import cn.edu.tsinghua.iotdb.kairosdb.rollup.RollUp;
import cn.edu.tsinghua.iotdb.kairosdb.rollup.RollUpException;
import cn.edu.tsinghua.iotdb.kairosdb.rollup.RollUpRecovery;
import cn.edu.tsinghua.iotdb.kairosdb.rollup.RollUpStoreImpl;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(MetricsManager.class);

  private static final Config config = ConfigDescriptor.getInstance().getConfig();

  private static final String ERROR_OUTPUT_FORMATTER = "%s: %s";

  // The metadata maintained in the memory
  private static final Map<String, Map<String, Integer>> tagOrder = new ConcurrentHashMap<>();

  // The SQL will be used to create metadata
  private static final String SYSTEM_CREATE_SQL = "CREATE TIMESERIES root.SYSTEMNEWX.TAG_NAME_INFO.%s WITH DATATYPE=%s, ENCODING=%s";
  private static final String METADATA_SERVICE_CREATE_SQL = "CREATE TIMESERIES root.SYSTEMNEWX.METADATA_SERVICE.%s WITH DATATYPE=%s, ENCODING=%s";

  // The SQL will be used to create rollup persistence data
  private static final String ROLLUP_CREATE_SQL = "CREATE TIMESERIES root.SYSTEMNEWX.ROLLUP.%s WITH DATATYPE=%s, ENCODING=%s";
  private static final String JSON = "json";

  // The constants of encoding methods
  private static final String TEXT_ENCODING = "PLAIN";
  private static final String INT64_ENCODING = "TS_2DIFF";
  private static final String INT32_ENCODING = "TS_2DIFF";
  private static final String DOUBLE_ENCODING = "GORILLA";

  // Storage group relevant config
  private static int storageGroupSize = config.STORAGE_GROUP_SIZE;
  private static final String STORAGE_GROUP_PREFIX = "group_";

  private static final int START_STORAGE_GROUP = 60;//specially for zc

  //index for persistence tag info in IoTDB
  private static AtomicLong index = new AtomicLong(1);

  private MetricsManager() {
  }

  /**
   * Load all of the metadata from database into memory. If the storage groups of metadata exist,
   * load out the content. If the storage groups of metadata don't exist, create all of the
   * TIMESERIES for persistent.
   */
  public static void loadMetadata(Connection connection) {
    LOGGER.info("Start loading system data.");
    Statement statement = null;
    try {
      // Judge whether the TIMESERIES(root.SYSTEMNEWX.TAG_NAME_INFO) has been created
      statement = connection.createStatement();
      statement.execute(String.format("SHOW TIMESERIES root.%s", "SYSTEMNEWX"));
      ResultSet rs = statement.getResultSet();
      if (rs.next()) {
        /* Since the TIMESERIES are created
         * Recover the tag_key-potion mapping */
        statement = connection.createStatement();
        statement.execute(String
            .format("SELECT metric_name,tag_name,tag_order FROM %s", "root.SYSTEMNEWX.TAG_NAME_INFO"));
        rs = statement.getResultSet();
        long maxIndex = 0;
        while (rs.next()) {
          String name = rs.getString(2);
          String tagName = rs.getString(3);
          Integer pos = rs.getInt(4);
          if (tagOrder.containsKey(name)) {
            tagOrder.get(name).put(tagName, pos);
          } else {
            Map<String, Integer> map = new HashMap<>();
            map.put(tagName, pos);
            tagOrder.put(name, map);
          }
          maxIndex = rs.getLong(1);
        }
        index.set(maxIndex + 1);

        // Read the size of storage group
        statement.execute(String.format("SELECT storage_group_size FROM %s",
            "root.SYSTEMNEWX.TAG_NAME_INFO"));
        rs = statement.getResultSet();
        if (rs.next()) {
          storageGroupSize = rs.getInt(2);
        } else {
          LOGGER.error("Database metadata has broken, use 30 as storage group size.");
//          System.exit(1);
          storageGroupSize = 30;
        }

        // Read the rollup tasks
        RollUpStoreImpl rollUpStore = new RollUpStoreImpl();

        try {
          Map<String, RollUp> historyTasks = rollUpStore.read();
          RollUpRecovery rollUpRecovery = new RollUpRecovery();
          rollUpRecovery.recover(historyTasks);
        } catch (RollUpException e) {
          LOGGER.error("Recover history rollup tasks failed because ", e);
        }
      } else {
        /* Since the TIMESERIES are not created
         * Create all the relevant TIMESERIES of metadata */
        statement.execute(String.format("SET STORAGE GROUP TO root.%s", "SYSTEMNEWX"));
        statement.execute(String.format(SYSTEM_CREATE_SQL, "metric_name", "TEXT", TEXT_ENCODING));
        statement.execute(String.format(SYSTEM_CREATE_SQL, "tag_name", "TEXT", TEXT_ENCODING));
        statement.execute(String.format(SYSTEM_CREATE_SQL, "tag_order", "INT32", INT32_ENCODING));

        statement
            .execute(String.format(METADATA_SERVICE_CREATE_SQL, "service", "TEXT", TEXT_ENCODING));
        statement.execute(
            String.format(METADATA_SERVICE_CREATE_SQL, "service_key", "TEXT", TEXT_ENCODING));
        statement.execute(String.format(METADATA_SERVICE_CREATE_SQL, "key", "TEXT", TEXT_ENCODING));
        statement.execute(
            String.format(METADATA_SERVICE_CREATE_SQL, "key_value", "TEXT", TEXT_ENCODING));

        // Initialize the storage group with STORAGE_GROUP_SIZE which is specified by config.properties
        statement.execute(
            String.format(SYSTEM_CREATE_SQL, "storage_group_size", "INT32", INT32_ENCODING));
        statement.execute(String.format(
            "insert into root.SYSTEMNEWX.TAG_NAME_INFO(timestamp, storage_group_size) values(%s, %s);",
            new Date().getTime(), storageGroupSize));
        for (int i = START_STORAGE_GROUP; i < storageGroupSize; i++) {
          statement
              .execute(String.format("SET STORAGE GROUP TO root.%s%s", STORAGE_GROUP_PREFIX, i));
        }

        // Create timeseries to persistence rollup tasks
        statement.execute(String.format(ROLLUP_CREATE_SQL, JSON, "TEXT", TEXT_ENCODING));
      }

    } catch (SQLException e) {
      LOGGER.error(String.format(ERROR_OUTPUT_FORMATTER, e.getClass().getName(), e.getMessage()));
    } finally {
      close(statement);
    }
    LOGGER.info("Finish loading system data.");

  }

  /**
   * Create a new TIMESERIES with given name, path and type.
   *
   * @param metricName The name of metric will be placed at the end of the path
   * @param path The path prefix of TIMESERIES
   * @param type The type of incoming data
   * @throws SQLException The exception will be throw when some errors occur while creating
   */
  private static void createNewMetric(String metricName, String path, String type)
      throws SQLException {
    String datatype;
    String encoding;
    switch (type) {
      case "long":
        //datatype = "INT64";
        //encoding = INT64_ENCODING;
        //break;
      case "double":
        datatype = "DOUBLE";
        encoding = DOUBLE_ENCODING;
        break;
      default:
        datatype = "TEXT";
        encoding = TEXT_ENCODING;
    }
    for (Connection conn : IoTDBConnectionPool.getInstance().getConnections()) {
      try (Statement statement = conn.createStatement()) {
        statement.execute(String
            .format(
                "CREATE TIMESERIES root.%s%s.%s WITH DATATYPE=%s, ENCODING=%s, COMPRESSOR=SNAPPY",
                getStorageGroupName(path), path, metricName, datatype, encoding));
      }
    }
  }

  private static void createNewMetricAndIgnoreErrors(String metricName, String path, String type) {
    try {
      createNewMetric(metricName, path, type);
    } catch (SQLException ignore) {
      // ignore the exception
    }
  }

  public static void addDataPoints(MetricResult metric, String metricName) {
    try {
      List<Connection> connections = IoTDBConnectionPool.getInstance().getConnections();
      for (Connection conn : connections) {
        for (MetricValueResult valueResult : metric.getResults()) {
          if ((valueResult.isTextType() && metric.getResults().size() > 1)
              || valueResult.getDatapoints() == null
              || valueResult.getDatapoints().get(0) == null) {
            continue;
          }
          Map<String, String> tag = new HashMap<>();
          tag.put("saved_from", valueResult.getName());

          HashMap<Integer, String> orderTagKeyMap = getMapping(metricName, tag);

          String path = generatePath(tag, orderTagKeyMap);

          Statement statement = conn.createStatement();

          for (QueryDataPoint point : valueResult.getDatapoints()) {
            String insertingSql = String
                .format("insert into root.%s%s(timestamp,%s) values(%s,%s);",
                    getStorageGroupName(path),
                    path, metricName, point.getTimestamp(), point.getAsString());
            statement.addBatch(insertingSql);
          }

          String type;
          switch (valueResult.getDatapoints().get(0).getType()) {
            case Types.INTEGER:
              type = "long";
              break;
            case Types.DOUBLE:
              type = "double";
              break;
            default:
              type = "text";
              break;
          }

          createNewMetricAndIgnoreErrors(metricName, path, type);

          statement.executeBatch();

          statement.close();

        }
      }

    } catch (SQLException e) {
      LOGGER.error(String.format(ERROR_OUTPUT_FORMATTER, e.getClass().getName(), e.getMessage()));
    }
  }

  public static void deleteMetric(String metricName) {
    try {
      List<Connection> connections = IoTDBConnectionPool.getInstance().getConnections();
      for (Connection conn : connections) {
        Statement statement = conn.createStatement();

        Map<String, Integer> mapping = tagOrder.getOrDefault(metricName, null);

        if (mapping == null) {
          return;
        }

        int size = mapping.size();

        for (int i = 0; i <= size; i++) {
          StringBuilder builder = new StringBuilder("DELETE TIMESERIES root.*");
          builder.append(".");
          for (int j = 0; j < i; j++) {
            builder.append("*.");
          }
          builder.append(metricName);
          executeAndIgnoreException(statement, builder.toString());
        }

        tagOrder.remove(metricName);
      }
    } catch (SQLException e) {
      LOGGER.error(String.format(ERROR_OUTPUT_FORMATTER, e.getClass().getName(), e.getMessage()));
    }
  }

  /**
   * Get or generate the mapping rule from position to tag_key of the given metric name and tags.
   *
   * @param name The metric name will be mapping
   * @param tags The tags will be computed
   * @return The mapping rule from position to tag_key
   */
  public static HashMap<Integer, String> getMapping(String name, Map<String, String> tags) {
    Map<String, Integer> tagKeyOrderMap = tagOrder.get(name);
    HashMap<Integer, String> mapping = new HashMap<>();
    HashMap<String, Integer> cache = new HashMap<>();
    if (null == tagKeyOrderMap) {
      // The metric name appears for the first time
      tagKeyOrderMap = new HashMap<>();
      Integer order = 0;
      for (String tagKey : tags.keySet()) {
        tagKeyOrderMap.put(tagKey, order);
        mapping.put(order, tagKey);
        cache.put(tagKey, order);
        order++;
      }
      tagOrder.put(name, tagKeyOrderMap);
      persistMappingCache(name, cache);
    } else {
      // The metric name exists
      for (Map.Entry<String, String> tag : tags.entrySet()) {
        Integer pos = tagKeyOrderMap.get(tag.getKey());
        if (null == pos) {
          pos = tagKeyOrderMap.size();
          tagKeyOrderMap.put(tag.getKey(), pos);
          cache.put(tag.getKey(), pos);
          persistMappingCache(name, cache);
        }
        mapping.put(pos, tag.getKey());
      }
    }
    return mapping;
  }

  /**
   * Persist the new mapping rule into database.
   *
   * @param metricName The name of the specific metric
   * @param cache The mapping cache will be persisted into database
   */
  private static void persistMappingCache(String metricName, Map<String, Integer> cache) {
    for (Map.Entry<String, Integer> entry : cache.entrySet()) {
      String sql = String.format(
          "insert into root.SYSTEMNEWX.TAG_NAME_INFO(timestamp, metric_name, tag_name, tag_order) values(%s, \"%s\", \"%s\", %s);",
          index.getAndIncrement(), metricName, entry.getKey(), entry.getValue());
      List<Connection> connections = IoTDBConnectionPool.getInstance().getConnections();
      for (Connection conn : connections) {
        try (Statement statement = conn.createStatement()) {
          statement.execute(sql);
        } catch (SQLException e) {
          LOGGER
              .error(String.format(ERROR_OUTPUT_FORMATTER, e.getClass().getName(), e.getMessage()));
        }
      }
    }
  }

  public static String generatePath(Map<String, String> tags,
      Map<Integer, String> orderTagKeyMap) {
    StringBuilder pathBuilder = new StringBuilder();
    int i = 0;
    int counter = 0;
    while (counter < tags.size()) {
      String path = tags.get(orderTagKeyMap.get(i));
      pathBuilder.append(".");
      if (null == path) {
        pathBuilder.append("d");
        counter++;
      } else {
        pathBuilder.append(path);
        counter++;
      }
      i++;
    }
    return pathBuilder.toString();
  }

  /**
   * Generate a corresponding storage group name of the given metric.
   *
   * @param path The device path of the specific metric
   * @return The corresponding storage group name of the given metric
   */
  public static String getStorageGroupName(String path) {
    if (path == null) {
      LOGGER.error(
          "MetricsManager.getStorageGroupName(String metricName): metricName could not be null.");
      return "null";
    }
    String device = path.split("\\.")[1];
//    int hashCode = 0;
//    if (device.length() == 4) {
//      hashCode = device.substring(0, 2).hashCode();
//    }else {
//      hashCode = device.substring(0, 1).hashCode();
//    }
//    int hashCode = path.split("\\.")[1].hashCode();
//    int protocal = 0;
    for (int i = 0; i < config.PROTOCAL_NUM; i++) {
      if (config.PROTOCAL_MACHINE.get(i).contains(device)) {
        return String.format("%s%s", STORAGE_GROUP_PREFIX, i);
      }
    }
    int hashCode = device.hashCode();
//    LOGGER.error("协议中不存在车辆{}", device);
    return String.format("%s%s", STORAGE_GROUP_PREFIX, Math.abs(hashCode) % storageGroupSize + START_STORAGE_GROUP);
  }

  private static void executeAndIgnoreException(Statement statement, String sql) {
    try {
      statement.execute(sql);
    } catch (SQLException ignore) {
      // Ignore
    }
  }

  /**
   * Close the statement no matter whether it is open and ignore any exception.
   *
   * @param statement The statement will be closed
   */
  private static void close(Statement statement) {
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException e) {
        LOGGER.warn(String.format(ERROR_OUTPUT_FORMATTER, e.getClass().getName(), e.getMessage()));
      }
    }
  }

  public static Map<String, Integer> getTagOrder(String metricName) {
    return tagOrder.getOrDefault(metricName, null);
  }

  public static List<String> getMetricNamesList(String prefix) {
    if (prefix == null) {
      return new ArrayList<>(tagOrder.keySet());
    } else {
      List<String> list = new ArrayList<>();
      for (String name : tagOrder.keySet()) {
        if (name.startsWith(prefix)) {
          list.add(name);
        }
      }
      return list;
    }
  }
}
