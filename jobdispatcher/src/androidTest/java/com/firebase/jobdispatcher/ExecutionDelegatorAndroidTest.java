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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.firebase.jobdispatcher.TestJobService.JobServiceProxy;
import com.google.common.util.concurrent.SettableFuture;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
          public boolean onStartJob(JobService jobService, JobParameters params) {
            if (runningJobs.add(params.getTag())) {
              startLatch.countDown();
            } else {
              fail("Job was already started.");
            }
            return true;
          }

          @Override
          public boolean onStopJob(JobService jobService, JobParameters params) {
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
    assertTrue("Latch wasn't counted down as expected", startLatch.await(TIMEOUT_SECONDS, SECONDS));
  }

  @Test
  public void executeAndStopWithResult() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, SECONDS));

    verifyStopRequestWasProcessedWithResult();
  }

  @Test
  public void executeAndStopWithoutResult() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, SECONDS));

    verifyStopRequestWasProcessedWithoutResult();
  }

  @Test
  public void execute_unbind_jobStopped() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, SECONDS));

    ExecutionDelegator.getJobServiceConnection(jobInvocation.getService()).unbind();

    assertTrue("Job should be stopped.", stopLatch.await(TIMEOUT_SECONDS, SECONDS));
  }

  @Test
  public void execute_twice_stopAndRestart() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, SECONDS));

    startLatch = new CountDownLatch(1);

    executionDelegator.executeJob(jobInvocation);

    assertTrue("First job should be stopped.", stopLatch.await(TIMEOUT_SECONDS, SECONDS));
    assertTrue("Job should be started again.", startLatch.await(TIMEOUT_SECONDS, SECONDS));
  }

  @Test
  public void execute_afterStopWithResult_jobServiceStartedAgain() throws Exception {
    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started.", startLatch.await(TIMEOUT_SECONDS, SECONDS));

    verifyStopRequestWasProcessedWithResult();

    ExecutionDelegator.stopJob(jobInvocation, true);
    ExecutionDelegator.stopJob(jobInvocation, true); // should not throw when called again

    startLatch = new CountDownLatch(1);

    executionDelegator.executeJob(jobInvocation);
    assertTrue("Job should be started again.", startLatch.await(TIMEOUT_SECONDS, SECONDS));
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
    assertTrue("First job should be started.", startLatch.await(TIMEOUT_SECONDS, SECONDS));

    startLatch = new CountDownLatch(1);
    executionDelegator.executeJob(secondJobInvocation);
    assertTrue("Second job should be started.", startLatch.await(TIMEOUT_SECONDS, SECONDS));

    ExecutionDelegator.stopJob(jobInvocation, false);

    assertTrue("First job should be stopped.", stopLatch.await(TIMEOUT_SECONDS, SECONDS));

    assertFalse(
        ExecutionDelegator.getJobServiceConnection(secondJobInvocation.getService()).wasUnbound());

    startLatch = new CountDownLatch(1);
    executionDelegator.executeJob(jobInvocation);
    assertTrue(
        "First job should be started again because it was stopped.",
        startLatch.await(TIMEOUT_SECONDS, SECONDS));
  }

  @Test
  public void executeTwoJobs_unbindsWhenDone() throws Exception {
    JobInvocation.Builder jobBuilder =
        new JobInvocation.Builder()
            .setService(TestJobService.class.getName())
            .setTrigger(Trigger.NOW);

    JobInvocation jobOne = jobBuilder.setTag("one").build();
    JobInvocation jobTwo = jobBuilder.setTag("two").build();

    SettableFuture<JobService> jobServiceFuture = SettableFuture.create();
    TestJobService.setProxy(
        new JobServiceProxy() {
          @Override
          public boolean onStartJob(JobService jobService, JobParameters job) {
            jobServiceFuture.set(jobService);
            return true; // more work to do
          }

          @Override
          public boolean onStopJob(JobService jobService, JobParameters job) {
            return true; // more work to do
          }
        });

    CountDownLatch latch = new CountDownLatch(2);

    ExecutionDelegator delegator =
        new ExecutionDelegator(
            appContext,
            /* jobFinishedCallback= */ (jobInvocation, result) -> latch.countDown(),
            new ConstraintChecker(appContext));

    delegator.executeJob(jobOne);
    delegator.executeJob(jobTwo);

    JobService jobService = jobServiceFuture.get(1, SECONDS);

    JobServiceConnection connection =
        ExecutionDelegator.getJobServiceConnection(TestJobService.class.getName());

    assertThat(connection.isConnected()).isTrue();
    jobService.jobFinished(jobOne, /* needsReschedule= */ true);
    jobService.jobFinished(jobTwo, /* needsReschedule= */ true);

    assertWithMessage("Timed out waiting for callback to be called")
        .that(latch.await(1, SECONDS))
        .isTrue();

    assertThat(connection.isConnected()).isFalse();
    assertThat(connection.wasUnbound()).isTrue();
    assertThat(ExecutionDelegator.getJobServiceConnection(TestJobService.class.getName())).isNull();
  }

  @Test
  public void handlesMissingJobService() throws Exception {
    // disable the component without shutting the app down
    appContext
        .getPackageManager()
        .setComponentEnabledSetting(
            new ComponentName(appContext, TestJobService.class),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);

    // try to execute the job
    executionDelegator.executeJob(jobInvocation);

    assertThat(finishedJobInvocationFuture.get(1, SECONDS)).isEqualTo(jobInvocation);
    assertThat(jobResult).isEqualTo(JobService.RESULT_FAIL_RETRY);

    // Make sure we unbound correctly
    assertThat(ExecutionDelegator.getJobServiceConnection(TestJobService.class.getName())).isNull();
  }

  private void verifyStopRequestWasProcessedWithResult() throws Exception {
    ExecutionDelegator.stopJob(jobInvocation, /* needToSendResult= */ true);

    try {
      JobInvocation finishedJobInvocation =
          finishedJobInvocationFuture.get(TIMEOUT_SECONDS, SECONDS);

      assertEquals(jobInvocation, finishedJobInvocation);
      assertEquals(JobService.RESULT_SUCCESS, jobResult);
    } catch (TimeoutException e) {
      throw new AssertionError("Timed out waiting for finishedJobInvocationFuture to be set", e);
    }
  }

  private void verifyStopRequestWasProcessedWithoutResult() throws Exception {
    ExecutionDelegator.stopJob(jobInvocation, /* needToSendResult= */ false);

    try {
      finishedJobInvocationFuture.get(TIMEOUT_SECONDS, SECONDS);
      fail("finishedJobInvocationFuture was unexpectedly set");
    } catch (TimeoutException expected) {
      assertEquals(-1, jobResult);
    }
  }
}
