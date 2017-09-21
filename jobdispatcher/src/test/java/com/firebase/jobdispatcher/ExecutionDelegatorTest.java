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

import static com.firebase.jobdispatcher.TestUtil.getContentUriTrigger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.support.annotation.NonNull;
import com.firebase.jobdispatcher.JobService.JobResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests for the {@link ExecutionDelegator}. */
@SuppressWarnings("WrongConstant")
@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 21)
public class ExecutionDelegatorTest {

  private Context mockContext;
  private TestJobReceiver receiver;
  private ExecutionDelegator executionDelegator;

  @Before
  public void setUp() {
    mockContext = spy(RuntimeEnvironment.application);
    doReturn("com.example.foo").when(mockContext).getPackageName();

    receiver = new TestJobReceiver();
    executionDelegator = new ExecutionDelegator(mockContext, receiver);
  }

  @Test
  public void testExecuteJob_sendsBroadcastWithJobAndMessage() throws Exception {
    for (JobInvocation input : TestUtil.getJobInvocationCombinations()) {
      verifyExecuteJob(input);
    }
  }

  private void verifyExecuteJob(JobInvocation input) throws Exception {
    reset(mockContext);
    receiver.lastResult = -1;

    receiver.setLatch(new CountDownLatch(1));

    executionDelegator.executeJob(input);

    final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
    final ArgumentCaptor<ServiceConnection> connCaptor =
        ArgumentCaptor.forClass(ServiceConnection.class);
    verify(mockContext).bindService(intentCaptor.capture(), connCaptor.capture(), anyInt());

    final Intent result = intentCaptor.getValue();
    // verify the intent was sent to the right place
    assertEquals(input.getService(), result.getComponent().getClassName());
    assertEquals(JobService.ACTION_EXECUTE, result.getAction());

    final ServiceConnection connection = connCaptor.getValue();

    ComponentName cname = mock(ComponentName.class);
    JobService.LocalBinder mockLocalBinder = mock(JobService.LocalBinder.class);
    final JobParameters[] out = new JobParameters[1];

    JobService mockJobService =
        new JobService() {
          @Override
          public boolean onStartJob(JobParameters job) {
            out[0] = job;
            return false;
          }

          @Override
          public boolean onStopJob(JobParameters job) {
            return false;
          }
        };

    when(mockLocalBinder.getService()).thenReturn(mockJobService);

    connection.onServiceConnected(cname, mockLocalBinder);

    TestUtil.assertJobsEqual(input, out[0]);

    // make sure the countdownlatch was decremented
    assertTrue(receiver.latch.await(1, TimeUnit.SECONDS));

    // verify the lastResult was set correctly
    assertEquals(JobService.RESULT_SUCCESS, receiver.lastResult);
  }

  @Test
  public void testExecuteJob_handlesNull() {
    assertFalse(
        "Expected calling triggerExecution on null to fail and return false",
        executionDelegator.executeJob(null));
  }

  @Test
  public void testHandleMessage_doesntCrashOnBadJobData() {
    JobInvocation j =
        new JobInvocation.Builder()
            .setService(TestJobService.class.getName())
            .setTag("tag")
            .setTrigger(Trigger.NOW)
            .build();

    executionDelegator.executeJob(j);

    ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
    ArgumentCaptor<ServiceConnection> connCaptor = ArgumentCaptor.forClass(ServiceConnection.class);

    // noinspection WrongConstant
    verify(mockContext).bindService(intentCaptor.capture(), connCaptor.capture(), anyInt());

    Intent executeReq = intentCaptor.getValue();
    assertEquals(JobService.ACTION_EXECUTE, executeReq.getAction());
  }

  @Test
  public void onStop_mock() throws InterruptedException {
    JobInvocation job =
        new JobInvocation.Builder()
            .setTag("TAG")
            .setTrigger(getContentUriTrigger())
            .setService(TestJobService.class.getName())
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL)
            .build();

    reset(mockContext);
    receiver.lastResult = -1;

    executionDelegator.executeJob(job);

    final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
    final ArgumentCaptor<ServiceConnection> connCaptor =
        ArgumentCaptor.forClass(ServiceConnection.class);
    verify(mockContext).bindService(intentCaptor.capture(), connCaptor.capture(), anyInt());

    final Intent result = intentCaptor.getValue();
    // verify the intent was sent to the right place
    assertEquals(job.getService(), result.getComponent().getClassName());
    assertEquals(JobService.ACTION_EXECUTE, result.getAction());

    final JobParameters[] out = new JobParameters[2];

    JobService mockJobService =
        new JobService() {
          @Override
          public boolean onStartJob(JobParameters job) {
            out[0] = job;
            return true;
          }

          @Override
          public boolean onStopJob(JobParameters job) {
            out[1] = job;
            return false;
          }
        };

    JobService.LocalBinder mockLocalBinder = mock(JobService.LocalBinder.class);
    when(mockLocalBinder.getService()).thenReturn(mockJobService);

    ComponentName componentName = mock(ComponentName.class);
    final ServiceConnection connection = connCaptor.getValue();
    connection.onServiceConnected(componentName, mockLocalBinder);

    ExecutionDelegator.stopJob(job, true);

    TestUtil.assertJobsEqual(job, out[0]);
    TestUtil.assertJobsEqual(job, out[1]);
  }

  private static final class TestJobReceiver implements ExecutionDelegator.JobFinishedCallback {
    int lastResult;

    private CountDownLatch latch;

    @Override
    public void onJobFinished(@NonNull JobInvocation js, @JobResult int result) {
      lastResult = result;

      if (latch != null) {
        latch.countDown();
      }
    }

    /** Convenience method for tests. */
    public void setLatch(CountDownLatch latch) {
      this.latch = latch;
    }
  }
}
