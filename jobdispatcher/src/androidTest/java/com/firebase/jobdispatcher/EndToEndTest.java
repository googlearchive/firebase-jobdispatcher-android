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
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import com.google.common.util.concurrent.SettableFuture;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
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

  private static final String FIRST_JOB = "first_job";
  private static final String SECOND_JOB = "second_job";

  @Before
  public void setUp() {
    appContext = InstrumentationRegistry.getTargetContext();
    dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(appContext));
    TestJobService.reset();
  }

  @After
  public void tearDown() {
    dispatcher.cancelAll();
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

  @Test
  public void startAndRescheduleTwoJobs_sameProcess_stopsOnReschedule() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(2);
    // Does not need to be concurrent because access is guarded by the startLatch.
    Set<String> startedJobs = new HashSet<>();
    CountDownLatch stopLatch = new CountDownLatch(2);
    // Does not need to be concurrent because access is guarded by the stopLatch.
    Set<String> stoppedJobs = new HashSet<>();
    TestJobService.setProxy(
        new TestJobService.JobServiceProxy() {
          @Override
          public boolean onStartJob(JobParameters params) {
            startedJobs.add(params.getTag());
            startLatch.countDown();
            return true;
          }

          @Override
          public boolean onStopJob(JobParameters params) {
            stoppedJobs.add(params.getTag());
            stopLatch.countDown();
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
            .setTag(FIRST_JOB)
            .setExtras(extras)
            .build());

    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(TestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag(SECOND_JOB)
            .setExtras(extras)
            .build());
    assertTrue(startLatch.await(120, TimeUnit.SECONDS));
    assertEquals(2, startedJobs.size());
    assertTrue(startedJobs.contains(FIRST_JOB));
    assertTrue(startedJobs.contains(SECOND_JOB));
    assertTrue(stoppedJobs.isEmpty());

    // reschedule
    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(TestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag(FIRST_JOB)
            .setExtras(extras)
            .build());

    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(TestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag(SECOND_JOB)
            .setExtras(extras)
            .build());

    assertTrue(stopLatch.await(120, TimeUnit.SECONDS));
    assertEquals(2, stoppedJobs.size());
    assertTrue(stoppedJobs.contains(FIRST_JOB));
    assertTrue(stoppedJobs.contains(SECOND_JOB));
  }

  @Test
  public void startAndRescheduleTwoJobs_workerProcess_stopOnReschedule() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(2);
    // Does not need to be concurrent because access is guarded by the startLatch.
    Set<String> startedJobs = new HashSet<>();
    CountDownLatch stopLatch = new CountDownLatch(2);
    // Does not need to be concurrent because access is guarded by the stopLatch.
    Set<String> stoppedJobs = new HashSet<>();

    appContext.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            Log.i(WorkerProcessTestJobService.TAG, "BroadcastReceiver intent " + intent);
            int eventType =
                intent.getExtras().getInt(WorkerProcessTestJobService.EXTRA_EVENT_TYPE, -1);
            String tag = intent.getExtras().getString(WorkerProcessTestJobService.EXTRA_BUNDLE_TAG);
            assertNotNull(tag);

            switch (eventType) {
              case WorkerProcessTestJobService.EVENT_TYPE_ON_START_JOB:
                startedJobs.add(tag);
                startLatch.countDown();
                break;
              case WorkerProcessTestJobService.EVENT_TYPE_ON_STOP_JOB:
                stoppedJobs.add(tag);
                stopLatch.countDown();
                break;
              default:
                fail("Unexpected event type " + eventType);
            }
            Bundle results = new Bundle();
            results.putBoolean(WorkerProcessTestJobService.EXTRA_MORE_WORK_REMAINING, true);
            setResultExtras(results);
          }
        },
        new IntentFilter(WorkerProcessTestJobService.ACTION_JOBSERVICE_EVENT));

    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(WorkerProcessTestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag(FIRST_JOB)
            .build());

    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(WorkerProcessTestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag(SECOND_JOB)
            .build());
    assertTrue(startLatch.await(120, TimeUnit.SECONDS));
    assertEquals(2, startedJobs.size());
    assertTrue(startedJobs.contains(FIRST_JOB));
    assertTrue(startedJobs.contains(SECOND_JOB));
    assertTrue(stoppedJobs.isEmpty());

    // reschedule
    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(WorkerProcessTestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag(FIRST_JOB)
            .build());

    dispatcher.mustSchedule(
        dispatcher
            .newJobBuilder()
            .setService(WorkerProcessTestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag(SECOND_JOB)
            .build());

    assertTrue(stopLatch.await(120, TimeUnit.SECONDS));
    assertEquals(2, stoppedJobs.size());
    assertTrue(stoppedJobs.contains(FIRST_JOB));
    assertTrue(stoppedJobs.contains(SECOND_JOB));
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
