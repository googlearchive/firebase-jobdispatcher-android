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

import android.os.IBinder;
import android.os.RemoteException;

import com.google.android.gms.gcm.INetworkTaskCallback;

/**
 * Wraps the GooglePlay-specific callback class in a JobCallback-compatible interface.
 */
/* package */ final class GooglePlayJobCallback implements JobCallback {
    private final INetworkTaskCallback mCallback;

    public GooglePlayJobCallback(IBinder binder) {
        mCallback = INetworkTaskCallback.Stub.asInterface(binder);
    }

    @Override
    public void jobFinished(@JobService.JobResult int status) {
        try {
            mCallback.taskFinished(status);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}
