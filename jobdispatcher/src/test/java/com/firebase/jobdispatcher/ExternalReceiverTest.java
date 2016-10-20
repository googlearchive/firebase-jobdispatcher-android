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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.firebase.jobdispatcher.JobService.JobResult;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 23)
public class ExternalReceiverTest {

    private static List<JobParameters> jobCombinations;
    private Context mMockContext;
    private TestJobReceiver mReceiver;

    @BeforeClass
    public static void setUpClass() {
        // uses a Bundle, so can't happen before Robolectric's mucked with the classpath
        jobCombinations = TestUtil.getJobCombinations(TestUtil.getBuilderWithNoopValidator());
    }

    @Before
    public void setUp() {
        mMockContext = spy(RuntimeEnvironment.application);
        doReturn("com.example.foo").when(mMockContext).getPackageName();

        mReceiver = new TestJobReceiver(mMockContext);
    }

    @Test
    public void testExecuteJob_sendsBroadcastWithJobAndMessage() throws Exception {
        mReceiver.onCreate();

        for (JobParameters input : jobCombinations) {
            verifyExecuteJob(input);
        }

        mReceiver.onDestroy();
    }

    private void verifyExecuteJob(JobParameters input) throws Exception {
        reset(mMockContext);
        mReceiver.lastResult = -1;

        mReceiver.setLatch(new CountDownLatch(1));

        mReceiver.executeJob(input);

        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        final ArgumentCaptor<ServiceConnection> connCaptor =
            ArgumentCaptor.forClass(ServiceConnection.class);
        verify(mMockContext).bindService(intentCaptor.capture(), connCaptor.capture(), anyInt());

        final Intent result = intentCaptor.getValue();
        // verify the intent was sent to the right place
        assertEquals(input.getService(), result.getComponent().getClassName());
        assertEquals(JobService.ACTION_EXECUTE, result.getAction());

        final ServiceConnection connection = connCaptor.getValue();

        ComponentName cname = mock(ComponentName.class);
        JobService.LocalBinder mockLocalBinder = mock(JobService.LocalBinder.class);
        final JobParameters[] out = new JobParameters[1];

        JobService mockJobService = new JobService() {
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
        assertTrue(mReceiver.mLatch.await(1, TimeUnit.SECONDS));

        // verify the lastResult was set correctly
        assertEquals(JobService.RESULT_SUCCESS, mReceiver.lastResult);
    }

    @Test
    public void testExecuteJob_handlesNull() {
        assertFalse("Expected calling triggerExecution on null to fail and return false",
            mReceiver.triggerExecution(null));
    }

    @Test
    public void testHandleMessage_doesntCrashOnBadJobData() {
        Job j = TestUtil.getBuilderWithNoopValidator()
            .setService(TestJobService.class)
            .build();

        mReceiver.onCreate();
        mReceiver.triggerExecution(j);

        ArgumentCaptor<Intent> intentCapto =
            ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<ServiceConnection> connCaptor =
            ArgumentCaptor.forClass(ServiceConnection.class);

        //noinspection WrongConstant
        verify(mMockContext).bindService(intentCapto.capture(), connCaptor.capture(), anyInt());

        Intent executeReq = intentCapto.getValue();
        assertEquals(JobService.ACTION_EXECUTE, executeReq.getAction());

        mReceiver.onDestroy();
    }

    public final static class TestJobReceiver extends ExternalReceiver {
        public int lastResult;

        private CountDownLatch mLatch;
        private Context mContext;

        public TestJobReceiver(Context ctx) {
            mContext = ctx;

            // Required for getPackageName() and other basic Context methods to work correctly
            // without a manifest defined.
            attachBaseContext(ctx);
        }

        /**
         * Exposes the protected {@link #executeJob(JobParameters)} method.
         */
        public boolean triggerExecution(JobParameters jobParameters) {
            return executeJob(jobParameters);
        }

        @Override
        protected void onJobFinished(@NonNull JobParameters js, @JobResult int result) {
            lastResult = result;

            if (mLatch != null) {
                mLatch.countDown();
            }
        }

        /**
         * Convenience method for tests.
         */
        public void setLatch(CountDownLatch latch) {
            mLatch = latch;
        }

        /**
         * Wire it up to use the passed Context, otherwise Robolectric uses a hidden Context and
         * doesn't actually do the correct thing.
         */
        @Override
        public boolean bindService(Intent service, ServiceConnection conn, int flags) {
            return mContext.bindService(service, conn, flags);
        }

        @Nullable
        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}
