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
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatButton;
import android.util.Log;
import android.view.View;
import com.firebase.jobdispatcher.Constraint;
import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job.Builder;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.Trigger;

/** An Activity that shows a form UI allowing users to define a custom job. */
public class JobFormActivity extends AppCompatActivity {

  private JobForm form = new JobForm();

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
              .setTrigger(
                  Trigger.executionWindow(form.getWinStartSeconds(), form.getWinEndSeconds()))
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

      Log.i("FJD.JobForm", "scheduling new job");
      jobDispatcher.mustSchedule(builder.build());

      JobFormActivity.this.finish();
    }
  }
}
