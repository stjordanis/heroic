/*
 * Copyright (c) 2020 Spotify AB.
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

package com.spotify.heroic.elasticsearch;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResourceLoader {
    private static final Logger log = LoggerFactory.getLogger(ResourceLoader.class);

    public static Map<String, Object> loadJson(Class clazz, String path) {
        final String fullPath = clazz.getPackage().getName() + "/" + path;

        try (final InputStream input = clazz
            .getClassLoader()
            .getResourceAsStream(fullPath)
        ) {
            if (input == null) {
                throw new IllegalArgumentException("No such resource: " + fullPath);
            }

            return JsonXContent.jsonXContent
                .createParser(
                    NamedXContentRegistry.EMPTY,
                    new DeprecationHandler() {
                        @Override
                        public void usedDeprecatedName(String usedName, String modernName) {
                            log.warn("Elasticsearch deprecated: {} {}", usedName, modernName);
                        }

                        @Override
                        public void usedDeprecatedField(String usedName, String replacedWith) {
                            log.warn("Elasticsearch deprecated: {} {}", usedName, replacedWith);
                        }
                    },
                    input)
                .map();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }
}

