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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import com.firebase.jobdispatcher.ExecutionDelegator.JobFinishedCallback;
import java.util.Arrays;
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

/** Tests for the {@link GooglePlayDriver} class. */
@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 23)
public class GooglePlayDriverTest {
  @Mock private Context mMockContext;
  @Mock private JobCallback jobCallbackMock;
  @Mock private JobFinishedCallback callbackMock;
  @Captor ArgumentCaptor<JobServiceConnection> serviceConnectionCaptor;

  private TestJobDriver driver;
  private GooglePlayDriver googlePlayDriver;
  private FirebaseJobDispatcher dispatcher;
  private final GooglePlayReceiver googlePlayReceiver = new GooglePlayReceiver();

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    googlePlayDriver = new GooglePlayDriver(mMockContext);
    driver = new TestJobDriver(googlePlayDriver);
    dispatcher = new FirebaseJobDispatcher(driver);

    when(mMockContext.getPackageName()).thenReturn("foo.bar.whatever");
  }

  @After
  public void tearDown() {
    GooglePlayReceiver.clearCallbacks();
  }

  @Test
  public void testSchedule_failsWhenPlayServicesIsUnavailable() throws Exception {
    markBackendUnavailable();
    mockPackageManagerInfo();

    Job job = null;
    try {
      job =
          dispatcher
              .newJobBuilder()
              .setService(TestJobService.class)
              .setTag("foobar")
              .setConstraints(Constraint.DEVICE_CHARGING)
              .setTrigger(Trigger.executionWindow(0, 60))
              .build();
    } catch (ValidationEnforcer.ValidationException ve) {
      fail(TextUtils.join("\n", ve.getErrors()));
    }

    assertEquals(
        "Expected schedule() request to fail when backend is unavailable",
        FirebaseJobDispatcher.SCHEDULE_RESULT_NO_DRIVER_AVAILABLE,
        dispatcher.schedule(job));
  }

  @Test
  public void testCancelJobs_backendUnavailable() throws Exception {
    markBackendUnavailable();

    assertEquals(
        "Expected cancelAll() request to fail when backend is unavailable",
        FirebaseJobDispatcher.CANCEL_RESULT_NO_DRIVER_AVAILABLE,
        dispatcher.cancelAll());
  }

  @Test
  public void testSchedule_sendsAppropriateBroadcast() {
    ArgumentCaptor<Intent> pmQueryIntentCaptor = mockPackageManagerInfo();

    Job job =
        dispatcher
            .newJobBuilder()
            .setConstraints(Constraint.DEVICE_CHARGING)
            .setService(TestJobService.class)
            .setTrigger(Trigger.executionWindow(0, 60))
            .setRecurring(false)
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL)
            .setTag("foobar")
            .build();

    Intent pmQueryIntent = pmQueryIntentCaptor.getValue();
    assertEquals(JobService.ACTION_EXECUTE, pmQueryIntent.getAction());
    assertEquals(TestJobService.class.getName(), pmQueryIntent.getComponent().getClassName());

    assertEquals(
        "Expected schedule() request to succeed",
        FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS,
        dispatcher.schedule(job));

    final ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
    verify(mMockContext).sendBroadcast(captor.capture());

    Intent broadcast = captor.getValue();

    assertNotNull(broadcast);
    assertEquals("com.google.android.gms.gcm.ACTION_SCHEDULE", broadcast.getAction());
    assertEquals("SCHEDULE_TASK", broadcast.getStringExtra("scheduler_action"));
    assertEquals("com.google.android.gms", broadcast.getPackage());
    assertEquals(8, broadcast.getIntExtra("source", -1));
    assertEquals(1, broadcast.getIntExtra("source_version", -1));

    final Parcelable parcelablePendingIntent = broadcast.getParcelableExtra("app");
    assertTrue(
        "Expected 'app' value to be a PendingIntent",
        parcelablePendingIntent instanceof PendingIntent);
  }

  @Test
  public void schedule_whenRunning_onStopIsCalled() {
    // simulate running job
    Bundle bundle = TestUtil.getBundleForContentJobExecution();

    JobCoder prefixedCoder = new JobCoder(BundleProtocol.PACKED_PARAM_BUNDLE_PREFIX);
    JobInvocation invocation = prefixedCoder.decodeIntentBundle(bundle);
    googlePlayReceiver.prepareJob(jobCallbackMock, bundle);

    when(mMockContext.bindService(
            any(Intent.class), serviceConnectionCaptor.capture(), eq(BIND_AUTO_CREATE)))
        .thenReturn(true);
    new ExecutionDelegator(mMockContext, callbackMock).executeJob(invocation);

    Job job =
        TestUtil.getBuilderWithNoopValidator()
            .setService(TestJobService.class)
            .setTrigger(invocation.getTrigger())
            .setTag(invocation.getTag())
            .build();

    googlePlayDriver.schedule(job); // reschedule request during the execution

    verify(mMockContext).sendBroadcast(any(Intent.class));

    assertTrue(serviceConnectionCaptor.getValue().wasUnbound());
    assertNull(
        "JobServiceConnection should be removed.",
        ExecutionDelegator.getJobServiceConnection(invocation.getService()));
  }

  private ArgumentCaptor<Intent> mockPackageManagerInfo() {
    PackageManager packageManager = mock(PackageManager.class);
    when(mMockContext.getPackageManager()).thenReturn(packageManager);
    ArgumentCaptor<Intent> intentArgCaptor = ArgumentCaptor.forClass(Intent.class);

    ResolveInfo info = new ResolveInfo();
    info.serviceInfo = new ServiceInfo();
    info.serviceInfo.enabled = true;

    // noinspection WrongConstant
    when(packageManager.queryIntentServices(intentArgCaptor.capture(), eq(0)))
        .thenReturn(Arrays.asList(info));

    return intentArgCaptor;
  }

  @Test
  public void testCancel_sendsAppropriateBroadcast() {
    dispatcher.cancel("foobar");

    ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
    verify(mMockContext).sendBroadcast(captor.capture());

    Intent broadcast = captor.getValue();

    assertNotNull(broadcast);
    assertEquals("foobar", broadcast.getStringExtra("tag"));
  }

  private void markBackendUnavailable() {
    driver.available = false;
  }

  /**
   * A simple {@link Driver} implementation that allows overriding the result of {@link
   * #isAvailable}.
   */
  public static final class TestJobDriver implements Driver {
    public boolean available = true;

    private final Driver wrappedDriver;

    public TestJobDriver(Driver wrappedDriver) {
      this.wrappedDriver = wrappedDriver;
    }

    @Override
    public int schedule(@NonNull Job job) {
      return this.wrappedDriver.schedule(job);
    }

    @Override
    public int cancel(@NonNull String tag) {
      return this.wrappedDriver.cancel(tag);
    }

    @Override
    public int cancelAll() {
      return this.wrappedDriver.cancelAll();
    }

    @NonNull
    @Override
    public JobValidator getValidator() {
      return this.wrappedDriver.getValidator();
    }

    @Override
    public boolean isAvailable() {
      return available;
    }
  }
}
