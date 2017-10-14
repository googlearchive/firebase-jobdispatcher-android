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

import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobTrigger;
import com.firebase.jobdispatcher.JobTrigger.ContentUriTrigger;
import com.firebase.jobdispatcher.ObservedUri;

/** An Activity that shows details on the associated Job. */
public class JobDetailActivity extends AppCompatActivity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final Bundle extras = getIntent().getExtras();
    if (extras == null) {
      throw new IllegalArgumentException("Expected Bundle of extras, got null");
    }
    int pos = extras.getInt("pos", -1);
    if (pos == -1) {
      throw new IllegalArgumentException("Expected pos to be present, was absent");
    }

    final JobParameters job = CentralContainer.getStore(getApplicationContext()).get(pos).job;
    if (job == null) {
      throw new IllegalArgumentException("Expected pos to represent a Job");
    }

    getSupportActionBar().setTitle(job.getTag());
    setContentView(createViewForJob(job));
  }

  private View createViewForJob(JobParameters job) {
    TableLayout tableLayout = new TableLayout(this);
    addRow(tableLayout, "TAG = " + job.getTag());
    addRow(tableLayout, "SERVICE = " + job.getService());
    if (job.getTriggerReason() != null
        && job.getTrigger() instanceof JobTrigger.ContentUriTrigger) {
      ContentUriTrigger trigger = (ContentUriTrigger) job.getTrigger();

      addRow(tableLayout, "OBSERVED URIs = ");
      for (ObservedUri uri : trigger.getUris()) {
        addRow(
            tableLayout,
            "URI = " + uri.getUri() + ", flags = " + Integer.toBinaryString(uri.getFlags()));
      }
      addRow(tableLayout, "TRIGGERED URIs = ");
      for (Uri uri : job.getTriggerReason().getTriggeredContentUris()) {
        addRow(tableLayout, uri.toString());
      }
    }
    ScrollView scrollView = new ScrollView(this);
    scrollView.addView(tableLayout);
    return scrollView;
  }

  private void addRow(TableLayout tableLayout, String text) {
    TableRow tableRow = new TableRow(this);
    TextView textView = new TextView(this);
    textView.setText(text);
    tableRow.addView(textView);
    tableLayout.addView(tableRow);
  }
}
