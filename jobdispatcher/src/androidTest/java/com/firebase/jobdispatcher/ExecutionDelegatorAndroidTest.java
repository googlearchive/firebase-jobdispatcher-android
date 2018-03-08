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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.google.common.util.concurrent.SettableFuture;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Android test for {@link ExecutionDelegator}. */
@RunWith(AndroidJUnit4.class)
public class ExecutionDelegatorAndroidTest {

  private static final int TIMEOUT_SECONDS = 5;

  private Context appContext;
  private SettableFuture<JobInvocation> finishedJobInvocationFuture;
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
    finishedJobInvocationFuture = SettableFuture.create();
    jobResult = -1;
    ConstraintChecker constraintChecker = new ConstraintChecker(appContext);
    executionDelegator =
        new ExecutionDelegator(
            appContext,
            /* jobFinishedCallback= */ (jobInvocation, result) -> {
              jobResult = result;
              finishedJobInvocationFuture.set(jobInvocation);
            },
            constraintChecker);
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
  public void execute() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue(
        "Latch wasn't counted down as expected",
        startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  public void executeAndStopWithResult() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    verifyStopRequestWasProcessedWithResult();
  }

  @Test
  public void executeAndStopWithoutResult() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    verifyStopRequestWasProcessedWithoutResult();
  }

  @Test
  public void execute_unbind_jobStopped() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    ExecutionDelegator.getJobServiceConnection(jobInvocation.getService()).unbind();

    assertTrue("Job should be stopped.", stopLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  public void execute_twice_stopAndRestart() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    startLatch = new CountDownLatch(1);

    executionDelegator.executeJob(jobInvocation);

    assertTrue("First job should be stopped.", stopLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertTrue("Job should be started again.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  public void execute_afterStopWithResult_jobServiceStartedAgain() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

    verifyStopRequestWasProcessedWithResult();

    ExecutionDelegator.stopJob(jobInvocation, true);
    ExecutionDelegator.stopJob(jobInvocation, true); // should not throw when called again

    startLatch = new CountDownLatch(1);

    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started again.", startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  public void executeTwoJobs_stopFirst_secondStays() throws Exception {
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

    assertFalse(
        ExecutionDelegator.getJobServiceConnection(secondJobInvocation.getService()).wasUnbound());

    startLatch = new CountDownLatch(1);
    executionDelegator.executeJob(jobInvocation);
    assertTrue(
        "First job should be started again because it was stopped.",
        startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  private void verifyStopRequestWasProcessedWithResult() throws Exception {
    ExecutionDelegator.stopJob(jobInvocation, /* needToSendResult= */ true);

    try {
      JobInvocation finishedJobInvocation =
          finishedJobInvocationFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertEquals(jobInvocation, finishedJobInvocation);
      assertEquals(JobService.RESULT_SUCCESS, jobResult);
    } catch (TimeoutException e) {
      throw new AssertionError("Timed out waiting for finishedJobInvocationFuture to be set", e);
    }
  }

  private void verifyStopRequestWasProcessedWithoutResult() throws Exception {
    ExecutionDelegator.stopJob(jobInvocation, /* needToSendResult= */ false);

    try {
      finishedJobInvocationFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      fail("finishedJobInvocationFuture was unexpectedly set");
    } catch (TimeoutException expected) {
      assertEquals(-1, jobResult);
    }
  }
}
