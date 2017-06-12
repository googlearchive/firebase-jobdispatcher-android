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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Service;
import android.content.Intent;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Messenger;
import com.firebase.jobdispatcher.GooglePlayReceiverTest.ShadowMessenger;
import com.firebase.jobdispatcher.TestUtil.InspectableBinder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(
    constants = BuildConfig.class,
    manifest = Config.NONE,
    sdk = 21,
    shadows = {ShadowMessenger.class}
)
public class GooglePlayReceiverTest {

    /**
     * The default ShadowMessenger implementation causes NPEs when using the
     * {@link Messenger#Messenger(Handler)} constructor. We create our own empty Shadow so we can
     * just use the standard Android implementation, which is totally fine.
     *
     * @see <a href="https://github.com/robolectric/robolectric/issues/2246">Robolectric issue</a>
     *
     */
    @Implements(Messenger.class)
    public static class ShadowMessenger {}

    GooglePlayReceiver receiver;

    @Mock
    Messenger messengerMock;
    @Mock
    IBinder binderMock;
    @Mock
    JobCallback callbackMock;
    @Mock
    ExecutionDelegator executionDelegatorMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        receiver = spy(new GooglePlayReceiver());
        when(receiver.getExecutionDelegator()).thenReturn(executionDelegatorMock);
    }

    @Test
    public void prepareJob_messenger() {
        JobInvocation jobInvocation = receiver.prepareJob(callbackMock, new Bundle());
        assertNull(jobInvocation);
        verify(callbackMock).jobFinished(JobService.RESULT_FAIL_NORETRY);
    }

    @Test
    public void onBind() {
        Intent intent = new Intent(GooglePlayReceiver.ACTION_EXECUTE);
        IBinder binderA = receiver.onBind(intent);
        IBinder binderB = receiver.onBind(intent);

        assertEquals(binderA, binderB);
    }

    @Test
    public void onBind_nullIntent() {
        IBinder binder = receiver.onBind(null);
        assertNull(binder);
    }

    @Test
    public void onBind_wrongAction() {
        Intent intent = new Intent("test");
        IBinder binder = receiver.onBind(intent);
        assertNull(binder);
    }

    @Test
    @Config(sdk = VERSION_CODES.KITKAT)
    public void onBind_wrongBuild() {
        Intent intent = new Intent(GooglePlayReceiver.ACTION_EXECUTE);
        IBinder binder = receiver.onBind(intent);
        assertNull(binder);
    }

    @Test
    public void onStartCommand_nullIntent() {
        assertResultWasStartNotSticky(receiver.onStartCommand(null, 0, 101));
        verify(receiver).stopSelf(101);
    }

    @Test
    public void onStartCommand_initAction() {
        Intent initIntent = new Intent("com.google.android.gms.gcm.SERVICE_ACTION_INITIALIZE");
        assertResultWasStartNotSticky(receiver.onStartCommand(initIntent, 0, 101));
        verify(receiver).stopSelf(101);
    }

    @Test
    public void onStartCommand_unknownAction() {
        Intent unknownIntent = new Intent("com.example.foo.bar");
        assertResultWasStartNotSticky(receiver.onStartCommand(unknownIntent, 0, 101));
        assertResultWasStartNotSticky(receiver.onStartCommand(unknownIntent, 0, 102));
        assertResultWasStartNotSticky(receiver.onStartCommand(unknownIntent, 0, 103));

        InOrder inOrder = inOrder(receiver);
        inOrder.verify(receiver).stopSelf(101);
        inOrder.verify(receiver).stopSelf(102);
        inOrder.verify(receiver).stopSelf(103);
    }

    @Test
    public void onStartCommand_executeActionWithEmptyExtras() {
        Intent execIntent = new Intent("com.google.android.gms.gcm.ACTION_TASK_READY");
        assertResultWasStartNotSticky(receiver.onStartCommand(execIntent, 0, 101));
        verify(receiver).stopSelf(101);
    }

    @Test
    public void onStartCommand_executeAction() {
        JobInvocation job = new JobInvocation.Builder()
            .setTag("tag")
            .setService("com.example.foo.FooService")
            .setTrigger(Trigger.NOW)
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL)
            .setLifetime(Lifetime.UNTIL_NEXT_BOOT)
            .setConstraints(new int[]{Constraint.DEVICE_IDLE})
            .build();

        Intent execIntent = new Intent("com.google.android.gms.gcm.ACTION_TASK_READY")
            .putExtra("extras", new JobCoder(BundleProtocol.PACKED_PARAM_BUNDLE_PREFIX, true)
                .encode(job, new Bundle()))
            .putExtra("callback", new InspectableBinder().toPendingCallback());

        when(executionDelegatorMock.executeJob(any(JobInvocation.class))).thenReturn(true);

        assertResultWasStartNotSticky(receiver.onStartCommand(execIntent, 0, 101));

        verify(receiver, never()).stopSelf(anyInt());
        verify(executionDelegatorMock).executeJob(any(JobInvocation.class));

        receiver.onJobFinished(job, JobService.RESULT_SUCCESS);

        verify(receiver).stopSelf(101);
    }

    private void assertResultWasStartNotSticky(int result) {
        assertEquals(
            "Result for onStartCommand wasn't START_NOT_STICKY", Service.START_NOT_STICKY, result);
    }
}
