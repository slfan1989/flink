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

package org.apache.flink.api.common.io;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.types.IntValue;
import org.apache.flink.types.Record;
import org.apache.flink.types.StringValue;

import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for serialized formats. */
class SerializedFormatTest extends SequentialFormatTestBase<Record> {

    private BlockInfo info;

    @BeforeEach
    void setup() {
        info = createInputFormat().createBlockInfo();
    }

    @Override
    protected BinaryInputFormat<Record> createInputFormat() {
        Configuration configuration = new Configuration();

        final SerializedInputFormat<Record> inputFormat = new SerializedInputFormat<Record>();
        inputFormat.setFilePath(this.tempFile.toURI().toString());

        inputFormat.configure(configuration);
        return inputFormat;
    }

    @Override
    protected BinaryOutputFormat<Record> createOutputFormat(
            String path, Configuration configuration) throws IOException {
        final SerializedOutputFormat<Record> outputFormat = new SerializedOutputFormat<Record>();
        outputFormat.setOutputFilePath(new Path(path));
        outputFormat.setWriteMode(FileSystem.WriteMode.OVERWRITE);

        configuration = configuration == null ? new Configuration() : configuration;
        outputFormat.configure(configuration);
        outputFormat.open(FirstAttemptInitializationContext.of(0, 1));
        return outputFormat;
    }

    @Override
    protected int getInfoSize() {
        return info.getInfoSize();
    }

    @Override
    protected Record getRecord(int index) {
        return new Record(new IntValue(index), new StringValue(String.valueOf(index)));
    }

    @Override
    protected Record createInstance() {
        return new Record();
    }

    @Override
    protected void writeRecord(Record record, DataOutputView outputView) throws IOException {
        record.write(outputView);
    }

    @Override
    protected void checkEquals(Record expected, Record actual) {
        assertThat(actual.getNumFields()).isEqualTo(expected.getNumFields());
        assertThat(actual.getField(0, IntValue.class))
                .isEqualTo(expected.getField(0, IntValue.class));
        assertThat((CharSequence) actual.getField(1, StringValue.class))
                .isEqualTo(expected.getField(1, StringValue.class));
    }
}
