// Copyright 2018 Google, Inc.
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

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkInfo;

/** Tests for the {@link com.firebase.jobdispatcher.ConstraintChecker} class. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class ConstraintCheckerTest {

  private static final String JOB_TAG = "JobTag";
  private static final String JOB_SERVICE = "JobService";

  private Context context;
  private JobInvocation.Builder jobBuilder;
  private ConstraintChecker constraintChecker;
  private ShadowConnectivityManager shadowConnectivityManager;
  private ShadowNetworkInfo shadowNetworkInfo;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    context = RuntimeEnvironment.application;
    constraintChecker = new ConstraintChecker(context);
    jobBuilder =
        new JobInvocation.Builder().setTag(JOB_TAG).setService(JOB_SERVICE).setTrigger(Trigger.NOW);

    ConnectivityManager connectivityManager =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    shadowConnectivityManager = shadowOf(connectivityManager);
    shadowNetworkInfo = shadowOf(connectivityManager.getActiveNetworkInfo());
  }

  private void setNetworkMetered(boolean isMetered) {
    // Only mobile connections are considered to be metered.
    // See {@link ShadowConnectivityManager#isActiveNetworkMetered()}
    if (isMetered) {
      shadowNetworkInfo.setConnectionType(ConnectivityManager.TYPE_MOBILE);
    } else {
      shadowNetworkInfo.setConnectionType(ConnectivityManager.TYPE_WIFI);
    }
  }

  @Test
  public void testAreConstraintsSatisfied_anyNetworkRequired_satisfied() {
    JobInvocation job =
        jobBuilder.setConstraints(Constraint.uncompact(Constraint.ON_ANY_NETWORK)).build();

    shadowNetworkInfo.setConnectionStatus(/* isConnected= */ true);

    assertThat(constraintChecker.areConstraintsSatisfied(job)).isTrue();
  }

  @Test
  public void testAreConstraintsSatisfied_anyNetworkRequired_unsatisfied_notConnected() {
    JobInvocation job =
        jobBuilder.setConstraints(Constraint.uncompact(Constraint.ON_ANY_NETWORK)).build();
    shadowNetworkInfo.setConnectionStatus(/* isConnected= */ false);

    assertThat(constraintChecker.areConstraintsSatisfied(job)).isFalse();
  }

  @Test
  public void testAreConstraintsSatisfied_anyNetworkRequired_unsatisfied_nullNetworkInfo() {
    JobInvocation job =
        jobBuilder.setConstraints(Constraint.uncompact(Constraint.ON_ANY_NETWORK)).build();
    shadowConnectivityManager.setActiveNetworkInfo(null);

    assertThat(constraintChecker.areConstraintsSatisfied(job)).isFalse();
  }

  @Test
  public void testAreConstraintsSatisfied_unmeteredNetworkRequired_satisfied() {
    JobInvocation job =
        jobBuilder.setConstraints(Constraint.uncompact(Constraint.ON_UNMETERED_NETWORK)).build();

    shadowNetworkInfo.setConnectionStatus(/* isConnected= */ true);
    setNetworkMetered(false);

    assertThat(constraintChecker.areConstraintsSatisfied(job)).isTrue();
  }

  @Test
  public void
      testAreConstraintsSatisfied_unmeteredNetworkRequired_unsatisfied_networkDisconnected() {
    JobInvocation job =
        jobBuilder.setConstraints(Constraint.uncompact(Constraint.ON_UNMETERED_NETWORK)).build();

    shadowNetworkInfo.setConnectionStatus(/* isConnected= */ false);
    setNetworkMetered(false);

    assertThat(constraintChecker.areConstraintsSatisfied(job)).isFalse();
  }

  @Test
  public void testAreConstraintsSatisfied_unmeteredNetworkRequired_unsatisfied_networkMetered() {
    JobInvocation job =
        jobBuilder.setConstraints(Constraint.uncompact(Constraint.ON_UNMETERED_NETWORK)).build();

    shadowNetworkInfo.setConnectionStatus(/* isConnected= */ true);
    setNetworkMetered(true);

    assertThat(constraintChecker.areConstraintsSatisfied(job)).isFalse();
  }

  @Test
  public void testAreConstraintsSatisfied_nonNetworkConstraint() {
    JobInvocation job =
        jobBuilder.setConstraints(Constraint.uncompact(Constraint.DEVICE_IDLE)).build();
    assertThat(constraintChecker.areConstraintsSatisfied(job)).isTrue();
  }

  @Test
  public void testAreConstraintsSatisfied_nonNetworkConstraints() {
    JobInvocation job =
        jobBuilder
            .setConstraints(
                Constraint.uncompact(Constraint.DEVICE_IDLE | Constraint.DEVICE_CHARGING))
            .build();
    assertThat(constraintChecker.areConstraintsSatisfied(job)).isTrue();
  }

  @Test
  public void
      testAreConstraintsSatisfied_anyNetworkRequired_satisfied_includesNonNetworkConstraints() {
    JobInvocation job =
        jobBuilder
            .setConstraints(
                Constraint.uncompact(
                    Constraint.DEVICE_IDLE
                        | Constraint.DEVICE_CHARGING
                        | Constraint.ON_ANY_NETWORK))
            .build();
    shadowNetworkInfo.setConnectionStatus(/* isConnected= */ true);

    assertThat(constraintChecker.areConstraintsSatisfied(job)).isTrue();
  }
}
