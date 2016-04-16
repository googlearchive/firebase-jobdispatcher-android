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

import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.gms.gcm.PendingCallback;

/** Responsible for extracting a JobCallback from a given Bundle. */
/* package */ final class GooglePlayCallbackExtractor {
    private static final String ERROR_NULL_CALLBACK = "No callback received, terminating";
    private static final String ERROR_INVALID_CALLBACK = "Bad callback received, terminating";
    private static final String TAG = GooglePlayReceiver.TAG;

    public JobCallback extractCallback(@Nullable Bundle data) {
        if (data == null) {
            Log.e(TAG, ERROR_NULL_CALLBACK);
            return null;
        }

        data.setClassLoader(PendingCallback.class.getClassLoader());

        Parcelable parcelledCallback = data.getParcelable("callback");
        if (parcelledCallback == null) {
            Log.e(TAG, ERROR_NULL_CALLBACK);
            return null;
        } else if (!(parcelledCallback instanceof PendingCallback)) {
            Log.e(TAG, ERROR_INVALID_CALLBACK);
            return null;
        }

        return new GooglePlayJobCallback(((PendingCallback) parcelledCallback).getIBinder());
    }
}
