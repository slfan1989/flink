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

package org.apache.flink.runtime.util;

import org.apache.flink.api.common.JobID;
import org.apache.flink.runtime.blob.PermanentBlobKey;
import org.apache.flink.runtime.deployment.TaskDeploymentDescriptorFactory.ShuffleDescriptorAndIndex;
import org.apache.flink.runtime.deployment.TaskDeploymentDescriptorFactory.ShuffleDescriptorGroup;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.shuffle.ShuffleDescriptor;
import org.apache.flink.runtime.shuffle.UnknownShuffleDescriptor;

import org.apache.flink.shaded.guava33.com.google.common.base.Ticker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link DefaultGroupCache}. */
class DefaultGroupCacheTest {
    private final Duration expireTimeout = Duration.ofSeconds(10);

    @Test
    void testGetEntry() {
        DefaultGroupCache<JobID, PermanentBlobKey, ShuffleDescriptorGroup> cache =
                new DefaultGroupCache.Factory<JobID, PermanentBlobKey, ShuffleDescriptorGroup>(
                                expireTimeout, Integer.MAX_VALUE, Ticker.systemTicker())
                        .create();

        JobID jobId = new JobID();
        ShuffleDescriptor shuffleDescriptor = new UnknownShuffleDescriptor(new ResultPartitionID());
        ShuffleDescriptorGroup shuffleDescriptorGroup =
                new ShuffleDescriptorGroup(
                        new ShuffleDescriptorAndIndex[] {
                            new ShuffleDescriptorAndIndex(shuffleDescriptor, 0)
                        });

        PermanentBlobKey blobKey = new PermanentBlobKey();

        assertThat(cache.get(jobId, blobKey)).isNull();

        cache.put(jobId, blobKey, shuffleDescriptorGroup);
        assertThat(cache.get(jobId, blobKey)).isEqualTo(shuffleDescriptorGroup);
    }

    @Test
    void testClearCacheForJob() {
        DefaultGroupCache<JobID, PermanentBlobKey, ShuffleDescriptorGroup> cache =
                new DefaultGroupCache.Factory<JobID, PermanentBlobKey, ShuffleDescriptorGroup>(
                                expireTimeout, Integer.MAX_VALUE, Ticker.systemTicker())
                        .create();

        JobID jobId = new JobID();
        ShuffleDescriptor shuffleDescriptor = new UnknownShuffleDescriptor(new ResultPartitionID());
        ShuffleDescriptorGroup shuffleDescriptorGroup =
                new ShuffleDescriptorGroup(
                        new ShuffleDescriptorAndIndex[] {
                            new ShuffleDescriptorAndIndex(shuffleDescriptor, 0)
                        });
        PermanentBlobKey blobKey = new PermanentBlobKey();

        assertThat(cache.get(jobId, blobKey)).isNull();

        cache.put(jobId, blobKey, shuffleDescriptorGroup);
        assertThat(cache.get(jobId, blobKey)).isEqualTo(shuffleDescriptorGroup);

        cache.clearCacheForGroup(jobId);
        assertThat(cache.get(jobId, blobKey)).isNull();
    }

    @Test
    void testPutWhenOverLimit() {
        DefaultGroupCache<JobID, PermanentBlobKey, ShuffleDescriptorGroup> cache =
                new DefaultGroupCache.Factory<JobID, PermanentBlobKey, ShuffleDescriptorGroup>(
                                expireTimeout, 1, Ticker.systemTicker())
                        .create();

        JobID jobId = new JobID();

        ShuffleDescriptor shuffleDescriptor = new UnknownShuffleDescriptor(new ResultPartitionID());
        ShuffleDescriptorGroup shuffleDescriptorGroup =
                new ShuffleDescriptorGroup(
                        new ShuffleDescriptorAndIndex[] {
                            new ShuffleDescriptorAndIndex(shuffleDescriptor, 0)
                        });
        PermanentBlobKey blobKey = new PermanentBlobKey();

        cache.put(jobId, blobKey, shuffleDescriptorGroup);
        assertThat(cache.get(jobId, blobKey)).isEqualTo(shuffleDescriptorGroup);

        ShuffleDescriptorGroup otherShuffleDescriptorGroup =
                new ShuffleDescriptorGroup(
                        new ShuffleDescriptorAndIndex[] {
                            new ShuffleDescriptorAndIndex(
                                    new UnknownShuffleDescriptor(new ResultPartitionID()), 0)
                        });
        PermanentBlobKey otherBlobKey = new PermanentBlobKey();

        cache.put(jobId, otherBlobKey, otherShuffleDescriptorGroup);
        assertThat(cache.get(jobId, blobKey)).isNull();
        assertThat(cache.get(jobId, otherBlobKey)).isEqualTo(otherShuffleDescriptorGroup);
    }

    @Test
    void testEntryExpired() {
        TestingTicker ticker = new TestingTicker();
        DefaultGroupCache<JobID, PermanentBlobKey, ShuffleDescriptorGroup> cache =
                new DefaultGroupCache.Factory<JobID, PermanentBlobKey, ShuffleDescriptorGroup>(
                                Duration.ofSeconds(1), Integer.MAX_VALUE, ticker)
                        .create();

        JobID jobId = new JobID();

        ShuffleDescriptor shuffleDescriptor = new UnknownShuffleDescriptor(new ResultPartitionID());
        ShuffleDescriptorGroup shuffleDescriptorGroup =
                new ShuffleDescriptorGroup(
                        new ShuffleDescriptorAndIndex[] {
                            new ShuffleDescriptorAndIndex(shuffleDescriptor, 0)
                        });
        PermanentBlobKey blobKey = new PermanentBlobKey();

        cache.put(jobId, blobKey, shuffleDescriptorGroup);
        assertThat(cache.get(jobId, blobKey)).isEqualTo(shuffleDescriptorGroup);

        ticker.advance(Duration.ofSeconds(2));
        assertThat(cache.get(jobId, blobKey)).isNull();
    }

    private static class TestingTicker extends Ticker {
        private final AtomicLong nanos = new AtomicLong();

        public void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }
}
