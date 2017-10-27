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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import com.firebase.jobdispatcher.FirebaseJobDispatcher.ScheduleResult;

/**
 * GooglePlayDriver provides an implementation of Driver for devices with Google Play services
 * installed. This backend does not do any availability checks and any uses should be guarded with a
 * call to {@code GoogleApiAvailability#isGooglePlayServicesAvailable(android.content.Context)}
 *
 * @see <a
 *     href="https://developers.google.com/android/reference/com/google/android/gms/common/GoogleApiAvailability#isGooglePlayServicesAvailable(android.content.Context)">GoogleApiAvailability</a>
 */
public final class GooglePlayDriver implements Driver {
  static final String BACKEND_PACKAGE = "com.google.android.gms";
  private static final String ACTION_SCHEDULE = "com.google.android.gms.gcm.ACTION_SCHEDULE";

  private static final String BUNDLE_PARAM_SCHEDULER_ACTION = "scheduler_action";
  private static final String BUNDLE_PARAM_TAG = "tag";
  private static final String BUNDLE_PARAM_TOKEN = "app";
  private static final String BUNDLE_PARAM_COMPONENT = "component";

  private static final String SCHEDULER_ACTION_SCHEDULE_TASK = "SCHEDULE_TASK";
  private static final String SCHEDULER_ACTION_CANCEL_TASK = "CANCEL_TASK";
  private static final String SCHEDULER_ACTION_CANCEL_ALL = "CANCEL_ALL";
  private static final String INTENT_PARAM_SOURCE = "source";
  private static final String INTENT_PARAM_SOURCE_VERSION = "source_version";

  private static final int JOB_DISPATCHER_SOURCE_CODE = 1 << 3;
  private static final int JOB_DISPATCHER_SOURCE_VERSION_CODE = 1;

  private final JobValidator validator;
  /** The application Context. Used to send broadcasts. */
  private final Context context;
  /**
   * A PendingIntent from this package. Passed inside the broadcast so the receiver can verify the
   * sender's package.
   */
  private final PendingIntent token;
  /** Turns Jobs into Bundles. */
  private final GooglePlayJobWriter writer;
  /**
   * This is hardcoded to true to avoid putting an unnecessary dependency on the Google Play
   * services library.
   */
  // TODO: this is an unsatisfying solution
  private final boolean available = true;

  /** Instantiates a new GooglePlayDriver. */
  public GooglePlayDriver(Context context) {
    this.context = context;
    token = PendingIntent.getBroadcast(context, 0, new Intent(), 0);
    writer = new GooglePlayJobWriter();
    validator = new DefaultJobValidator(context);
  }

  @Override
  public boolean isAvailable() {
    return available;
  }

  /** Schedules the provided Job. */
  @Override
  @ScheduleResult
  public int schedule(@NonNull Job job) {
    // Stop the job if it's running first, before we send the broadcast
    GooglePlayReceiver.onSchedule(job);

    context.sendBroadcast(createScheduleRequest(job));

    return FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS;
  }

  @Override
  public int cancel(@NonNull String tag) {
    context.sendBroadcast(createCancelRequest(tag));

    return FirebaseJobDispatcher.CANCEL_RESULT_SUCCESS;
  }

  @Override
  public int cancelAll() {
    context.sendBroadcast(createBatchCancelRequest());

    return FirebaseJobDispatcher.CANCEL_RESULT_SUCCESS;
  }

  @NonNull
  protected Intent createCancelRequest(@NonNull String tag) {
    Intent cancelReq = createSchedulerIntent(SCHEDULER_ACTION_CANCEL_TASK);
    cancelReq.putExtra(BUNDLE_PARAM_TAG, tag);
    cancelReq.putExtra(BUNDLE_PARAM_COMPONENT, new ComponentName(context, getReceiverClass()));
    return cancelReq;
  }

  @NonNull
  protected Intent createBatchCancelRequest() {
    Intent cancelReq = createSchedulerIntent(SCHEDULER_ACTION_CANCEL_ALL);
    cancelReq.putExtra(BUNDLE_PARAM_COMPONENT, new ComponentName(context, getReceiverClass()));
    return cancelReq;
  }

  @NonNull
  protected Class<GooglePlayReceiver> getReceiverClass() {
    return GooglePlayReceiver.class;
  }

  @NonNull
  @Override
  public JobValidator getValidator() {
    return validator;
  }

  @NonNull
  private Intent createScheduleRequest(JobParameters job) {
    Intent scheduleReq = createSchedulerIntent(SCHEDULER_ACTION_SCHEDULE_TASK);
    scheduleReq.putExtras(writer.writeToBundle(job, scheduleReq.getExtras()));
    return scheduleReq;
  }

  @NonNull
  private Intent createSchedulerIntent(String schedulerAction) {
    Intent scheduleReq = new Intent(ACTION_SCHEDULE);

    scheduleReq.setPackage(BACKEND_PACKAGE);
    scheduleReq.putExtra(BUNDLE_PARAM_SCHEDULER_ACTION, schedulerAction);
    scheduleReq.putExtra(BUNDLE_PARAM_TOKEN, token);
    scheduleReq.putExtra(INTENT_PARAM_SOURCE, JOB_DISPATCHER_SOURCE_CODE);
    scheduleReq.putExtra(INTENT_PARAM_SOURCE_VERSION, JOB_DISPATCHER_SOURCE_VERSION_CODE);

    return scheduleReq;
  }
}
