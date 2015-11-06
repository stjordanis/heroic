/*
 * Copyright (c) 2015 Spotify AB.
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

package com.spotify.heroic.http.metadata;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spotify.heroic.common.DateRange;
import com.spotify.heroic.filter.Filter;
import com.spotify.heroic.filter.impl.TrueFilterImpl;
import com.spotify.heroic.http.query.QueryDateRange;

import lombok.Data;

@Data
public class MetadataTagKeySuggest {
    private static final Filter DEFAULT_FILTER = TrueFilterImpl.get();
    private static final int DEFAULT_LIMIT = 10;

    private final Filter filter;
    private final int limit;
    private final Optional<DateRange> range;

    @JsonCreator
    public MetadataTagKeySuggest(@JsonProperty("filter") Filter filter,
            @JsonProperty("limit") Integer limit, @JsonProperty("range") QueryDateRange range) {
        this.filter = Optional.ofNullable(filter).orElse(DEFAULT_FILTER);
        this.limit = Optional.ofNullable(limit).orElse(DEFAULT_LIMIT);
        this.range = Optional.ofNullable(range).flatMap(QueryDateRange::buildDateRange);
    }

    public static MetadataTagKeySuggest createDefault() {
        return new MetadataTagKeySuggest(null, null, null);
    }
}