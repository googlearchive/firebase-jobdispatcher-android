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

package com.firebase.jobdispatcher;

import static android.content.Context.BIND_AUTO_CREATE;
import static com.firebase.jobdispatcher.GooglePlayReceiver.getJobCoder;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
// import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.SimpleArrayMap;
import android.util.Log;
import com.firebase.jobdispatcher.JobService.JobResult;

/**
 * ExecutionDelegator tracks local Binder connections to client JobServices and handles
 * communication with those services.
 */
/* package */ class ExecutionDelegator {
  @VisibleForTesting static final int JOB_FINISHED = 1;

  static final String TAG = "FJD.ExternalReceiver";

  interface JobFinishedCallback {
    void onJobFinished(@NonNull JobInvocation jobInvocation, @JobResult int result);
  }

  /** A mapping of {@link JobInvocation} to (local) binder connections. Synchronized by itself. */
  @VisibleForTesting
  // @GuardedBy("serviceConnections")
  static final SimpleArrayMap<JobInvocation, JobServiceConnection> serviceConnections =
      new SimpleArrayMap<>();

  @VisibleForTesting
  static void cleanServiceConnections() {
    synchronized (serviceConnections) {
      serviceConnections.clear();
    }
  }

  private final IJobCallback execCallback =
      new IJobCallback.Stub() {
        @Override
        public void jobFinished(Bundle invocationData, @JobService.JobResult int result) {
          JobInvocation.Builder invocation = getJobCoder().decode(invocationData);
          if (invocation == null) {
            Log.wtf(TAG, "jobFinished: unknown invocation provided");
            return;
          }

          ExecutionDelegator.this.onJobFinishedMessage(invocation.build(), result);
        }
      };

  private final Context context;
  private final JobFinishedCallback jobFinishedCallback;

  ExecutionDelegator(Context context, JobFinishedCallback jobFinishedCallback) {
    this.context = context;
    this.jobFinishedCallback = jobFinishedCallback;
  }

  /**
   * Executes the provided {@code jobInvocation} by kicking off the creation of a new Binder
   * connection to the Service.
   */
  void executeJob(JobInvocation jobInvocation) {
    if (jobInvocation == null) {
      return;
    }

    synchronized (serviceConnections) {
      JobServiceConnection oldConnection = serviceConnections.get(jobInvocation);
      if (oldConnection != null) {
        if (!oldConnection.isConnected() && !oldConnection.wasUnbound()) {
          // Fresh connection. Not yet connected or not able to connect.
          // TODO(user) Handle invalid service when the connection can't be established.
          return;
        }
        oldConnection.onStop(false /* Do not send result because it is new execution request. */);
      }
      JobServiceConnection conn = new JobServiceConnection(jobInvocation, execCallback, context);

      serviceConnections.put(jobInvocation, conn);
      if (!context.bindService(createBindIntent(jobInvocation), conn, BIND_AUTO_CREATE)) {
        Log.e(TAG, "Unable to bind to " + jobInvocation.getService());
        conn.unbind();
      }
    }
  }

  @NonNull
  private Intent createBindIntent(JobParameters jobParameters) {
    Intent execReq = new Intent(JobService.ACTION_EXECUTE);
    execReq.setClassName(context, jobParameters.getService());
    return execReq;
  }

  static void stopJob(JobInvocation job, boolean needToSendResult) {
    synchronized (serviceConnections) {
      JobServiceConnection jobServiceConnection = serviceConnections.remove(job);
      if (jobServiceConnection != null) {
        jobServiceConnection.onStop(needToSendResult);
      }
    }
  }

  private void onJobFinishedMessage(JobInvocation jobInvocation, int result) {
    synchronized (serviceConnections) {
      JobServiceConnection connection = serviceConnections.remove(jobInvocation);
      if (connection != null) {
        connection.unbind();
      }
    }

    jobFinishedCallback.onJobFinished(jobInvocation, result);
  }
}
