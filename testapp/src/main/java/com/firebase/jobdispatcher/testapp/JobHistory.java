// Copyright 2016 Google, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.firebase.jobdispatcher.testapp;

import android.os.SystemClock;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService.JobResult;
import java.util.LinkedList;
import java.util.List;

/** JobHistory tracks the history of a Job. */
public final class JobHistory {
  public final JobParameters job;
  private List<Result> results = new LinkedList<>();

  public JobHistory(JobParameters job) {
    this.job = job;
  }

  public void recordResult(@JobResult int result) {
    results.add(new Result(result, SystemClock.elapsedRealtime()));
  }

  /** Represents a job result. */
  public static final class Result {
    public final int result;
    public final long elapsedTime;

    public Result(int result, long elapsedTime) {
      this.result = result;
      this.elapsedTime = elapsedTime;
    }
  }
}
