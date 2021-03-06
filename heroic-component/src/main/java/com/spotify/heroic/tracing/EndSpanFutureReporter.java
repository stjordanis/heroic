/*
 * Copyright (c) 2019 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.tracing;

import static io.opencensus.trace.AttributeValue.booleanAttributeValue;

import com.spotify.heroic.statistics.FutureReporter;
import io.opencensus.trace.Span;
import io.opencensus.trace.Status;
import java.util.Optional;

public class EndSpanFutureReporter implements FutureReporter.Context {
  private final Span span;

  public EndSpanFutureReporter(final Span span) {
    this.span = span;
  }

  @Override
  public void failed(final Throwable cause) throws Exception {
    span.putAttribute("error", booleanAttributeValue(true));
    Optional.ofNullable(cause.getMessage()).ifPresent(span::addAnnotation);
    span.setStatus(Status.INTERNAL);
    span.end();
  }

  @Override
  public void resolved(final Object result) throws Exception {
    span.end();
  }

  @Override
  public void cancelled() throws Exception {
    span.putAttribute("error", booleanAttributeValue(true));
    span.setStatus(Status.CANCELLED);
    span.end();
  }
}
