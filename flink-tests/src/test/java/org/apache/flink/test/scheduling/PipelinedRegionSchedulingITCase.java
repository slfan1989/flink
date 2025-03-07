/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.scheduling;

import org.apache.flink.api.common.JobID;
import org.apache.flink.client.program.MiniClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.io.network.api.reader.RecordReader;
import org.apache.flink.runtime.io.network.api.writer.RecordWriter;
import org.apache.flink.runtime.io.network.api.writer.RecordWriterBuilder;
import org.apache.flink.runtime.io.network.partition.PartitionException;
import org.apache.flink.runtime.io.network.partition.PartitionNotFoundException;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobGraphTestUtils;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.jobmanager.scheduler.NoResourceAvailableException;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.jobmaster.JobResult;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.runtime.testtasks.NoOpInvokable;
import org.apache.flink.types.IntValue;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.TestLogger;

import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.apache.flink.configuration.RestartStrategyOptions.RestartStrategyType.FIXED_DELAY;
import static org.apache.flink.runtime.util.JobVertexConnectionUtils.connectNewDataSetAsInput;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/** IT case for pipelined region scheduling. */
public class PipelinedRegionSchedulingITCase extends TestLogger {

    @Test
    public void testSuccessWithSlotsNoFewerThanTheMaxRegionRequired() throws Exception {
        final JobResult jobResult = executeSchedulingTest(2);
        assertThat(jobResult.getSerializedThrowable().isPresent(), is(false));
    }

    @Test
    public void testFailsOnInsufficientSlots() throws Exception {
        final JobResult jobResult = executeSchedulingTest(1);
        assertThat(jobResult.getSerializedThrowable().isPresent(), is(true));

        final Throwable jobFailure =
                jobResult
                        .getSerializedThrowable()
                        .get()
                        .deserializeError(ClassLoader.getSystemClassLoader());

        final Optional<NoResourceAvailableException> cause =
                ExceptionUtils.findThrowable(jobFailure, NoResourceAvailableException.class);
        assertThat(cause.isPresent(), is(true));
        assertThat(
                cause.get().getMessage(), containsString("Slot request bulk is not fulfillable!"));
    }

    @Test(timeout = 120000)
    public void testRecoverFromPartitionException() throws Exception {
        final Configuration configuration = new Configuration();
        configuration.set(RestartStrategyOptions.RESTART_STRATEGY, FIXED_DELAY.getMainValue());
        configuration.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 1);

        OneTimeFailingReceiverWithPartitionException.hasFailed.set(false);

