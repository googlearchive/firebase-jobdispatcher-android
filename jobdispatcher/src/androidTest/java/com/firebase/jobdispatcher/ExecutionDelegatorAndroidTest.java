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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Android test for {@link ExecutionDelegator}. */
@RunWith(AndroidJUnit4.class)
public class ExecutionDelegatorAndroidTest {

  private static final int TIMEOUT_SECONDS = 10;

  private Context appContext;
  private JobInvocation finishedJobInvocation;
  private int jobResult;
  private ExecutionDelegator executionDelegator;
  private final JobInvocation jobInvocation =
      new JobInvocation.Builder()
          .setTag("tag")
          .setService(TestJobService.class.getName())
          .setTrigger(Trigger.NOW)
          .build();
  private volatile CountDownLatch startLatch;
  private volatile CountDownLatch stopLatch;

  @Before
  public void setUp() {
    appContext = InstrumentationRegistry.getTargetContext();
    TestJobService.reset();
    finishedJobInvocation = null;
    jobResult = -1;
    executionDelegator =
        new ExecutionDelegator(
            appContext,
            /* jobFinishedCallback= */ (jobInvocation, result) -> {
              finishedJobInvocation = jobInvocation;
              jobResult = result;
            });
    startLatch = new CountDownLatch(1);
    stopLatch = new CountDownLatch(1);

    ExecutionDelegator.cleanServiceConnections();

    TestJobService.setProxy(
        new TestJobService.JobServiceProxy() {
          Set<String> runningJobs = new HashSet<>();

          @Override
          public boolean onStartJob(JobParameters params) {
            if (runningJobs.add(params.getTag())) {
              startLatch.countDown();
            } else {
              fail("Job was already started.");
            }
            return true;
          }

          @Override
          public boolean onStopJob(JobParameters params) {
            if (runningJobs.remove(params.getTag())) {
              stopLatch.countDown();
            } else {
              fail("Job was not running.");
            }
            return false;
          }
        });
  }

  @Test
  public void execute() throws InterruptedException {
    executionDelegator.executeJob(jobInvocation);
    assertTrue(
        "Latch wasn't counted down as expected",
        startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  public void executeAndStopWithResult() throws InterruptedException {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    verifyStopRequestWasProcessed(true);
  }

  @Test
  public void executeAndStopWithoutResult() throws InterruptedException {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    verifyStopRequestWasProcessed(false);
  }

  @Test
  public void execute_unbind_jobStopped() throws InterruptedException {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    synchronized (ExecutionDelegator.serviceConnections) {
      ExecutionDelegator.serviceConnections.get(jobInvocation).unbind();
    }

    assertTrue("Job should be stopped.", stopLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  public void execute_twice_stopAndRestart() throws InterruptedException {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    startLatch = new CountDownLatch(1);

    executionDelegator.executeJob(jobInvocation);

    assertTrue("First job should be stopped.", stopLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertTrue("Job should be started again.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  public void execute_afterStopWithResult_jobServiceStartedAgain() throws InterruptedException {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    verifyStopRequestWasProcessed(true);

    ExecutionDelegator.stopJob(jobInvocation, true);
    ExecutionDelegator.stopJob(jobInvocation, true); // should not throw when called again

    startLatch = new CountDownLatch(1);

    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started again.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  public void executeTwoJobs_stopFirst_secondStays() throws InterruptedException {
    JobInvocation secondJobInvocation =
        new JobInvocation.Builder()
            .setTag("secondJob")
            .setService(TestJobService.class.getName())
            .setTrigger(Trigger.NOW)
            .build();

    executionDelegator.executeJob(jobInvocation);
    assertTrue("First job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    startLatch = new CountDownLatch(1);
    executionDelegator.executeJob(secondJobInvocation);
    assertTrue(
        "Second job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    ExecutionDelegator.stopJob(jobInvocation, false);

    assertTrue("First job should be stopped.", stopLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    synchronized (ExecutionDelegator.serviceConnections) {
      assertFalse(ExecutionDelegator.serviceConnections.get(secondJobInvocation).wasUnbound());
    }

    startLatch = new CountDownLatch(1);
    executionDelegator.executeJob(jobInvocation);
    assertTrue(
        "First job should be started again because it was stopped.",
        startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  private void verifyStopRequestWasProcessed(boolean withResult) throws InterruptedException {
    ExecutionDelegator.stopJob(jobInvocation, withResult);
    // Idle the main looper twice; once to process the call to onStopJob and once to process the
    // corresponding jobFinishedCallback that sets the finishedJobInvocation variable.
    idleMainLooper();
    idleMainLooper();

    if (withResult) {
      assertEquals(jobInvocation, finishedJobInvocation);
      assertEquals(JobService.RESULT_SUCCESS, jobResult);
    } else {
      assertNull(finishedJobInvocation);
      assertEquals(-1, jobResult);
    }
  }

  private static void idleMainLooper() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    new Handler(Looper.getMainLooper()).post(latch::countDown);
    assertTrue(
        "Looper didn't run posted runnable.", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }
}
