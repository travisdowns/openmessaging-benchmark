/*
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
package io.openmessaging.benchmark.worker;

import static io.openmessaging.benchmark.utils.UniformRateLimiter.*;
import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.Preconditions;
import io.openmessaging.benchmark.utils.RateLimiter;

import io.openmessaging.benchmark.utils.UniformRateLimiter;
import org.HdrHistogram.Recorder;
import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.OpStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.openmessaging.benchmark.DriverConfiguration;
import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.BenchmarkDriver;
import io.openmessaging.benchmark.driver.BenchmarkProducer;
import io.openmessaging.benchmark.driver.ConsumerCallback;
import io.openmessaging.benchmark.utils.RandomGenerator;
import io.openmessaging.benchmark.utils.Timer;
import io.openmessaging.benchmark.utils.distributor.KeyDistributor;
import io.openmessaging.benchmark.worker.commands.ConsumerAssignment;
import io.openmessaging.benchmark.worker.commands.CountersStats;
import io.openmessaging.benchmark.worker.commands.CumulativeLatencies;
import io.openmessaging.benchmark.worker.commands.PeriodStats;
import io.openmessaging.benchmark.worker.commands.ProducerWorkAssignment;
import io.openmessaging.benchmark.worker.commands.TopicsInfo;

public class LocalWorker implements Worker, ConsumerCallback {

    private BenchmarkDriver benchmarkDriver = null;

    private List<BenchmarkProducer> producers = new ArrayList<>();
    private List<BenchmarkConsumer> consumers = new ArrayList<>();

    private volatile UniformRateLimiter rateLimiter = new UniformRateLimiter(1.0);

    private final ExecutorService executor = Executors.newCachedThreadPool(new DefaultThreadFactory("local-worker"));

    // stats

    private final StatsLogger statsLogger;

    private final LongAdder messagesSent = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder pollErrors = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();
    private final Counter messagesSentCounter;
    private final Counter bytesSentCounter;

    private final LongAdder messagesReceived = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();
    private final Counter messagesReceivedCounter;
    private final Counter bytesReceivedCounter;

    private final LongAdder totalMessagesSent = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder totalMessagesReceived = new LongAdder();

    private final Recorder publishLatencyRecorder = new Recorder(TimeUnit.SECONDS.toMicros(60), 5);
    private final Recorder cumulativePublishLatencyRecorder = new Recorder(TimeUnit.SECONDS.toMicros(60), 5);
    private final OpStatsLogger publishLatencyStats;

    private final Recorder scheduleLatencyRecorder = new Recorder(TimeUnit.SECONDS.toMicros(60), 5);
    private final Recorder cumulativeScheduleLatencyRecorder = new Recorder(TimeUnit.SECONDS.toMicros(60), 5);
    private final OpStatsLogger scheduleLatencyStats;

    private final Recorder publishDelayLatencyRecorder = new Recorder(TimeUnit.SECONDS.toMicros(60), 5);
    private final Recorder cumulativePublishDelayLatencyRecorder = new Recorder(TimeUnit.SECONDS.toMicros(60), 5);
    private final OpStatsLogger publishDelayLatencyStats;

    private final Recorder endToEndLatencyRecorder = new Recorder(TimeUnit.HOURS.toMicros(12), 5);
    private final Recorder endToEndCumulativeLatencyRecorder = new Recorder(TimeUnit.HOURS.toMicros(12), 5);
    private final OpStatsLogger endToEndLatencyStats;

    private boolean testCompleted = false;

    private boolean consumersArePaused = false;

    public LocalWorker() {
        this(NullStatsLogger.INSTANCE);
    }

    public LocalWorker(StatsLogger statsLogger) {
        this.statsLogger = statsLogger;

        StatsLogger producerStatsLogger = statsLogger.scope("producer");
        this.messagesSentCounter = producerStatsLogger.getCounter("messages_sent");
        this.bytesSentCounter = producerStatsLogger.getCounter("bytes_sent");
        this.publishDelayLatencyStats = producerStatsLogger.getOpStatsLogger("producer_delay_latency");
        this.publishLatencyStats = producerStatsLogger.getOpStatsLogger("produce_latency");
        this.scheduleLatencyStats = producerStatsLogger.getOpStatsLogger("schedule_latency");

        StatsLogger consumerStatsLogger = statsLogger.scope("consumer");
        this.messagesReceivedCounter = consumerStatsLogger.getCounter("messages_recv");
        this.bytesReceivedCounter = consumerStatsLogger.getCounter("bytes_recv");
        this.endToEndLatencyStats = consumerStatsLogger.getOpStatsLogger("e2e_latency");
    }

    @Override
    public void initializeDriver(File driverConfigFile) throws IOException {
        Preconditions.checkArgument(benchmarkDriver == null);
        testCompleted = false;

        DriverConfiguration driverConfiguration = mapper.readValue(driverConfigFile, DriverConfiguration.class);

        log.info("Driver: {}", writer.writeValueAsString(driverConfiguration));

        try {
            benchmarkDriver = (BenchmarkDriver) Class.forName(driverConfiguration.driverClass).newInstance();
            benchmarkDriver.initialize(driverConfigFile, statsLogger);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> createOrValidateTopics(TopicsInfo topicsInfo) {
        List<String> topics = new ArrayList<>();
        boolean useExisting = topicsInfo.isExistingTopics();

        if (useExisting) {
            for (String topicName : topicsInfo.allExistingTopics()) {
                if (!benchmarkDriver.validateTopicExists(topicName).join()) {
                    throw new RuntimeException(String.format("Topic specified in workload does not exist: %s",
                        topicName));
                }
                topics.add(topicName);
            }
        } else {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < topicsInfo.numberOfTopics; i++) {
                String topicPrefix = benchmarkDriver.getTopicNamePrefix();
                String topic = String.format("%s-%s-%04d", topicPrefix, RandomGenerator.getRandomString(), i);
                topics.add(topic);
                futures.add(benchmarkDriver.createTopic(topic, topicsInfo.numberOfPartitionsPerTopic));
            }
            futures.forEach(CompletableFuture::join);
        }

        return topics;
    }

    @Override
    public void createProducers(List<String> topics) {
        Timer timer = new Timer();

        List<CompletableFuture<BenchmarkProducer>> futures = topics.stream()
                .map(topic -> benchmarkDriver.createProducer(topic)).collect(toList());

        futures.forEach(f -> producers.add(f.join()));
        log.info("Created {} producers in {} ms", producers.size(), timer.elapsedMillis());
    }

    @Override
    public void createConsumers(ConsumerAssignment consumerAssignment) {
        Timer timer = new Timer();

        List<CompletableFuture<BenchmarkConsumer>> futures = consumerAssignment.topicsSubscriptions.stream()
                .map(ts -> benchmarkDriver.createConsumer(ts.topic, ts.subscription, this)).collect(toList());

        futures.forEach(f -> consumers.add(f.join()));
        log.info("Created {} consumers in {} ms", consumers.size(), timer.elapsedMillis());
    }

    @Override
    public void startLoad(ProducerWorkAssignment producerWorkAssignment) {
        int processors = Runtime.getRuntime().availableProcessors();

        rateLimiter = new UniformRateLimiter(producerWorkAssignment.publishRate);

        Map<Integer, List<BenchmarkProducer>> processorAssignment = new TreeMap<>();

        int processorIdx = 0;
        for (BenchmarkProducer p : producers) {
            processorAssignment.computeIfAbsent(processorIdx, x -> new ArrayList<BenchmarkProducer>()).add(p);

            processorIdx = (processorIdx + 1) % processors;
        }

        processorAssignment.values().forEach(producers -> submitProducersToExecutor(producers,
                KeyDistributor.build(producerWorkAssignment.keyDistributorType), producerWorkAssignment.payloadData));
    }

    @Override
    public void probeProducers() throws IOException {
        producers.forEach(producer -> producer.sendAsync(Optional.of("key"), new byte[24])
                .thenRun(() -> totalMessagesSent.increment()));
    }

    private void submitProducersToExecutor(List<BenchmarkProducer> producers, KeyDistributor keyDistributor, List<byte[]> payloads) {
        executor.submit(() -> {
            int payloadCount = payloads.size();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            byte[] firstPayload = payloads.get(0);

            try {
                while (!testCompleted) {
                    producers.forEach(producer -> {
                        byte[] payloadData = payloadCount == 0 ? firstPayload : payloads.get(r.nextInt(payloadCount));
                        final long intendedSendTime = rateLimiter.acquire();
                        uninterruptibleSleepNs(intendedSendTime);
                        final long sendTime = System.nanoTime();
                        CompletableFuture<Void> f = producer.sendAsync(Optional.ofNullable(keyDistributor.next()), payloadData);
                        long scheduleMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - sendTime);
                        scheduleLatencyRecorder.recordValue(scheduleMicros);
                        cumulativeScheduleLatencyRecorder.recordValue(scheduleMicros);
                        scheduleLatencyStats.registerSuccessfulEvent(scheduleMicros, TimeUnit.MICROSECONDS);
                        f.thenRun(() -> {
                            messagesSent.increment();
                            totalMessagesSent.increment();
                            messagesSentCounter.inc();
                            bytesSent.add(payloadData.length);
                            bytesSentCounter.add(payloadData.length);

                            long latencyMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - sendTime);
                            publishLatencyRecorder.recordValue(latencyMicros);
                            cumulativePublishLatencyRecorder.recordValue(latencyMicros);
                            publishLatencyStats.registerSuccessfulEvent(latencyMicros, TimeUnit.MICROSECONDS);

                            final long sendDelayMicros = TimeUnit.NANOSECONDS.toMicros(sendTime - intendedSendTime);
                            publishDelayLatencyRecorder.recordValue(sendDelayMicros);
                            cumulativePublishDelayLatencyRecorder.recordValue(sendDelayMicros);
                            publishDelayLatencyStats.registerSuccessfulEvent(sendDelayMicros, TimeUnit.MICROSECONDS);
                        }).exceptionally(ex -> {
                            errors.increment();
                            totalErrors.increment();
                            log.warn("Write error on message", ex);
                            return null;
                        });
                    });
                }
            } catch (Throwable t) {
                log.error("Got error", t);
            }
        });
    }

    @Override
    public void adjustPublishRate(double publishRate) {
        if(publishRate < 1.0) {
            rateLimiter = new UniformRateLimiter(1.0);
            return;
        }
        rateLimiter = new UniformRateLimiter(publishRate);
    }

    @Override
    public PeriodStats getPeriodStats() {
        PeriodStats stats = new PeriodStats();

        stats.messagesSent = messagesSent.sumThenReset();
        stats.bytesSent = bytesSent.sumThenReset();
        stats.errors = errors.sumThenReset();
        stats.pollErrors = pollErrors.sumThenReset();

        stats.messagesReceived = messagesReceived.sumThenReset();
        stats.bytesReceived = bytesReceived.sumThenReset();

        stats.totalMessagesSent = totalMessagesSent.sum();
        stats.totalErrors = totalErrors.sum();
        stats.totalMessagesReceived = totalMessagesReceived.sum();

        stats.publishLatency = publishLatencyRecorder.getIntervalHistogram();
        stats.scheduleLatency = scheduleLatencyRecorder.getIntervalHistogram();
        stats.publishDelayLatency = publishDelayLatencyRecorder.getIntervalHistogram();
        stats.endToEndLatency = endToEndLatencyRecorder.getIntervalHistogram();
        return stats;
    }

    @Override
    public CumulativeLatencies getCumulativeLatencies() {
        CumulativeLatencies latencies = new CumulativeLatencies();
        latencies.publishLatency = cumulativePublishLatencyRecorder.getIntervalHistogram();
        latencies.scheduleLatency = cumulativeScheduleLatencyRecorder.getIntervalHistogram();
        latencies.publishDelayLatency = cumulativePublishDelayLatencyRecorder.getIntervalHistogram();
        latencies.endToEndLatency = endToEndCumulativeLatencyRecorder.getIntervalHistogram();
        return latencies;
    }

    @Override
    public CountersStats getCountersStats() throws IOException {
        CountersStats stats = new CountersStats();
        stats.messagesSent = totalMessagesSent.sum();
        stats.messagesReceived = totalMessagesReceived.sum();
        return stats;
    }

    @Override
    public void error() {
        pollErrors.increment();
    }

    @Override
    public void messageReceived(byte[] data, long publishTimestamp) {
        internalMessageReceived(data.length, publishTimestamp);
    }

    @Override
    public void messageReceived(ByteBuffer data, long publishTimestampMillis) {
        internalMessageReceived(data.remaining(), publishTimestampMillis);
    }

    public void internalMessageReceived(int size, long publishTimestampMillis) {
        messagesReceived.increment();
        totalMessagesReceived.increment();
        messagesReceivedCounter.inc();
        bytesReceived.add(size);
        bytesReceivedCounter.add(size);

        // NOTE: PublishTimestamp is expected to be using the wall-clock time across
        // machines in milliseocnds
        Instant currentTime = Instant.now();

        long currentTimeNanos = TimeUnit.SECONDS.toNanos(currentTime.getEpochSecond()) + currentTime.getNano();
        long publishTimeNanos = TimeUnit.MILLISECONDS.toNanos(publishTimestampMillis);
        long endToEndLatencyMicros = TimeUnit.NANOSECONDS.toMicros(currentTimeNanos - publishTimeNanos);
        if (endToEndLatencyMicros > 0) {
            endToEndCumulativeLatencyRecorder.recordValue(endToEndLatencyMicros);
            endToEndLatencyRecorder.recordValue(endToEndLatencyMicros);
            endToEndLatencyStats.registerSuccessfulEvent(endToEndLatencyMicros, TimeUnit.MICROSECONDS);
        }

        while (consumersArePaused) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void messageReceived(int payloadSize, long e2eLatencyNs) {
        if (e2eLatencyNs < 0) {
            error();
            return;
        }

        messagesReceived.increment();
        totalMessagesReceived.increment();
        messagesReceivedCounter.inc();
        bytesReceived.add(payloadSize);
        bytesReceivedCounter.add(payloadSize);


        long endToEndLatencyMicros = TimeUnit.NANOSECONDS.toMicros(e2eLatencyNs);

        endToEndCumulativeLatencyRecorder.recordValue(endToEndLatencyMicros);
        endToEndLatencyRecorder.recordValue(endToEndLatencyMicros);
        endToEndLatencyStats.registerSuccessfulEvent(endToEndLatencyMicros, TimeUnit.MICROSECONDS);

        while (consumersArePaused) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void pauseConsumers() throws IOException {
        consumersArePaused = true;
        log.info("Pausing consumers");
    }

    @Override
    public void resumeConsumers() throws IOException {
        consumersArePaused = false;
        log.info("Resuming consumers");
    }

    @Override
    public void resetStats() throws IOException {
        publishLatencyRecorder.reset();
        scheduleLatencyRecorder.reset();
        cumulativeScheduleLatencyRecorder.reset();
        cumulativePublishLatencyRecorder.reset();
        publishDelayLatencyRecorder.reset();
        cumulativePublishDelayLatencyRecorder.reset();
        endToEndLatencyRecorder.reset();
        endToEndCumulativeLatencyRecorder.reset();
    }

    @Override
    public void stopAll() throws IOException {
        testCompleted = true;
        consumersArePaused = false;

        publishLatencyRecorder.reset();
        scheduleLatencyRecorder.reset();
        cumulativeScheduleLatencyRecorder.reset();
        cumulativePublishLatencyRecorder.reset();
        publishDelayLatencyRecorder.reset();
        cumulativePublishDelayLatencyRecorder.reset();
        endToEndLatencyRecorder.reset();
        endToEndCumulativeLatencyRecorder.reset();

        messagesSent.reset();
        bytesSent.reset();
        messagesReceived.reset();
        bytesReceived.reset();
        totalMessagesSent.reset();
        totalMessagesReceived.reset();

        try {
            Thread.sleep(100);

            for (BenchmarkProducer producer : producers) {
                producer.close();
            }
            producers.clear();

            for (BenchmarkConsumer consumer : consumers) {
                consumer.close();
            }
            consumers.clear();

            if (benchmarkDriver != null) {
                benchmarkDriver.close();
                benchmarkDriver = null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
    }

    private static final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static {
        mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
    }

    private static final Logger log = LoggerFactory.getLogger(LocalWorker.class);
}
