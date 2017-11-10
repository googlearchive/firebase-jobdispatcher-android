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
import static com.firebase.jobdispatcher.GooglePlayJobWriter.REQUEST_PARAM_TAG;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import com.firebase.jobdispatcher.ExecutionDelegator.JobFinishedCallback;
import com.firebase.jobdispatcher.JobInvocation.Builder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests {@link GooglePlayMessageHandler}. */
@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 21)
public class GooglePlayMessageHandlerTest {

  @Mock Looper looper;
  @Mock GooglePlayReceiver receiverMock;
  @Mock Context contextMock;
  @Mock AppOpsManager appOpsManager;
  @Mock Messenger messengerMock;
  @Mock JobFinishedCallback jobFinishedCallbackMock;
  @Captor ArgumentCaptor<JobServiceConnection> jobServiceConnectionCaptor;

  @Captor private ArgumentCaptor<IJobCallback> jobCallbackCaptor;
  @Captor private ArgumentCaptor<Bundle> bundleCaptor;
  @Mock private IRemoteJobService jobServiceMock;
  @Mock private IBinder iBinderMock;

  ExecutionDelegator executionDelegator;

  GooglePlayMessageHandler handler;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    handler = new GooglePlayMessageHandler(looper, receiverMock);
    executionDelegator = new ExecutionDelegator(contextMock, jobFinishedCallbackMock);
    when(receiverMock.getExecutionDelegator()).thenReturn(executionDelegator);
    when(receiverMock.getApplicationContext()).thenReturn(contextMock);
    when(contextMock.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(appOpsManager);
  }

  @After
  public void tearDown() {
    ExecutionDelegator.cleanServiceConnections();
  }

  @Test
  public void handleMessage_nullNoException() throws Exception {
    handler.handleMessage(null);
  }

  @Test
  public void handleMessage_ignoreIfSenderIsNotGcm() throws Exception {
    Message message = Message.obtain();
    message.what = GooglePlayMessageHandler.MSG_START_EXEC;
    Bundle data = new Bundle();
    data.putString(REQUEST_PARAM_TAG, "TAG");
    message.setData(data);
    message.replyTo = messengerMock;
    doThrow(new SecurityException())
        .when(appOpsManager)
        .checkPackage(message.sendingUid, GooglePlayDriver.BACKEND_PACKAGE);
    handler.handleMessage(message);
    verify(receiverMock, never()).prepareJob(any(GooglePlayMessengerCallback.class), eq(data));
  }

  @Test
  public void handleMessage_startExecution_noData() throws Exception {
    Message message = Message.obtain();
    message.what = GooglePlayMessageHandler.MSG_START_EXEC;
    message.replyTo = messengerMock;

    handler.handleMessage(message);
    verify(receiverMock, never())
        .prepareJob(any(GooglePlayMessengerCallback.class), any(Bundle.class));
  }

  @Test
  public void handleMessage_startExecution() throws Exception {
    Message message = Message.obtain();
    message.what = GooglePlayMessageHandler.MSG_START_EXEC;
    Bundle data = new Bundle();
    data.putString(REQUEST_PARAM_TAG, "TAG");
    message.setData(data);
    message.replyTo = messengerMock;
    JobInvocation jobInvocation =
        new Builder()
            .setTag("tag")
            .setService(TestJobService.class.getName())
            .setTrigger(Trigger.NOW)
            .build();
    when(receiverMock.prepareJob(any(GooglePlayMessengerCallback.class), eq(data)))
        .thenReturn(jobInvocation);

    handler.handleMessage(message);
    verify(contextMock)
        .bindService(any(Intent.class), jobServiceConnectionCaptor.capture(), eq(BIND_AUTO_CREATE));
    assertTrue(jobServiceConnectionCaptor.getValue().hasJobInvocation(jobInvocation));
  }

  @Test
  public void handleMessage_stopExecution_noUnbindWaitForTheResult() throws Exception {
    Message message = Message.obtain();
    message.what = GooglePlayMessageHandler.MSG_STOP_EXEC;
    JobCoder jobCoder = GooglePlayReceiver.getJobCoder();
    Bundle data = TestUtil.encodeContentUriJob(TestUtil.getContentUriTrigger(), jobCoder);
    JobInvocation jobInvocation = jobCoder.decode(data).build();
    message.setData(data);
    message.replyTo = messengerMock;

    when(contextMock.bindService(
            any(Intent.class), any(JobServiceConnection.class), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);

    executionDelegator.executeJob(jobInvocation);
    verify(contextMock)
        .bindService(any(Intent.class), jobServiceConnectionCaptor.capture(), eq(BIND_AUTO_CREATE));
    JobServiceConnection serviceConnection = jobServiceConnectionCaptor.getValue();
    assertTrue(serviceConnection.hasJobInvocation(jobInvocation));

    when(iBinderMock.queryLocalInterface(anyString())).thenReturn(jobServiceMock);
    serviceConnection.onServiceConnected(null, iBinderMock);

    verify(jobServiceMock).start(bundleCaptor.capture(), jobCallbackCaptor.capture());

    handler.handleMessage(message);

    assertEquals(
        serviceConnection, ExecutionDelegator.getJobServiceConnection(jobInvocation.getService()));
    assertFalse(serviceConnection.wasUnbound());
    // The connection must be active to process jobFinished
    verify(contextMock, never()).unbindService(serviceConnection);

    jobCallbackCaptor
        .getValue()
        .jobFinished(bundleCaptor.getValue(), JobService.RESULT_FAIL_NORETRY);

    verify(contextMock).unbindService(serviceConnection);
    assertTrue(serviceConnection.wasUnbound());
  }

  @Test
  public void handleMessage_stopExecution_invalidNoCrash() throws Exception {
    Message message = Message.obtain();
    message.what = GooglePlayMessageHandler.MSG_STOP_EXEC;
    message.replyTo = messengerMock;

    handler.handleMessage(message);
  }
}
