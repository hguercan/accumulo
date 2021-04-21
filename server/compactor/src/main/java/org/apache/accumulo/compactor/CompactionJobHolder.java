/*
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
package org.apache.accumulo.compactor;

import java.util.Objects;

import org.apache.accumulo.core.tabletserver.thrift.TCompactionStats;
import org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob;

public class CompactionJobHolder {

  private TExternalCompactionJob job;
  private Thread compactionThread;
  private volatile Boolean cancelled = Boolean.FALSE;
  private TCompactionStats stats = null;

  CompactionJobHolder() {}

  public void reset() {
    job = null;
    compactionThread = null;
    cancelled = Boolean.FALSE;
    stats = null;
  }

  public TExternalCompactionJob getJob() {
    return job;
  }

  public Thread getThread() {
    return compactionThread;
  }

  public TCompactionStats getStats() {
    return stats;
  }

  public void setStats(TCompactionStats stats) {
    this.stats = stats;
  }

  public void cancel() {
    cancelled = Boolean.TRUE;
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public boolean isSet() {
    return (null != this.job);
  }

  public void set(TExternalCompactionJob job, Thread compactionThread) {
    Objects.requireNonNull(job, "CompactionJob is null");
    Objects.requireNonNull(compactionThread, "Compaction thread is null");
    this.job = job;
    this.compactionThread = compactionThread;
  }

}
