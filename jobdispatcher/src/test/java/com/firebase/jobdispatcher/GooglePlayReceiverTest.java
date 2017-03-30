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
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Messenger;
import com.firebase.jobdispatcher.GooglePlayReceiverTest.ShadowMessenger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        receiver = new GooglePlayReceiver();
    }

    @Test
    public void prepareJob_messenger() {
        JobInvocation jobInvocation = receiver.prepareJob(new Bundle(), callbackMock);
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
}
