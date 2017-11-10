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

import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** A JobService that's configured via the manifest to run in a different process. */
public class WorkerProcessTestJobService extends JobService {
  public static final String TAG = "FJD.WorkerProcessJob";
  public static final String ACTION_JOBSERVICE_EVENT = "action_jobservice_event";
  public static final String EXTRA_EVENT_TYPE = "event";
  public static final String EXTRA_MORE_WORK_REMAINING = "more_work";
  public static final String EXTRA_BUNDLE_EXTRAS = "extras";
  public static final String EXTRA_BUNDLE_TAG = "tag";
  public static final int EVENT_TYPE_ON_START_JOB = 1;
  public static final int EVENT_TYPE_ON_STOP_JOB = 2;

  private final HandlerThread handlerThread = new HandlerThread("worker-handler");
  private final SettableFuture<Handler> handlerFuture = SettableFuture.create();

  @Override
  public void onCreate() {
    super.onCreate();

    handlerThread.start();
    handlerFuture.set(new Handler(handlerThread.getLooper()));
  }

  @Override
  public boolean onStartJob(JobParameters job) {
    Log.i(TAG, "onStartJob " + job);
    return sendOrderedBroadcastAndGetResult(createIntentForEventType(job, EVENT_TYPE_ON_START_JOB));
  }

  @Override
  public boolean onStopJob(JobParameters job) {
    Log.i(TAG, "onStopJob " + job);
    return sendOrderedBroadcastAndGetResult(createIntentForEventType(job, EVENT_TYPE_ON_STOP_JOB));
  }

  private boolean sendOrderedBroadcastAndGetResult(Intent intent) {
    try {
      final SettableFuture<Boolean> moreWorkFuture = SettableFuture.create();
      sendOrderedBroadcast(
          /* intent= */ intent,
          /* receiverPermission= */ null,
          /* resultReceiver= */ new BroadcastReceiver() {
            @Override
            public void onReceive(Context unused, Intent intent) {
              moreWorkFuture.set(
                  getResultExtras(/* makeMap= */ true).getBoolean(EXTRA_MORE_WORK_REMAINING));
            }
          },
          // this is being called on the main thread, so we need our receiver to run elsewhere
          /* scheduler= */ handlerFuture.get(),
          /* initialCode= */ 0,
          /* initialData= */ null,
          /* initialExtras= */ null);

      // Wait for 5 seconds, which is the longest we expect this broadcast chain to take
      return moreWorkFuture.get(5, SECONDS);
    } catch (TimeoutException | InterruptedException | ExecutionException e) {
      throw new RuntimeException(e); // crash the process
    }
  }

  private Intent createIntentForEventType(JobParameters job, int eventType) {
    return new Intent(ACTION_JOBSERVICE_EVENT)
        .putExtra(EXTRA_EVENT_TYPE, eventType)
        .putExtra(EXTRA_BUNDLE_EXTRAS, job.getExtras())
        .putExtra(EXTRA_BUNDLE_TAG, job.getTag())
        .setPackage(getPackageName());
  }
}
