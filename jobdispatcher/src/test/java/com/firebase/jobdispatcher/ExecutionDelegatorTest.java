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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import com.firebase.jobdispatcher.JobService.JobResult;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the {@link ExecutionDelegator}. */
@SuppressWarnings("WrongConstant")
@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 21)
public class ExecutionDelegatorTest {

  private TestJobReceiver receiver;
  private ExecutionDelegator executionDelegator;
  private IRemoteJobService.Stub noopBinder;

  @Captor private ArgumentCaptor<Intent> intentCaptor;
  @Captor private ArgumentCaptor<JobServiceConnection> connCaptor;
  @Captor private ArgumentCaptor<IJobCallback> jobCallbackCaptor;
  @Captor private ArgumentCaptor<Bundle> bundleCaptor;
  @Mock private Context mockContext;
  @Mock private IRemoteJobService jobServiceMock;
  @Mock private IBinder iBinderMock;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(mockContext.getPackageName()).thenReturn("com.example.foo");

    receiver = new TestJobReceiver();
    executionDelegator = new ExecutionDelegator(mockContext, receiver);
    ExecutionDelegator.cleanServiceConnections();

    noopBinder =
        new IRemoteJobService.Stub() {
          @Override
          public void start(Bundle invocationData, IJobCallback callback) {}

          @Override
          public void stop(Bundle invocationData, boolean needToSendResult) {}
        };
  }

  @Test
  public void jobFinished() throws RemoteException {
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

    JobServiceConnection connection = connCaptor.getValue();
    when(iBinderMock.queryLocalInterface(IRemoteJobService.class.getName()))
        .thenReturn(jobServiceMock);
    connection.onServiceConnected(null, iBinderMock);

    verify(jobServiceMock).start(bundleCaptor.capture(), jobCallbackCaptor.capture());

    jobCallbackCaptor
        .getValue()
        .jobFinished(bundleCaptor.getValue(), JobService.RESULT_FAIL_NORETRY);

    assertNull(ExecutionDelegator.getJobServiceConnection("service"));
    assertEquals(JobService.RESULT_FAIL_NORETRY, receiver.lastResult);
    assertTrue(connection.wasUnbound());
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
    connCaptor.getValue().onServiceConnected(null, noopBinder);

    assertFalse(connCaptor.getValue().wasUnbound());
    assertTrue(connCaptor.getValue().isConnected());
    reset(mockContext);

    executionDelegator.executeJob(jobInvocation);

    assertFalse(connCaptor.getValue().wasUnbound());
    verify(mockContext, never()).unbindService(connCaptor.getValue());

    verify(mockContext, never())
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
    jobServiceConnection.onServiceConnected(null, noopBinder);
    jobServiceConnection.unbind();

    verify(mockContext).unbindService(jobServiceConnection);

    reset(mockContext);

    executionDelegator.executeJob(jobInvocation);
    verify(mockContext)
        .bindService(any(Intent.class), any(JobServiceConnection.class), eq(BIND_AUTO_CREATE));
  }

  private void verifyExecuteJob(JobInvocation input) throws Exception {
    reset(mockContext);
    when(mockContext.getPackageName()).thenReturn("com.example.foo");
    receiver.lastResult = -1;

    receiver.setLatch(new CountDownLatch(1));

    when(mockContext.bindService(
            any(Intent.class), any(JobServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);

    executionDelegator.executeJob(input);

    verify(mockContext)
        .bindService(intentCaptor.capture(), connCaptor.capture(), eq(BIND_AUTO_CREATE));

    final Intent result = intentCaptor.getValue();
    // verify the intent was sent to the right place
    assertEquals(input.getService(), result.getComponent().getClassName());
    assertEquals(JobService.ACTION_EXECUTE, result.getAction());

    final JobServiceConnection connection = connCaptor.getValue();

    final AtomicReference<JobParameters> jobParametersAtomicReference = new AtomicReference<>();
    IRemoteJobService.Stub jobServiceBinder =
        new IRemoteJobService.Stub() {
          @Override
          public void start(Bundle invocationData, IJobCallback callback) {
            jobParametersAtomicReference.set(
                GooglePlayReceiver.getJobCoder().decode(invocationData).build());
          }

          @Override
          public void stop(Bundle invocationData, boolean needToSendResult) {}
        };

    connection.onServiceConnected(null, jobServiceBinder);

    TestUtil.assertJobsEqual(input, jobParametersAtomicReference.get());
    // Clean up started job. Otherwise new job won't be started.
    ExecutionDelegator.stopJob(input, false);
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

    IRemoteJobService.Stub jobServiceBinder =
        new IRemoteJobService.Stub() {
          @Override
          public void start(Bundle invocationData, IJobCallback callback) {
            startedJobFuture.set(GooglePlayReceiver.getJobCoder().decode(invocationData).build());
          }

          @Override
          public void stop(Bundle invocationData, boolean needToSendResult) {
            stoppedJobFuture.set(GooglePlayReceiver.getJobCoder().decode(invocationData).build());
          }
        };

    final ServiceConnection connection = connCaptor.getValue();
    connection.onServiceConnected(null, jobServiceBinder);

    ExecutionDelegator.stopJob(job, true);

    TestUtil.assertJobsEqual(job, startedJobFuture.get(0, TimeUnit.SECONDS));
    TestUtil.assertJobsEqual(job, stoppedJobFuture.get(0, TimeUnit.SECONDS));
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
    int lastResult = -1;

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
