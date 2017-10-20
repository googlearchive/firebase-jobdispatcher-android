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

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.testapp.JobStore.OnChangeListener;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** An adapter between a JobStore and the list view that displays its contents. */
public class JobStoreAdapter extends ArrayAdapter<JobHistory> implements OnChangeListener {
  private final JobStore mStore;
  private static final String CELL_FORMAT = "tag = %s, endpoint = %s, uris = %s";

  @Override
  public View getView(final int position, View convertView, ViewGroup parent) {
    final Context ctx = getContext();

    TextView textView = new TextView(parent.getContext());
    JobParameters job = getItem(position).job;
    List<Uri> uris =
        job.getTriggerReason() != null
            ? job.getTriggerReason().getTriggeredContentUris()
            : Collections.<Uri>emptyList();
    textView.setText(String.format(Locale.US, CELL_FORMAT, job.getTag(), job.getService(), uris));

    textView.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            Intent i = new Intent(ctx, JobDetailActivity.class);
            i.putExtra("pos", position);
            ctx.startActivity(i);
          }
        });

    return textView;
  }

  public JobStoreAdapter(Context ctx, JobStore store) {
    super(ctx, android.R.layout.simple_list_item_1, store);

    mStore = store;
    mStore.addOnChangeListener(this);
  }

  @Override
  public void onChange() {
    notifyDataSetChanged();
  }
}
