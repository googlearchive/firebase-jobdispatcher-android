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

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.SimpleArrayMap;
import android.util.Log;

import com.firebase.jobdispatcher.JobService.JobResult;

/**
 * Handles incoming execute requests from the GooglePlay driver and forwards them to your Service.
 */
public final class GooglePlayReceiver extends ExternalReceiver {
    /**
     * Logging tag.
     */
    /* package */ static final String TAG = "FJD.GooglePlayReceiver";
    /**
     * The action sent by Google Play services that triggers job execution.
     */
    @VisibleForTesting
    static final String ACTION_EXECUTE = "com.google.android.gms.gcm.ACTION_TASK_READY";

    /** Action sent by Google Play services when your app has been updated. */
    @VisibleForTesting
    static final String ACTION_INITIALIZE = "com.google.android.gms.gcm.SERVICE_ACTION_INITIALIZE";

    private static final String ERROR_NULL_INTENT = "Null Intent passed, terminating";
    private static final String ERROR_UNKNOWN_ACTION = "Unknown action received, terminating";
    private static final String ERROR_NO_DATA = "No data provided, terminating";

    private final JobCoder prefixedCoder =
        new JobCoder(BundleProtocol.PACKED_PARAM_BUNDLE_PREFIX, true);

    private GooglePlayCallbackExtractor callbackExtractor = new GooglePlayCallbackExtractor();

    /**
     * Endpoint (String) -> Tag (String) -> JobCallback
     */
    private SimpleArrayMap<String, SimpleArrayMap<String, JobCallback>> callbacks =
        new SimpleArrayMap<>(1);

    private static void sendResultSafely(JobCallback callback, int result) {
        try {
            callback.jobFinished(result);
        } catch (Throwable e) {
            Log.e(TAG, "Encountered error running callback", e.getCause());
        }
    }

    @Override
    public final int onStartCommand(Intent intent, int flags, int startId) {
        try {
            super.onStartCommand(intent, flags, startId);

            if (intent == null) {
                Log.w(TAG, ERROR_NULL_INTENT);
                return START_NOT_STICKY;
            }

            String action = intent.getAction();
            if (ACTION_EXECUTE.equals(action)) {
                executeJob(prepareJob(intent));
                return START_NOT_STICKY;
            } else if (ACTION_INITIALIZE.equals(action)) {
                return START_NOT_STICKY;
            }

            Log.e(TAG, ERROR_UNKNOWN_ACTION);
            return START_NOT_STICKY;
        } finally {
            synchronized (this) {
                if (callbacks.isEmpty()) {
                    stopSelf(startId);
                }
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Nullable
    private JobParameters prepareJob(Intent intent) {
        Bundle data = intent.getExtras();
        if (data == null) {
            Log.e(TAG, ERROR_NO_DATA);
            return null;
        }

        // get the callback first. If we don't have this we can't talk back to the backend.
        JobCallback callback = callbackExtractor.extractCallback(data);
        if (callback == null) {
            Log.i(TAG, "no callback found");
            return null;
        }

        Bundle extras = data.getBundle(GooglePlayJobWriter.REQUEST_PARAM_EXTRAS);
        if (extras == null) {
            Log.i(TAG, "no 'extras' bundle found");
            sendResultSafely(callback, JobService.RESULT_FAIL_NORETRY);
            return null;
        }

        JobInvocation job = prefixedCoder.decode(extras);
        if (job == null) {
            Log.i(TAG, "unable to decode job from extras");
            sendResultSafely(callback, JobService.RESULT_FAIL_NORETRY);
            return null;
        }

        // repack the extras
        job.getExtras().putAll(extras);

        synchronized (this) {
            SimpleArrayMap<String, JobCallback> map = callbacks.get(job.getService());
            if (map == null) {
                map = new SimpleArrayMap<>(1);
                callbacks.put(job.getService(), map);
            }

            map.put(job.getTag(), callback);
        }

        return job;
    }

    @Override
    protected void onJobFinished(@NonNull JobParameters js, @JobResult int result) {
        synchronized (this) {
            SimpleArrayMap<String, JobCallback> map = callbacks.get(js.getService());
            if (map == null) {
                return;
            }

            JobCallback callback = map.remove(js.getTag());
            if (callback != null) {
                Log.i(TAG, "sending jobFinished for " + js.getTag() + " = " + result);
                callback.jobFinished(result);
            }

            if (map.isEmpty()) {
                callbacks.remove(js.getService());
            }
        }
    }
}
