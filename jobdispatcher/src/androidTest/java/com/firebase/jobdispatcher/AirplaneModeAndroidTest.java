// Copyright 2017 Google, Inc.
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

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static junit.framework.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import com.google.common.util.concurrent.SettableFuture;
import java.io.FileInputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Verifies jobs with network constraints don't run under airplane mode. Requires API level 21+ (for
 * {@link UiAutomation#executeShellCommand(String)}), as well as an installed and available Google
 * Play services.
 */
@RunWith(AndroidJUnit4.class)
public final class AirplaneModeAndroidTest {

  /** Log tag. */
  private static final String TAG = "FJD_TEST";
  /** How long we're willing to wait for the network to change state. */
  private static final int NETWORK_STATE_CHANGE_TIMEOUT_SECONDS = 20;
  /** How long we're willing to wait for a job to run after it becomes eligible. */
  private static final int EXECUTE_JOB_TIMEOUT_SECONDS = 30;

  private Context testContext;
  private Context appContext;
  private UiAutomation uiAutomation;
  private ConnectivityManager connManager;
  private FirebaseJobDispatcher dispatcher;

  private final SettableFuture<Void> jobStartedFuture = SettableFuture.create();

  @Before
  public void setUp() {
    assumeTrue(
        getClass().getSimpleName() + " requires API level 21+ to run",
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP);

    testContext = InstrumentationRegistry.getContext();
    appContext = InstrumentationRegistry.getTargetContext();
    uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    connManager = (ConnectivityManager) testContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(appContext));

    TestJobService.setProxy(
        new TestJobService.JobServiceProxy() {
          @Override
          public boolean onStartJob(JobService jobService, JobParameters params) {
            jobStartedFuture.set(null);
            return false;
          }

          @Override
          public boolean onStopJob(JobService jobService, JobParameters params) {
            return false;
          }
        });
  }

  @After
  public void tearDown() throws Exception {
    setAirplaneModeEnabled(false);
  }

  @Test
  public void immediateTrigger_withNoConstraints_shouldRunInAirplaneMode() throws Exception {
    verifyJobRunsInAirplaneMode(
        dispatcher
            .newJobBuilder()
            .setService(TestJobService.class)
            .setTrigger(Trigger.NOW)
            .setTag("basic-immediate-job--no-constraint")
            .build());
  }

  @Test
  public void executionWindowTrigger_withNoConstraints_shouldRunInAirplaneMode() throws Exception {
    verifyJobRunsInAirplaneMode(
        dispatcher
            .newJobBuilder()
            .setService(TestJobService.class)
            .setTrigger(Trigger.executionWindow(0, 15))
            .setTag("basic-execution-window-job--no-constraint")
            .build());
  }

  @Test
  public void immediateTrigger_withNetworkConstraint_shouldNotRunInAirplaneMode() throws Exception {
    verifyJobDoesntRunInAirplaneMode(
        dispatcher
            .newJobBuilder()
            .setService(TestJobService.class)
            .setTrigger(Trigger.NOW)
            .addConstraint(Constraint.ON_ANY_NETWORK)
            .setTag("basic-immediate-job")
            .build());
  }

  @Test
  public void executionWindowTrigger_withNetworkConstraint_shouldNotRunInAirplaneMode()
      throws Exception {
    verifyJobDoesntRunInAirplaneMode(
        dispatcher
            .newJobBuilder()
            .setService(TestJobService.class)
            .setTrigger(Trigger.executionWindow(0, 15))
            .addConstraint(Constraint.ON_ANY_NETWORK)
            .setTag("basic-execution-window-job")
            .build());
  }

  private void verifyJobRunsInAirplaneMode(Job job) throws Exception {
    enableAirplaneMode();

    dispatcher.mustSchedule(job);

    try {
      jobStartedFuture.get(EXECUTE_JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new AssertionError(
          "Timed out waiting for job with no network constraints to run in airplane mode", e);
    }
  }

  private void verifyJobDoesntRunInAirplaneMode(Job job) throws Exception {
    // Verify that the job does not run while the device is in airplane mode
    enableAirplaneMode();
    dispatcher.mustSchedule(job);
    try {
      jobStartedFuture.get(EXECUTE_JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      fail("Shouldn't have run job with network constraints while in airplane mode");
    } catch (TimeoutException e) {
      // expected
    }

    // Verify that the job runs after airplane mode is disabled
    disableAirplaneMode();
    try {
      jobStartedFuture.get(EXECUTE_JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (CancellationException
        | ExecutionException
        | InterruptedException
        | TimeoutException e) {
      throw new AssertionError(
          "Should have run job with network constraints once airplane mode was disabled", e);
    }
  }

  private void enableAirplaneMode() throws Exception {
    setAirplaneModeEnabled(true);
    waitForAllNetworksToDisconnect();
  }

  private void disableAirplaneMode() throws Exception {
    setAirplaneModeEnabled(false);
    waitForSomeNetworkToConnect();
  }

  private void setAirplaneModeEnabled(boolean enabled) throws Exception {
    Log.i(TAG, "Setting airplane mode to " + enabled);

    String value = String.valueOf(enabled ? 1 : 0);

    // Update the setting
    executeShellCommand("settings put global airplane_mode_on " + value);

    // Check the setting took
    String newSetting = executeShellCommand("settings get global airplane_mode_on");
    assertThat(newSetting).isEqualTo(value);

    // Let everything know we flipped the setting
    executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE");
  }

  private void waitForAllNetworksToDisconnect() throws Exception {
    final SettableFuture<Void> future = SettableFuture.create();

    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ctx, Intent intent) {
            Network[] networks = connManager.getAllNetworks();
            for (Network network : networks) {
              NetworkInfo info = connManager.getNetworkInfo(network);
              if (info != null && info.isAvailable()) {
                // not done yet
                return;
              }
            }

            future.set(null);
          }
        };

    testContext.registerReceiver(
        receiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

    try {
      future.get(NETWORK_STATE_CHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      testContext.unregisterReceiver(receiver);
    }
  }

  private void waitForSomeNetworkToConnect() throws Exception {
    final SettableFuture<Void> future = SettableFuture.create();

    ConnectivityManager.NetworkCallback cb =
        new ConnectivityManager.NetworkCallback() {
          @Override
          public void onAvailable(Network network) {
            NetworkInfo netInfo = connManager.getNetworkInfo(network);
            if (netInfo != null && netInfo.isConnected()) {
              future.set(null);
            }
          }
        };

    connManager.requestNetwork(
        new NetworkRequest.Builder().addCapability(NET_CAPABILITY_INTERNET).build(), cb);

    try {
      future.get(NETWORK_STATE_CHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      connManager.unregisterNetworkCallback(cb);
    }
  }

  private String executeShellCommand(String cmd) throws Exception {
    ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(cmd);
    StringBuilder stdout = new StringBuilder();
    try (FileInputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
      byte[] buffer = new byte[1024];
      int bytesRead = 0;

      while ((bytesRead = inputStream.read(buffer)) != -1) {
        stdout.append(new String(buffer, 0, bytesRead, UTF_8));
      }
    }

    Log.i(TAG, "$ adb shell " + cmd + "\n" + stdout);
    return stdout.toString().trim(); // trim trailing newline
  }
}
