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

package com.firebase.jobdispatcher.testapp;

import android.databinding.DataBindingUtil;
import android.databinding.ViewDataBinding;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatButton;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import com.firebase.jobdispatcher.Constraint;
import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job.Builder;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.ObservedUri;
import com.firebase.jobdispatcher.Trigger;
import java.util.Arrays;

/** An Activity that shows a form UI allowing users to define a custom job. */
public class JobFormActivity extends AppCompatActivity {

  public static final String TAG = "FJD.TEST_APP";

  enum TriggerType {
    NOW_TRIGGER,
    TIMED_TRIGGER,
    CONTENT_URI_TRIGGER;

    public static TriggerType getByPosition(int position) {
      if (position < 0 || position >= TriggerType.values().length) {
        return null;
      }
      return TriggerType.values()[position];
    }
  }

  private JobForm form = new JobForm();
  private Spinner uriSpinner;
  private Spinner triggerSpinner;

  private final Uri[] uris =
      new Uri[] {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        ContactsContract.AUTHORITY_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_job_form);

    final ViewDataBinding binding =
        DataBindingUtil.setContentView(this, R.layout.activity_job_form);
    binding.setVariable(com.firebase.jobdispatcher.testapp.BR.form, form);

    View.OnClickListener onScheduleButtonClickListener =
        new ScheduleButtonClickListener(
            form, new FirebaseJobDispatcher(new GooglePlayDriver(this)));

    AppCompatButton scheduleButton = (AppCompatButton) findViewById(R.id.schedule_button);
    assert scheduleButton != null;

    scheduleButton.setOnClickListener(onScheduleButtonClickListener);

    uriSpinner = setUpSpinner(R.id.uri_spinner, R.array.uri_array);

    triggerSpinner = setUpSpinner(R.id.trigger_spinner, R.array.trigger_array);

    final LinearLayout timePanel = (LinearLayout) findViewById(R.id.timePanel);
    timePanel.setVisibility(View.GONE);

    final LinearLayout uriPanel = (LinearLayout) findViewById(R.id.uriPanel);
    uriPanel.setVisibility(View.GONE);
    triggerSpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            switch (TriggerType.getByPosition(position)) {
              case TIMED_TRIGGER:
                timePanel.setVisibility(View.VISIBLE);
                uriPanel.setVisibility(View.GONE);
                break;
              case CONTENT_URI_TRIGGER:
                timePanel.setVisibility(View.GONE);
                uriPanel.setVisibility(View.VISIBLE);
                break;
              case NOW_TRIGGER:
                timePanel.setVisibility(View.GONE);
                uriPanel.setVisibility(View.GONE);
                break;
              default:
                Log.e(TAG, "Unknown trigger was selected.");
                break;
            }
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
  }

  private Spinner setUpSpinner(int id, int arrayId) {
    Spinner spinner = (Spinner) findViewById(id);
    ArrayAdapter<CharSequence> adapter =
        ArrayAdapter.createFromResource(this, arrayId, android.R.layout.simple_spinner_item);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    return spinner;
  }

  private class ScheduleButtonClickListener implements View.OnClickListener {

    private final JobForm form;
    private final FirebaseJobDispatcher jobDispatcher;

    ScheduleButtonClickListener(JobForm form, FirebaseJobDispatcher dispatcher) {
      this.form = form;
      this.jobDispatcher = dispatcher;
    }

    @SuppressWarnings("WrongConstant")
    @Override
    public void onClick(View v) {
      final Builder builder =
          jobDispatcher
              .newJobBuilder()
              .setTag(form.tag.get())
              .setRecurring(form.recurring.get())
              .setLifetime(form.persistent.get() ? Lifetime.FOREVER : Lifetime.UNTIL_NEXT_BOOT)
              .setService(DemoJobService.class)
              .setReplaceCurrent(form.replaceCurrent.get())
              .setRetryStrategy(
                  jobDispatcher.newRetryStrategy(
                      form.retryStrategy.get(),
                      form.getInitialBackoffSeconds(),
                      form.getMaximumBackoffSeconds()));

      if (form.constrainDeviceCharging.get()) {
        builder.addConstraint(Constraint.DEVICE_CHARGING);
      }
      if (form.constrainOnAnyNetwork.get()) {
        builder.addConstraint(Constraint.ON_ANY_NETWORK);
      }
      if (form.constrainOnUnmeteredNetwork.get()) {
        builder.addConstraint(Constraint.ON_UNMETERED_NETWORK);
      }

      int selectedTriggerPosition = triggerSpinner.getSelectedItemPosition();
      switch (TriggerType.getByPosition(selectedTriggerPosition)) {
        case NOW_TRIGGER:
          builder.setTrigger(Trigger.NOW);
          break;
        case TIMED_TRIGGER:
          builder.setTrigger(
              Trigger.executionWindow(form.getWinStartSeconds(), form.getWinEndSeconds()));
          break;
        case CONTENT_URI_TRIGGER:
          Uri uri = uris[uriSpinner.getSelectedItemPosition()];
          CheckBox notifyForDescendants = (CheckBox) findViewById(R.id.notify_for_descendants);
          int flags =
              notifyForDescendants.isChecked() ? ObservedUri.Flags.FLAG_NOTIFY_FOR_DESCENDANTS : 0;
          ObservedUri observedUri = new ObservedUri(uri, flags);
          builder.setTrigger(Trigger.contentUriTrigger(Arrays.asList(observedUri)));
          break;
        default:
          Log.e(TAG, "Unknown trigger was selected.");
          break;
      }
      Log.i("FJD.JobForm", "scheduling new job");
      jobDispatcher.mustSchedule(builder.build());

      JobFormActivity.this.finish();
    }
  }
}
