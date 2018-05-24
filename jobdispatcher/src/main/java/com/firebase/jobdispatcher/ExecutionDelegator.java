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
  private final ConstraintChecker constraintChecker;

  ExecutionDelegator(
      Context context,
      JobFinishedCallback jobFinishedCallback,
      ConstraintChecker constraintChecker) {
    this.context = context;
    this.jobFinishedCallback = jobFinishedCallback;
    this.constraintChecker = constraintChecker;
  }

  /**
   * Executes the provided {@code jobInvocation} by kicking off the creation of a new Binder
   * connection to the Service.
   *
   * <p>Note job is not executed if its constraints are still unsatisfied. E.g. disconnected network
   * connection for network-constrained jobs. In this case, job is failed but set to retry if
   * eligible using the {@code JobService.RESULT_FAIL_RETRY} code.
   */
  void executeJob(JobInvocation jobInvocation) {
    if (jobInvocation == null) {
      return;
    }

    // Check job constraints satisfied prior to starting job.
    if (!constraintChecker.areConstraintsSatisfied(jobInvocation)) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "Not executing job because constraints still unmet. Job: " + jobInvocation);
      }
      jobFinishedCallback.onJobFinished(jobInvocation, JobService.RESULT_FAIL_RETRY);
      return;
    }
    if (Log.isLoggable(TAG, Log.DEBUG)) {
      Log.d(TAG, "Proceeding to execute job because constraints met. Job: " + jobInvocation);
    }

    synchronized (serviceConnections) {
      JobServiceConnection jobServiceConnection =
          serviceConnections.get(jobInvocation.getService());

      if (jobServiceConnection != null) {
        // We already have an open connection, so reuse that. The connection will handle both
        // duplicate execution requests and binder failures.
        jobServiceConnection.startJob(jobInvocation);
        return;
      }

      // No pre-existing connection, create a new one
      jobServiceConnection = new JobServiceConnection(execCallback, context);
      serviceConnections.put(jobInvocation.getService(), jobServiceConnection);
      // Queue the job
      jobServiceConnection.startJob(jobInvocation);
      // And kick off the bind
      boolean successfullyBound = tryBindingToJobService(jobInvocation, jobServiceConnection);

      if (successfullyBound) {
        // TODO(user): add a timeout to ensure the bind completes within ~18s or so
      } else {
        // TODO(user): we should track the number of times this happens and drop the job if
        //                     it happens too often.
        Log.e(TAG, "Unable to bind to " + jobInvocation.getService());
        jobServiceConnection.unbind();
      }
    }
  }

  /**
   * Attempts to bind to the JobService associated with the provided {@code jobInvocation}.
   *
   * <p>Returns a boolean indicating whether the bind attempt succeeded.
   */
  private boolean tryBindingToJobService(JobInvocation job, JobServiceConnection connection) {
    Intent bindIntent =
        new Intent(JobService.ACTION_EXECUTE).setClassName(context, job.getService());

    try {
      return context.bindService(bindIntent, connection, BIND_AUTO_CREATE);
    } catch (SecurityException e) {
      // It's not clear what would cause a SecurityException when binding to the same app, but
      // some change made in the N timeframe caused this to start happening.
      Log.e(TAG, "Failed to bind to " + job.getService() + ": " + e);
      return false;
    }
  }

  /** Stops provided {@link JobInvocation job}. */
  static void stopJob(JobInvocation job, boolean needToSendResult) {
    JobServiceConnection jobServiceConnection;
    synchronized (serviceConnections) {
      jobServiceConnection = serviceConnections.get(job.getService());
    }
    if (jobServiceConnection != null) {
      jobServiceConnection.onStop(job, needToSendResult);
      if (jobServiceConnection.wasUnbound()) {
        synchronized (serviceConnections) {
          serviceConnections.remove(job.getService());
        }
      }
    }
  }

  private void onJobFinishedMessage(JobInvocation jobInvocation, int result) {
    // Need to release unused connection if it was not release previously.
    JobServiceConnection jobServiceConnection;
    synchronized (serviceConnections) {
      jobServiceConnection = serviceConnections.get(jobInvocation.getService());
    }
    if (jobServiceConnection != null) {
      jobServiceConnection.onJobFinished(jobInvocation);
      if (jobServiceConnection.wasUnbound()) {
        synchronized (serviceConnections) {
          serviceConnections.remove(jobInvocation.getService());
        }
      }
    }
    jobFinishedCallback.onJobFinished(jobInvocation, result);
  }
}
