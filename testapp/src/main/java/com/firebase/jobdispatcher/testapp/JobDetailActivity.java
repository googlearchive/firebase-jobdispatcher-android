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

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import com.firebase.jobdispatcher.JobParameters;

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
    final TableRow tagRow = new TableRow(this);
    TextView tagTxt = new TextView(this);
    tagTxt.setText("TAG = " + job.getTag());
    tagRow.addView(tagTxt);

    final TableRow serviceRow = new TableRow(this);
    TextView serviceTxt = new TextView(this);
    serviceTxt.setText("SERVICE = " + job.getService());
    serviceRow.addView(serviceTxt);

    final TableLayout layout = new TableLayout(this);
    layout.addView(tagRow);
    layout.addView(serviceRow);
    return layout;
  }
}
