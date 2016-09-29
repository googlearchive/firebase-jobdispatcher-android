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

import com.firebase.jobdispatcher.JobParameters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

public class JobStore extends ArrayList<JobHistory> {

  private final ArrayList<OnChangeListener> mListeners = new ArrayList<>();

  public interface OnChangeListener {
    void onChange();
  }

  public void addOnChangeListener(OnChangeListener listener) {
    mListeners.add(listener);
  }

  public void removeOnChangeListener(OnChangeListener listener) {
    synchronized (mListeners) {
      final Iterator<OnChangeListener> it = mListeners.iterator();
      while (it.hasNext()) {
        OnChangeListener candidate = it.next();

        if (candidate.equals(listener)) {
          it.remove();
          break;
        }
      }
    }
  }

  private void notifyChange() {
    for (OnChangeListener listener : mListeners) {
      listener.onChange();
    }
  }

  public synchronized void recordResult(JobParameters j, int result) {
    for (JobHistory jh : this) {
      if (jh.job.getTag().equals(j.getTag()) && jh.job.getService().equals(j.getService())) {
        jh.recordResult(result);
        return;
      }
    }

    JobHistory jh = new JobHistory(j);
    jh.recordResult(result);
    add(jh);
  }

  @Override
  public boolean add(JobHistory object) {
    boolean res = super.add(object);

    notifyChange();

    return res;
  }

  @Override
  public void add(int index, JobHistory object) {
    super.add(index, object);

    notifyChange();
  }

  @Override
  public boolean addAll(Collection<? extends JobHistory> collection) {
    boolean res = super.addAll(collection);

    notifyChange();

    return res;
  }

  @Override
  public boolean addAll(int index, Collection<? extends JobHistory> collection) {
    boolean res = super.addAll(index, collection);

    notifyChange();

    return res;
  }

  @Override
  public void clear() {
    super.clear();

    notifyChange();
  }

  @Override
  public JobHistory remove(int index) {
    final JobHistory job = super.remove(index);

    notifyChange();

    return job;
  }

  @Override
  public boolean remove(Object object) {
    final boolean res = super.remove(object);

    notifyChange();

    return res;
  }

  @Override
  public JobHistory set(int index, JobHistory object) {
    final JobHistory job = super.set(index, object);

    notifyChange();

    return job;
  }
}
