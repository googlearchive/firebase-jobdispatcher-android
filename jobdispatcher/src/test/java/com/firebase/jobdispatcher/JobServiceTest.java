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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import com.google.android.gms.gcm.PendingCallback;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 23)
public class JobServiceTest {
    private static CountDownLatch countDownLatch;

    @Before
    public void setUp() throws Exception {}

    @After
    public void tearDown() throws Exception {
        countDownLatch = null;
    }

    @Test
    public void testOnStartCommand_handlesNullIntent() throws Exception {
        JobService service = spy(new ExampleJobService());
        int startId = 7;

        try {
            service.onStartCommand(null, 0, startId);

            verify(service).stopSelf(startId);
        } catch (NullPointerException npe) {
            fail("Unexpected NullPointerException after calling onStartCommand with a null Intent.");
        }
    }

    @Test
    public void testOnStartCommand_handlesNullAction() throws Exception {
        JobService service = spy(new ExampleJobService());
        int startId = 7;

        Intent nullActionIntent = new Intent();
        service.onStartCommand(nullActionIntent, 0, startId);

        verify(service).stopSelf(startId);
    }

    @Test
    public void testOnStartCommand_handlesEmptyAction() throws Exception {
        JobService service = spy(new ExampleJobService());
        int startId = 7;

        Intent emptyActionIntent = new Intent("");
        service.onStartCommand(emptyActionIntent, 0, startId);

        verify(service).stopSelf(startId);
    }

    @Test
    public void testOnStartCommand_handlesUnknownAction() throws Exception {
        JobService service = spy(new ExampleJobService());
        int startId = 7;

        Intent emptyActionIntent = new Intent("foo.bar.baz");
        service.onStartCommand(emptyActionIntent, 0, startId);

        verify(service).stopSelf(startId);
    }

    @Test
    public void testOnStartCommand_handlesStartJob_nullData() {
        JobService service = spy(new ExampleJobService());
        int startId = 7;

        Intent executeJobIntent = new Intent(JobService.ACTION_EXECUTE);
        service.onStartCommand(executeJobIntent, 0, startId);

        verify(service).stopSelf(startId);
    }

    @Test
    public void testOnStartCommand_handlesStartJob_noTag() {
        JobService service = spy(new ExampleJobService());
        int startId = 7;

        Intent executeJobIntent = new Intent(JobService.ACTION_EXECUTE);
        Parcel p = Parcel.obtain();
        p.writeStrongBinder(mock(IBinder.class));
        executeJobIntent.putExtra("callback", new PendingCallback(p));

        service.onStartCommand(executeJobIntent, 0, startId);

        verify(service).stopSelf(startId);

        p.recycle();
    }

    @Test
    public void testOnStartCommand_handlesStartJob_noCallback() {
        JobService service = spy(new ExampleJobService());
        int startId = 7;

        Intent executeJobIntent = new Intent(JobService.ACTION_EXECUTE);
        executeJobIntent.putExtra("tag", "foobar");

        service.onStartCommand(executeJobIntent, 0, startId);

        verify(service).stopSelf(startId);
    }

    @Test
    public void testOnStartCommand_handlesStartJob_validRequest() throws InterruptedException {
        JobService service = spy(new ExampleJobService());

        HandlerThread ht = new HandlerThread("handler");
        ht.start();
        Handler h = new Handler(ht.getLooper());

        Intent executeJobIntent = new Intent(JobService.ACTION_EXECUTE);

        Job jobSpec = TestUtil.getBuilderWithNoopValidator()
            .setTag("tag")
            .setService(ExampleJobService.class)
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL)
            .setTrigger(Trigger.NOW)
            .setLifetime(Lifetime.FOREVER)
            .build();

        countDownLatch = new CountDownLatch(1);

        ((JobService.LocalBinder) service.onBind(executeJobIntent))
            .getService()
            .start(jobSpec, h.obtainMessage(ExternalReceiver.JOB_FINISHED, jobSpec));

        assertTrue("Expected job to run to completion", countDownLatch.await(5, TimeUnit.SECONDS));
    }

    public static class ExampleJobService extends JobService {
        @Override
        public boolean onStartJob(JobParameters job) {
            countDownLatch.countDown();
            return false;
        }

        @Override
        public boolean onStopJob(JobParameters job) {
            return false;
        }
    }
}
