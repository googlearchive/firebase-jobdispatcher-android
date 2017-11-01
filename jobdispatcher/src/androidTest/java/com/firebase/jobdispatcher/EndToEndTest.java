// Copyright 2017 Google, Inc.
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

import static com.firebase.jobdispatcher.TestUtil.assertBundlesEqual;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Basic end to end test for the JobDispatcher. Requires Google Play services be installed and
 * available.
 */
@RunWith(AndroidJUnit4.class)
public final class EndToEndTest {
  private Context appContext;
  private FirebaseJobDispatcher dispatcher;

  @Before
  public void setUp() {
    appContext = InstrumentationRegistry.getTargetContext();
    dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(appContext));
    TestJobService.reset();
  }

  @Test
  public void basicImmediateJob() throws Exception {
    final SettableFuture<Bundle> bundleFuture = SettableFuture.create();
    TestJobService.setProxy(
        new TestJobService.JobServiceProxy() {
          @Override
          public boolean onStartJob(JobParameters params) {
            bundleFuture.set(params.getExtras());
            return false;
          }

          @Override
          public boolean onStopJob(JobParameters params) {
            return false;
          }
        });

    Bundle extras = new Bundle();
    extras.putBoolean("extras", true);

    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(TestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag("basic-immediate-job")
            .setExtras(extras)
            .build());

    assertBundlesEqual(extras, bundleFuture.get(120, TimeUnit.SECONDS));
  }

  /**
   * Tests that JobServices work correctly when defined in alternative processes. Relies on the
   * broadcast-sending code in {@link WorkerProcessTestJobService}.
   */
  @Test
  public void workerProcessImmediateJob() throws Exception {
    final SettableFuture<Bundle> bundleFuture = SettableFuture.create();
    appContext.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            Bundle results = new Bundle();
            results.putBoolean(WorkerProcessTestJobService.EXTRA_MORE_WORK_REMAINING, false);
            setResultExtras(results);

            bundleFuture.set(
                intent.getBundleExtra(WorkerProcessTestJobService.EXTRA_BUNDLE_EXTRAS));
          }
        },
        new IntentFilter(WorkerProcessTestJobService.ACTION_JOBSERVICE_EVENT));

    Bundle extras = new Bundle();
    extras.putBoolean("extras", true);

    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(WorkerProcessTestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag("worker-process-immediate-job")
            .setExtras(extras)
            .build());

    assertBundlesEqual(extras, bundleFuture.get(120, TimeUnit.SECONDS));
  }
}
