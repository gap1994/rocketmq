/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.timer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.google.common.io.Files;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import org.apache.rocketmq.common.ConfigManager;
import org.apache.rocketmq.common.DataVersion;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.topic.TopicValidator;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.protocol.RemotingSerializable;

import java.io.IOException;
import java.io.FileWriter;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TimerMetrics extends ConfigManager {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private static final long LOCK_TIMEOUT_MILLIS = 3000;
    private transient final Lock lock = new ReentrantLock();

    private final ConcurrentMap<String, Metric> timingCount =
            new ConcurrentHashMap<>(1024);

    private final ConcurrentMap<Integer, Metric> timingDistribution =
            new ConcurrentHashMap<>(1024);
    public List<Integer> timerDist = new ArrayList<Integer>() {{
            add(5);
            add(60);
            add(300); // 5s, 1min, 5min
            add(900);
            add(3600);
            add(14400); // 15min, 1h, 4h
            add(28800);
            add(86400); // 8h, 24h
        }};
    private final DataVersion dataVersion = new DataVersion();

    private final String configPath;

    public TimerMetrics(String configPath) {
        this.configPath = configPath;
    }

    public long updateDistPair(int period, int value) {
        Metric distPair = getDistPair(period);
        return distPair.getCount().addAndGet(value);
    }

    public long addAndGet(String topic, int value) {
        Metric pair = getTopicPair(topic);
        getDataVersion().nextVersion();
        pair.setTimeStamp(System.currentTimeMillis());
        return pair.getCount().addAndGet(value);
    }

    public Metric getDistPair(Integer period) {
        Metric pair = timingDistribution.get(period);
        if (null != pair) {
            return pair;
        }
        pair = new Metric();
        final Metric previous = timingDistribution.putIfAbsent(period, pair);
        if (null != previous) {
            return previous;
        }
        return pair;
    }

    public Metric getTopicPair(String topic) {
        Metric pair = timingCount.get(topic);
        if (null != pair) {
            return pair;
        }
        pair = new Metric();
        final Metric previous = timingCount.putIfAbsent(topic, pair);
        if (null != previous) {
            return previous;
        }
        return pair;
    }

    public List<Integer> getTimerDistList() {
        return this.timerDist;
    }

    public void setTimerDistList(List<Integer> timerDist) {
        this.timerDist = timerDist;
    }

    public long getTimingCount(String topic) {
        Metric pair = timingCount.get(topic);
        if (null == pair) {
            return 0;
        } else {
            return pair.getCount().get();
        }
    }

    public Map<String, Metric> getTimingCount() {
        return timingCount;
    }

    protected void write0(Writer writer) {
        TimerMetricsSerializeWrapper wrapper = new TimerMetricsSerializeWrapper();
        wrapper.setTimingCount(timingCount);
        wrapper.setDataVersion(dataVersion);
        JSON.writeJSONString(writer, wrapper, SerializerFeature.BrowserCompatible);
    }

    @Override
    public String encode() {
        return null;
    }

    @Override
    public String configFilePath() {
        return configPath;
    }

    @Override
    public void decode(String jsonString) {
        if (jsonString != null) {
            TimerMetricsSerializeWrapper timerMetricsSerializeWrapper =
                    TimerMetricsSerializeWrapper.fromJson(jsonString, TimerMetricsSerializeWrapper.class);
            if (timerMetricsSerializeWrapper != null) {
                this.timingCount.putAll(timerMetricsSerializeWrapper.getTimingCount());
            }
        }
    }

    @Override
    public String encode(boolean prettyFormat) {
        return null;
    }

    public DataVersion getDataVersion() {
        return dataVersion;
    }

    public void cleanMetrics(Set<String> topics) {
        if (topics == null || topics.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Metric>> iterator = timingCount.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Metric> entry = iterator.next();
            final String topic = entry.getKey();
            if (topic.startsWith(TopicValidator.SYSTEM_TOPIC_PREFIX)) {
                continue;
            }
            if (topics.contains(topic)) {
                continue;
            }

            iterator.remove();
            log.info("clean timer metrics, because not in topic config, {}", topic);
        }
    }

    public static class TimerMetricsSerializeWrapper extends RemotingSerializable {
        private ConcurrentMap<String, Metric> timingCount =
                new ConcurrentHashMap<>(1024);
        private DataVersion dataVersion = new DataVersion();

        public ConcurrentMap<String, Metric> getTimingCount() {
            return timingCount;
        }

        public void setTimingCount(
                ConcurrentMap<String, Metric> timingCount) {
            this.timingCount = timingCount;
        }

        public DataVersion getDataVersion() {
            return dataVersion;
        }

        public void setDataVersion(DataVersion dataVersion) {
            this.dataVersion = dataVersion;
        }
    }

    @Override
    public synchronized void persist() {
        String config = configFilePath();
        String temp = config + ".tmp";
        String backup = config + ".bak";
        BufferedWriter bufferedWriter = null;
        try {
            File tmpFile = new File(temp);
            File parentDirectory = tmpFile.getParentFile();
            if (!parentDirectory.exists()) {
                if (!parentDirectory.mkdirs()) {
                    log.error("Failed to create directory: {}", parentDirectory.getCanonicalPath());
                    return;
                }
            }

            if (!tmpFile.exists()) {
                if (!tmpFile.createNewFile()) {
                    log.error("Failed to create file: {}", tmpFile.getCanonicalPath());
                    return;
                }
            }
            bufferedWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmpFile, false),
                StandardCharsets.UTF_8));
            write0(bufferedWriter);
            bufferedWriter.flush();
            log.debug("Finished writing tmp file: {}", temp);

            File configFile = new File(config);
            if (configFile.exists()) {
                Files.copy(configFile, new File(backup));
                configFile.delete();
            }

            tmpFile.renameTo(configFile);
        } catch (IOException e) {
            log.error("Failed to persist {}", temp, e);
        } finally {
            if (null != bufferedWriter) {
                try {
                    bufferedWriter.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    public static class Metric {
        private AtomicLong count;
        private long timeStamp;

        public Metric() {
            count = new AtomicLong(0);
            timeStamp = System.currentTimeMillis();
        }

        public AtomicLong getCount() {
            return count;
        }

        public void setCount(AtomicLong count) {
            this.count = count;
        }

        public long getTimeStamp() {
            return timeStamp;
        }

        public void setTimeStamp(long timeStamp) {
            this.timeStamp = timeStamp;
        }

        @Override
        public String toString() {
            return String.format("[%d,%d]", count.get(), timeStamp);
        }
    }

}
