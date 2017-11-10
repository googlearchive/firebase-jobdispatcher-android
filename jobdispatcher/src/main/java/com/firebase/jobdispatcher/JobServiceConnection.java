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

import static com.firebase.jobdispatcher.ExecutionDelegator.TAG;
import static com.firebase.jobdispatcher.GooglePlayReceiver.getJobCoder;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
// import android.support.annotation.GuardedBy;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/** ServiceConnection for job execution. */
@VisibleForTesting
class JobServiceConnection implements ServiceConnection {

  /** A map of {@link JobInvocation job} to running state. */
  // @GuardedBy("this")
  private final Map<JobInvocation, Boolean> jobStatuses = new HashMap<>();

  private final IJobCallback callback;
  private final Context context;

  // @GuardedBy("this")
  private boolean wasUnbound = false;

  // @GuardedBy("this")
  private IRemoteJobService binder;

  JobServiceConnection(IJobCallback callback, Context context) {
    this.callback = callback;
    this.context = context;
  }

  @Override
  public synchronized void onServiceConnected(ComponentName name, IBinder service) {
    if (wasUnbound()) {
      Log.w(TAG, "Connection have been used already.");
      return;
    }

    binder = IRemoteJobService.Stub.asInterface(service);
    Set<JobInvocation> startedJobs = new HashSet<>();
    for (Entry<JobInvocation, Boolean> entry : jobStatuses.entrySet()) {
      if (Boolean.FALSE.equals(entry.getValue())) {
        try {
          binder.start(encodeJob(entry.getKey()), callback);
          startedJobs.add(entry.getKey());
        } catch (RemoteException remoteException) {
          Log.e(TAG, "Failed to start job " + entry.getKey(), remoteException);
          unbind();
          // TODO(user) notify a driver about the fail and release a wakelock.
          return;
        }
      }
    }
    // Mark jobs as started.
    for (JobInvocation invocation : startedJobs) {
      jobStatuses.put(invocation, true);
    }
  }

  @Override
  public synchronized void onServiceDisconnected(ComponentName name) {
    unbind();
  }

  synchronized boolean wasUnbound() {
    return wasUnbound;
  }

  synchronized boolean isConnected() {
    return binder != null;
  }

  /**
   * Stops provided {@link JobInvocation job}.
   *
   * <p>Unbinds the service if {@code needToSendResult} is {@code false} and no other jobs are
   * running.
   */
  synchronized void onStop(JobInvocation jobInvocation, boolean needToSendResult) {
    if (!wasUnbound()) {
      boolean isRunning = Boolean.TRUE.equals(jobStatuses.remove(jobInvocation));
      if (isRunning && isConnected()) {
        stopJob(needToSendResult, jobInvocation);
      }
      // Need to keep the connection open to receive the result.
      if (!needToSendResult && jobStatuses.isEmpty()) {
        unbind();
      }
    } else {
      Log.w(TAG, "Can't send stop request because service was unbound.");
    }
  }

  private synchronized void stopJob(boolean needToSendResult, JobInvocation jobInvocation) {
    try {
      binder.stop(encodeJob(jobInvocation), needToSendResult);
    } catch (RemoteException remoteException) {
      Log.e(TAG, "Failed to stop a job", remoteException);
      unbind();
    }
  }

  synchronized void unbind() {
    if (!wasUnbound()) {
      binder = null;
      wasUnbound = true;
      try {
        context.unbindService(this);
      } catch (IllegalArgumentException e) {
        Log.w(TAG, "Error unbinding service: " + e.getMessage());
      }
    }
  }

  /** Removes provided {@link JobInvocation job} and unbinds itself if no other jobs are running. */
  synchronized void onJobFinished(JobInvocation jobInvocation) {
    jobStatuses.remove(jobInvocation);
    if (jobStatuses.isEmpty()) {
      unbind();
    }
  }

  /** Returns {@code true} if the job was started. */
  synchronized boolean startJob(JobInvocation jobInvocation) {
    boolean connected = isConnected();
    if (connected) {
      // Need to stop running job
      Boolean isRunning = jobStatuses.get(jobInvocation);
      if (Boolean.TRUE.equals(isRunning)) {
        Log.w(TAG, "Received an execution request for already running job " + jobInvocation);
        stopJob(/* Do not send result because it is new execution request. */ false, jobInvocation);
      }
      try {
        binder.start(encodeJob(jobInvocation), callback);
      } catch (RemoteException e) {
        Log.e(TAG, "Failed to start the job " + jobInvocation, e);
        unbind();
        return false;
      }
    }
    jobStatuses.put(jobInvocation, connected);
    return connected;
  }

  private static Bundle encodeJob(JobParameters job) {
    return getJobCoder().encode(job, new Bundle());
  }

  @VisibleForTesting
  synchronized boolean hasJobInvocation(JobInvocation jobInvocation) {
    return jobStatuses.containsKey(jobInvocation);
  }
}
