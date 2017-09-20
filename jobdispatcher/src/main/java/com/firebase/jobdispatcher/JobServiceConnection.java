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

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

/** ServiceConnection for job execution. */
@VisibleForTesting
class JobServiceConnection implements ServiceConnection {

  private final JobInvocation jobInvocation;
  // Should be sent only once. Can't be reused.
  private final Message jobFinishedMessage;
  private boolean wasMessageUsed = false;
  private final Context context;

  // Guarded by "this". Can be updated from main and binder threads.
  private JobService.LocalBinder binder;

  JobServiceConnection(JobInvocation jobInvocation, Message jobFinishedMessage, Context context) {
    this.jobFinishedMessage = jobFinishedMessage;
    this.jobInvocation = jobInvocation;
    this.jobFinishedMessage.obj = this.jobInvocation;
    this.context = context;
  }

  @Override
  public synchronized void onServiceConnected(ComponentName name, IBinder service) {
    if (!(service instanceof JobService.LocalBinder)) {
      Log.w(TAG, "Unknown service connected");
      return;
    }
    if (wasMessageUsed) {
      Log.w(TAG, "onServiceConnected Duplicate calls. Ignored.");
      return;
    } else {
      wasMessageUsed = true;
    }

    binder = (JobService.LocalBinder) service;

    JobService jobService = binder.getService();

    jobService.start(jobInvocation, jobFinishedMessage);
  }

  @Override
  public synchronized void onServiceDisconnected(ComponentName name) {
    binder = null;
  }

  @VisibleForTesting
  synchronized boolean isBound() {
    return binder != null;
  }

  synchronized void onStop(boolean needToSendResult) {
    if (isBound()) {
      binder.getService().stop(jobInvocation, needToSendResult);
      unbind();
    }
  }

  synchronized void unbind() {
    if (isBound()) {
      binder = null;
      try {
        context.unbindService(this);
      } catch (IllegalArgumentException e) {
        Log.w(TAG, "Error unbinding service: " + e.getMessage());
      }
    }
  }
}
