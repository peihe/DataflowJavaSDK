/*******************************************************************************
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/

package com.google.cloud.dataflow.sdk.runners.worker;

import static com.google.cloud.dataflow.sdk.util.Structs.getInt;
import static com.google.cloud.dataflow.sdk.util.Structs.getStrings;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.CloudObject;
import com.google.cloud.dataflow.sdk.util.ExecutionContext;
import com.google.cloud.dataflow.sdk.util.PropertyNames;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.worker.NativeReader;

import java.util.Collections;

import javax.annotation.Nullable;

/**
 * Creates an InMemoryReader from a CloudObject spec.
 */
public class InMemoryReaderFactory implements ReaderFactory {

  @Override
  public NativeReader<?> create(
      CloudObject spec,
      @Nullable Coder<?> coder,
      @Nullable PipelineOptions options,
      @Nullable ExecutionContext executionContext,
      @Nullable CounterSet.AddCounterMutator addCounterMutator,
      @Nullable String operationName)
          throws Exception {
    return create(spec, coder);
  }

  <T> InMemoryReader<T> create(CloudObject spec, Coder<T> coder) throws Exception {
    return new InMemoryReader<>(
        getStrings(spec, PropertyNames.ELEMENTS, Collections.<String>emptyList()),
        getInt(spec, PropertyNames.START_INDEX, null),
        getInt(spec, PropertyNames.END_INDEX, null),
        coder);
  }
}