        final JobResult jobResult =
                executeSchedulingTest(createJobGraphWithThreeStages(2), 2, configuration);
        assertThat(jobResult.getSerializedThrowable().isPresent(), is(false));
    }

    private JobResult executeSchedulingTest(int numSlots) throws Exception {
        return executeSchedulingTest(createJobGraph(2), numSlots, new Configuration());
    }

    private JobResult executeSchedulingTest(
            JobGraph jobGraph, int numSlots, Configuration configuration) throws Exception {
        configuration.set(JobManagerOptions.SLOT_REQUEST_TIMEOUT, Duration.ofMillis(30000L));
        configuration.set(JobManagerOptions.SCHEDULER, JobManagerOptions.SchedulerType.Default);

        final MiniClusterConfiguration miniClusterConfiguration =
                new MiniClusterConfiguration.Builder()
                        .withRandomPorts()
                        .setConfiguration(configuration)
                        .setNumTaskManagers(1)
                        .setNumSlotsPerTaskManager(numSlots)
                        .build();

        try (MiniCluster miniCluster = new MiniCluster(miniClusterConfiguration)) {
            miniCluster.start();

            final MiniClusterClient miniClusterClient =
                    new MiniClusterClient(configuration, miniCluster);

            // wait for the submission to succeed
            final JobID jobID = miniClusterClient.submitJob(jobGraph).get();

            final CompletableFuture<JobResult> resultFuture =
                    miniClusterClient.requestJobResult(jobID);

            final JobResult jobResult = resultFuture.get();

            return jobResult;
        }
    }

    private JobGraph createJobGraph(final int parallelism) {
        final SlotSharingGroup group1 = new SlotSharingGroup();
        final JobVertex source1 = new JobVertex("source1");
        source1.setInvokableClass(PipelinedSender.class);
        source1.setParallelism(parallelism * 2);
        source1.setSlotSharingGroup(group1);

        final SlotSharingGroup group2 = new SlotSharingGroup();
        final JobVertex source2 = new JobVertex("source2");
        source2.setInvokableClass(NoOpInvokable.class);
        source2.setParallelism(parallelism);
        source2.setSlotSharingGroup(group2);

        final JobVertex sink = new JobVertex("sink");
        sink.setInvokableClass(Receiver.class);
        sink.setParallelism(parallelism);
        sink.setSlotSharingGroup(group1);

        connectNewDataSetAsInput(
                sink, source1, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);
        connectNewDataSetAsInput(
                sink, source2, DistributionPattern.ALL_TO_ALL, ResultPartitionType.BLOCKING);

        return JobGraphTestUtils.batchJobGraph(source1, source2, sink);
    }

    private JobGraph createJobGraphWithThreeStages(final int parallelism) {
        final SlotSharingGroup group1 = new SlotSharingGroup();
        final JobVertex source = new JobVertex("source");
        source.setInvokableClass(NoOpInvokable.class);
        source.setParallelism(parallelism);
        source.setSlotSharingGroup(group1);

        final SlotSharingGroup group2 = new SlotSharingGroup();
        final JobVertex map = new JobVertex("map");
        map.setInvokableClass(NoOpInvokable.class);
        map.setParallelism(parallelism);
        map.setSlotSharingGroup(group2);

        final SlotSharingGroup group3 = new SlotSharingGroup();
        final JobVertex sink = new JobVertex("sink");
        sink.setInvokableClass(OneTimeFailingReceiverWithPartitionException.class);
        sink.setParallelism(parallelism);
        sink.setSlotSharingGroup(group3);

        connectNewDataSetAsInput(
                map, source, DistributionPattern.POINTWISE, ResultPartitionType.BLOCKING);
        connectNewDataSetAsInput(
                sink, map, DistributionPattern.ALL_TO_ALL, ResultPartitionType.BLOCKING);

        return JobGraphTestUtils.batchJobGraph(source, map, sink);
    }

    /**
     * This invokable is used by source1. It sends data to trigger the scheduling of the sink task.
     * It will also wait for a bit time before finishing itself, so that the scheduled sink task can
     * directly use its slot.
     */
    public static class PipelinedSender extends AbstractInvokable {

        public PipelinedSender(Environment environment) {
            super(environment);
        }

        @Override
        public void invoke() throws Exception {
            if (getEnvironment().getAllWriters().length < 1) {
                throw new IllegalStateException();
            }

            final RecordWriter<IntValue> writer =
                    new RecordWriterBuilder<IntValue>().build(getEnvironment().getWriter(0));

            try {
                writer.emit(new IntValue(42));
                writer.flushAll();
            } finally {
                writer.close();
            }

            if (getIndexInSubtaskGroup() == 0) {
                Thread.sleep(2000);
            }
        }
    }

    /**
     * This invokable finishes only after all its upstream task finishes. Unexpected result
     * partition errors can happen if a task finished later than its consumer task.
     */
    public static class Receiver extends AbstractInvokable {

        public Receiver(Environment environment) {
            super(environment);
        }

        @Override
        public void invoke() throws Exception {
            if (getEnvironment().getAllInputGates().length < 2) {
                throw new IllegalStateException();
            }

            final String[] tmpDirs = getEnvironment().getTaskManagerInfo().getTmpDirectories();
            final List<RecordReader<IntValue>> readers =
                    Arrays.asList(getEnvironment().getAllInputGates()).stream()
                            .map(
                                    inputGate ->
                                            new RecordReader<>(inputGate, IntValue.class, tmpDirs))
                            .collect(Collectors.toList());

            for (RecordReader<IntValue> reader : readers) {
                while (reader.hasNext()) {
                    reader.next();
                }
            }
        }
    }

    /** Invokable which fails exactly once with a {@link PartitionException}. */
    public static class OneTimeFailingReceiverWithPartitionException extends AbstractInvokable {

        private static final AtomicBoolean hasFailed = new AtomicBoolean(false);

        public OneTimeFailingReceiverWithPartitionException(Environment environment) {
            super(environment);
        }

        @Override
        public void invoke() throws Exception {
            if (hasFailed.compareAndSet(false, true)) {
                throw new PartitionNotFoundException(
                        getEnvironment().getInputGate(0).getChannel(1).getPartitionId());
            }
        }
    }
}
