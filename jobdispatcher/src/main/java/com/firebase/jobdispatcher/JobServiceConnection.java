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

/** ServiceConnection for job execution. */
@VisibleForTesting
class JobServiceConnection implements ServiceConnection {

  private final Bundle invocationData;
  private final IJobCallback callback;
  private final Context context;

  // @GuardedBy("this")
  private boolean wasUnbound = false;

  // @GuardedBy("this")
  private IRemoteJobService binder;

  JobServiceConnection(JobInvocation jobInvocation, IJobCallback callback, Context context) {
    this.invocationData = getJobCoder().encode(jobInvocation, new Bundle());
    this.callback = callback;
    this.context = context;
  }

  @Override
  public synchronized void onServiceConnected(ComponentName name, IBinder service) {
    if (isConnected() || wasUnbound()) {
      Log.w(TAG, "Connection have been used already.");
      return;
    }

    try {
      binder = IRemoteJobService.Stub.asInterface(service);
      binder.start(invocationData, callback);
    } catch (RemoteException remoteException) {
      Log.e(TAG, "Failed to start jobservice", remoteException);
      unbind();
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

  synchronized void onStop(boolean needToSendResult) {
    if (!wasUnbound()) {
      if (isConnected()) {
        try {
          binder.stop(invocationData, needToSendResult);
        } catch (RemoteException remoteException) {
          Log.e(TAG, "Failed to stop jobservice", remoteException);
        }
      }
      unbind();
    } else {
      Log.w(TAG, "Can't send stop request because service was unbound.");
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
}
