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

  static final String TAG = "FJD.ExternalReceiver";

  interface JobFinishedCallback {
    void onJobFinished(@NonNull JobInvocation jobInvocation, @JobResult int result);
  }

  /** A mapping of service name to binder connections. */
  // @GuardedBy("serviceConnections")
  private static final SimpleArrayMap<String, JobServiceConnection> serviceConnections =
      new SimpleArrayMap<>();

  @VisibleForTesting
  static JobServiceConnection getJobServiceConnection(String serviceName) {
    synchronized (serviceConnections) {
      return serviceConnections.get(serviceName);
    }
  }

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
      JobServiceConnection jobServiceConnection =
          serviceConnections.get(jobInvocation.getService());
      if (jobServiceConnection != null && !jobServiceConnection.wasUnbound()) {
        if (jobServiceConnection.hasJobInvocation(jobInvocation)
            && !jobServiceConnection.isConnected()) {
          // Fresh connection. Not yet connected or not able to connect.
          // TODO(user) Handle invalid service when the connection can't be established.
          return;
        }
      } else {
        jobServiceConnection = new JobServiceConnection(execCallback, context);
        serviceConnections.put(jobInvocation.getService(), jobServiceConnection);
      }
      boolean wasConnected = jobServiceConnection.startJob(jobInvocation);
      if (!wasConnected
          && !context.bindService(
              createBindIntent(jobInvocation), jobServiceConnection, BIND_AUTO_CREATE)) {
        Log.e(TAG, "Unable to bind to " + jobInvocation.getService());
        jobServiceConnection.unbind();
      }
    }
  }

  @NonNull
  private Intent createBindIntent(JobParameters jobParameters) {
    Intent execReq = new Intent(JobService.ACTION_EXECUTE);
    execReq.setClassName(context, jobParameters.getService());
    return execReq;
  }

  /** Stops provided {@link JobInvocation job}. */
  static void stopJob(JobInvocation job, boolean needToSendResult) {
    synchronized (serviceConnections) {
      JobServiceConnection jobServiceConnection = serviceConnections.get(job.getService());
      if (jobServiceConnection != null) {
        jobServiceConnection.onStop(job, needToSendResult);
        if (jobServiceConnection.wasUnbound()) {
          serviceConnections.remove(job.getService());
        }
      }
    }
  }

  private void onJobFinishedMessage(JobInvocation jobInvocation, int result) {
    // Need to release unused connection if it was not release previously.
    synchronized (serviceConnections) {
      JobServiceConnection jobServiceConnection =
          serviceConnections.get(jobInvocation.getService());
      if (jobServiceConnection != null) {
        jobServiceConnection.onJobFinished(jobInvocation);
        if (jobServiceConnection.wasUnbound()) {
          serviceConnections.remove(jobInvocation.getService());
        }
      }
    }

    jobFinishedCallback.onJobFinished(jobInvocation, result);
  }
}
