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

import static android.content.Context.BIND_AUTO_CREATE;
import static com.firebase.jobdispatcher.TestUtil.getContentUriTrigger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Looper;
import android.support.annotation.NonNull;
import com.firebase.jobdispatcher.JobService.JobResult;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Tests for the {@link ExecutionDelegator}. */
@SuppressWarnings("WrongConstant")
@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 21)
public class ExecutionDelegatorTest {

  private TestJobReceiver receiver;
  private ExecutionDelegator executionDelegator;

  @Captor private ArgumentCaptor<Intent> intentCaptor;
  @Captor private ArgumentCaptor<JobServiceConnection> connCaptor;
  @Mock private JobService.LocalBinder binderMock;
  @Mock private Context mockContext;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    doReturn("com.example.foo").when(mockContext).getPackageName();

    receiver = new TestJobReceiver();
    executionDelegator = new ExecutionDelegator(mockContext, receiver);
    ExecutionDelegator.cleanServiceConnections();

    when(binderMock.getService())
        .thenReturn(
            new JobService() {
              @Override
              public boolean onStartJob(JobParameters job) {
                return true;
              }

              @Override
              public boolean onStopJob(JobParameters job) {
                return false;
              }
            });
  }

  @Test
  public void testExecuteJob_sendsBroadcastWithJobAndMessage() throws Exception {
    for (JobInvocation input : TestUtil.getJobInvocationCombinations()) {
      verifyExecuteJob(input);
    }
  }

  @Test
  public void executeJob_alreadyRunning_doesNotBindSecondTime() {
    JobInvocation jobInvocation =
        new JobInvocation.Builder()
            .setTag("tag")
            .setService("service")
            .setTrigger(Trigger.NOW)
            .build();

    when(mockContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);

    executionDelegator.executeJob(jobInvocation);
    verify(mockContext)
        .bindService(intentCaptor.capture(), connCaptor.capture(), eq(BIND_AUTO_CREATE));
    connCaptor.getValue().onServiceConnected(null, binderMock);

    assertFalse(connCaptor.getValue().wasUnbound());
    assertTrue(connCaptor.getValue().isConnected());
    reset(mockContext);

    executionDelegator.executeJob(jobInvocation);

    assertTrue(connCaptor.getValue().wasUnbound());
    verify(mockContext).unbindService(connCaptor.getValue());

    verify(mockContext)
        .bindService(any(Intent.class), any(ServiceConnection.class), eq(BIND_AUTO_CREATE));
  }

  @Test
  public void executeJob_startedButNotConnected_doNotStartAgain() {
    JobInvocation jobInvocation =
        new JobInvocation.Builder()
            .setTag("tag")
            .setService("service")
            .setTrigger(Trigger.NOW)
            .build();
    when(mockContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);

    executionDelegator.executeJob(jobInvocation);

    verify(mockContext)
        .bindService(intentCaptor.capture(), connCaptor.capture(), eq(BIND_AUTO_CREATE));
    reset(mockContext);

    executionDelegator.executeJob(jobInvocation);

    verify(mockContext, never())
        .bindService(any(Intent.class), any(JobServiceConnection.class), eq(BIND_AUTO_CREATE));
  }

  @Test
  public void executeJob_wasStartedButDisconnected_startAgain() {
    JobInvocation jobInvocation =
        new JobInvocation.Builder()
            .setTag("tag")
            .setService("service")
            .setTrigger(Trigger.NOW)
            .build();

    when(mockContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);
    executionDelegator.executeJob(jobInvocation);
    verify(mockContext)
        .bindService(intentCaptor.capture(), connCaptor.capture(), eq(BIND_AUTO_CREATE));

    JobServiceConnection jobServiceConnection = connCaptor.getValue();
    jobServiceConnection.onServiceConnected(null, binderMock);
    jobServiceConnection.unbind();

    verify(mockContext).unbindService(jobServiceConnection);

    reset(mockContext);

    executionDelegator.executeJob(jobInvocation);
    verify(mockContext)
        .bindService(any(Intent.class), any(JobServiceConnection.class), eq(BIND_AUTO_CREATE));
  }

  private void verifyExecuteJob(JobInvocation input) throws Exception {
    reset(mockContext);
    receiver.lastResult = -1;

    receiver.setLatch(new CountDownLatch(1));

    when(mockContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);

    executionDelegator.executeJob(input);

    verify(mockContext)
        .bindService(intentCaptor.capture(), connCaptor.capture(), eq(BIND_AUTO_CREATE));

    final Intent result = intentCaptor.getValue();
    // verify the intent was sent to the right place
    assertEquals(input.getService(), result.getComponent().getClassName());
    assertEquals(JobService.ACTION_EXECUTE, result.getAction());

    final ServiceConnection connection = connCaptor.getValue();

    final SettableFuture<JobParameters> startedJobFuture = SettableFuture.create();
    JobService mockJobService =
        new JobService() {
          @Override
          public boolean onStartJob(JobParameters job) {
            startedJobFuture.set(job);
            return false;
          }

          @Override
          public boolean onStopJob(JobParameters job) {
            return false;
          }
        };

    when(binderMock.getService()).thenReturn(mockJobService);

    connection.onServiceConnected(null, binderMock);

    TestUtil.assertJobsEqual(input, startedJobFuture.get(0, TimeUnit.SECONDS));

    // make sure the countdownlatch was decremented
    assertTrue(receiver.latch.await(1, TimeUnit.SECONDS));

    // verify the lastResult was set correctly
    assertEquals(JobService.RESULT_SUCCESS, receiver.lastResult);
  }

  @Test
  public void testExecuteJob_handlesNull() {
    executionDelegator.executeJob(null);
    verifyZeroInteractions(mockContext);
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

    // noinspection WrongConstant
    verify(mockContext).bindService(intentCaptor.capture(), connCaptor.capture(), anyInt());

    Intent executeReq = intentCaptor.getValue();
    assertEquals(JobService.ACTION_EXECUTE, executeReq.getAction());
  }

  @Test
  public void onStop_mock() throws Exception {
    JobInvocation job =
        new JobInvocation.Builder()
            .setTag("TAG")
            .setTrigger(getContentUriTrigger())
            .setService(TestJobService.class.getName())
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL)
            .build();

    reset(mockContext);
    receiver.lastResult = -1;

    when(mockContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);
    executionDelegator.executeJob(job);

    verify(mockContext)
        .bindService(intentCaptor.capture(), connCaptor.capture(), eq(BIND_AUTO_CREATE));

    final Intent bindIntent = intentCaptor.getValue();
    // verify the intent was sent to the right place
    assertEquals(job.getService(), bindIntent.getComponent().getClassName());
    assertEquals(JobService.ACTION_EXECUTE, bindIntent.getAction());

    final SettableFuture<JobParameters> startedJobFuture = SettableFuture.create();
    final SettableFuture<JobParameters> stoppedJobFuture = SettableFuture.create();

    JobService mockJobService =
        new JobService() {
          @Override
          public boolean onStartJob(JobParameters job) {
            startedJobFuture.set(job);
            return true;
          }

          @Override
          public boolean onStopJob(JobParameters job) {
            stoppedJobFuture.set(job);
            return false;
          }
        };

    when(binderMock.getService()).thenReturn(mockJobService);

    final ServiceConnection connection = connCaptor.getValue();
    connection.onServiceConnected(null, binderMock);

    ExecutionDelegator.stopJob(job, true);

    TestUtil.assertJobsEqual(job, startedJobFuture.get(0, TimeUnit.SECONDS));
    TestUtil.assertJobsEqual(job, stoppedJobFuture.get(0, TimeUnit.SECONDS));
  }

  @Test
  public void onStop_calledOnMainThread() throws Exception {
    final JobInvocation job =
        new JobInvocation.Builder()
            .setTag("TAG")
            .setTrigger(getContentUriTrigger())
            .setService(TestJobService.class.getName())
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL)
            .build();

    when(mockContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);

    final SettableFuture<Looper> futureLooper = SettableFuture.create();
    when(binderMock.getService())
        .thenReturn(
            new JobService() {
              @Override
              public boolean onStartJob(JobParameters job) {
                return true;
              }

              @Override
              public boolean onStopJob(JobParameters job) {
                futureLooper.set(Looper.myLooper());
                return false;
              }
            });

    executionDelegator.executeJob(job);
    verify(mockContext)
        .bindService(intentCaptor.capture(), connCaptor.capture(), eq(BIND_AUTO_CREATE));
    connCaptor.getValue().onServiceConnected(null, binderMock);

    // call stopJob on a background thread and wait for it
    Thread workerThread =
        new Thread() {
          @Override
          public void run() {
            ExecutionDelegator.stopJob(job, true);
          }
        };
    workerThread.start();
    workerThread.join(TimeUnit.SECONDS.toMillis(1));

    ShadowLooper.idleMainLooper();
    assertEquals(
        "onStopJob was not called on main thread",
        Looper.getMainLooper(),
        futureLooper.get(1, TimeUnit.SECONDS));
  }

  @Test
  public void failedToBind_unbind() throws Exception {
    JobInvocation job =
        new JobInvocation.Builder()
            .setTag("TAG")
            .setTrigger(getContentUriTrigger())
            .setService(TestJobService.class.getName())
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL)
            .build();

    reset(mockContext);

    when(mockContext.bindService(
            any(Intent.class), any(ServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(false);

    executionDelegator.executeJob(job);

    verify(mockContext)
        .bindService(intentCaptor.capture(), connCaptor.capture(), eq(BIND_AUTO_CREATE));
    verify(mockContext).unbindService(connCaptor.getValue());
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
