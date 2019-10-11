/*
 * Copyright (c) 2019 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"): you may not use this file except in compliance
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

package com.spotify.heroic.querylogging

import java.util.*

data class HttpContext(
    /**
     * The remote address that connected to this node.
     */
    val remoteAddress: String,
    /**
     * The remote host (usually same as address) that connected to this node.
     */
    val remoteHost: String,
    /**
     * The client address that performed the request.
     * Most notably, if X-Forwarded-For is set, this will be the value of it.
     * Otherwise it is the same as {@link #remoteAddress}.
     */
    val clientAddress: String,
    /**
     * The user agent of the client that performed the request.
     */
    val userAgent: Optional<String>,
    /**
     * The id of the client that performed the request.
     */
    val clientId: Optional<String>
)
