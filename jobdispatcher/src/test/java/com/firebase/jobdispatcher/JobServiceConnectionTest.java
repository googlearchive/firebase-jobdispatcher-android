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

import static com.firebase.jobdispatcher.GooglePlayReceiver.getJobCoder;
import static com.firebase.jobdispatcher.TestUtil.assertBundlesEqual;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.util.Pair;
import com.firebase.jobdispatcher.JobInvocation.Builder;
import com.google.common.base.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Test for {@link JobServiceConnection}. */
@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 23)
public class JobServiceConnectionTest {

  JobInvocation job =
      new Builder()
          .setTag("tag")
          .setService(TestJobService.class.getName())
          .setTrigger(Trigger.NOW)
          .build();

  Bundle jobData = getJobCoder().encode(job, new Bundle());

  @Mock Context contextMock;
  JobServiceConnection connection;
  ManuallyMockedRemoteJobService binderMock;
  IJobCallback noopCallback;

  /**
   * The combination of Mockito and ShadowBinder ends up meaning we can't just wrap the IBinder in a
   * spy. So this is a manually created mock object.
   */
  private static class ManuallyMockedRemoteJobService extends IRemoteJobService.Stub {

    Pair<Bundle, IJobCallback> startArguments;
    Pair<Bundle, Boolean> stopArguments;

    Optional<RemoteException> startException = Optional.absent();
    Optional<RemoteException> stopException = Optional.absent();

    @Override
    public void start(Bundle invocationData, IJobCallback callback) throws RemoteException {
      startArguments = Pair.create(invocationData, callback);
      if (startException.isPresent()) {
        throw startException.get();
      }
    }

    @Override
    public void stop(Bundle invocationData, boolean needToSendResult) throws RemoteException {
      stopArguments = Pair.create(invocationData, needToSendResult);
      if (stopException.isPresent()) {
        throw stopException.get();
      }
    }

    void verifyStartArguments(Bundle invocationData, IJobCallback callback) {
      assertBundlesEqual(invocationData, startArguments.first);
      assertSame(callback, startArguments.second);
    }

    void verifyStopArguments(Bundle invocationData, boolean needToSendResult) {
      assertBundlesEqual(invocationData, stopArguments.first);
      assertEquals(needToSendResult, stopArguments.second);
    }

    void setStartException(RemoteException remoteException) {
      startException = Optional.of(remoteException);
    }

    void setStopException(RemoteException remoteException) {
      stopException = Optional.of(remoteException);
    }

    void reset() {
      startArguments = null;
      stopArguments = null;
    }
  }

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    noopCallback =
        new IJobCallback.Stub() {
          @Override
          public void jobFinished(Bundle invocationData, @JobService.JobResult int result) {}
        };
    connection = new JobServiceConnection(job, noopCallback, contextMock);
    binderMock = new ManuallyMockedRemoteJobService();
  }

  @Test
  public void fullConnectionCycle() throws Exception {
    assertFalse(connection.wasUnbound());
    connection.onServiceConnected(null, binderMock);
    binderMock.verifyStartArguments(jobData, noopCallback);
    assertFalse(connection.wasUnbound());

    connection.onStop(true);
    binderMock.verifyStopArguments(jobData, true);
    assertTrue(connection.wasUnbound());

    connection.onServiceDisconnected(null);
    assertTrue(connection.wasUnbound());
  }

  @Test
  public void onServiceDisconnected() throws Exception {
    connection.onServiceConnected(null, binderMock);
    binderMock.verifyStartArguments(jobData, noopCallback);
    assertFalse(connection.wasUnbound());

    connection.onServiceDisconnected(null);
    assertTrue(connection.wasUnbound());
  }

  @Test
  public void onServiceConnected_shouldNotSendExecutionRequestTwice() throws Exception {
    assertFalse(connection.wasUnbound());

    connection.onServiceConnected(null, binderMock);
    binderMock.verifyStartArguments(jobData, noopCallback);
    assertFalse(connection.wasUnbound());
    binderMock.reset();

    connection.onServiceConnected(null, binderMock);
    assertNull(binderMock.startArguments); // start should not be called again

    connection.onStop(true);
    binderMock.verifyStopArguments(jobData, true);
    assertTrue(connection.wasUnbound());

    connection.onServiceDisconnected(null);
    assertTrue(connection.wasUnbound());
  }

  @Test
  public void stopOnUnboundConnection_nothingHappens() throws Exception {
    assertFalse(connection.wasUnbound());

    connection.onStop(true);

    assertTrue(connection.wasUnbound());
    verify(contextMock).unbindService(connection);
  }

  @Test
  public void onServiceConnectedWrongBinder_doesNotThrow() throws Exception {
    IBinder binder = mock(IBinder.class);
    connection.onServiceConnected(null, binder);
  }

  @Test
  public void onStop_doNotSendResult() throws Exception {
    connection.onServiceConnected(null, binderMock);
    binderMock.verifyStartArguments(jobData, noopCallback);
    assertFalse(connection.wasUnbound());

    connection.onStop(false);
    binderMock.verifyStopArguments(jobData, false);
    assertTrue(connection.wasUnbound());
  }

  @Test
  public void unbind() throws Exception {
    connection.onServiceConnected(null, binderMock);
    binderMock.verifyStartArguments(jobData, noopCallback);
    assertFalse(connection.wasUnbound());

    connection.unbind();

    assertTrue(connection.wasUnbound());
    verify(contextMock).unbindService(connection);
  }

  @Test
  public void unbind_throws_noException() throws Exception {
    connection.onServiceConnected(null, binderMock);
    binderMock.verifyStartArguments(jobData, noopCallback);
    assertFalse(connection.wasUnbound());

    doThrow(IllegalArgumentException.class).when(contextMock).unbindService(connection);

    connection.unbind();

    assertTrue(connection.wasUnbound());
    verify(contextMock).unbindService(connection);
  }

  @Test
  public void onStop_unbindsAfterRemoteException() throws Exception {
    connection.onServiceConnected(null, binderMock);
    binderMock.verifyStartArguments(jobData, noopCallback);
    assertFalse(connection.wasUnbound());

    binderMock.setStopException(new RemoteException("something bad happened"));
    connection.onStop(true);
    binderMock.verifyStopArguments(jobData, true);
    assertTrue(connection.wasUnbound());
    verify(contextMock).unbindService(connection);
  }

  @Test
  public void serviceConnected_unbindsAfterRemoteException() throws Exception {
    binderMock.setStartException(new RemoteException("something bad happened"));

    connection.onServiceConnected(null, binderMock);
    assertTrue(connection.wasUnbound());
  }
}
