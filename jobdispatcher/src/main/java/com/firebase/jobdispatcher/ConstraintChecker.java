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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.v4.net.ConnectivityManagerCompat;
import android.util.Log;
import com.firebase.jobdispatcher.Constraint.JobConstraint;

/** Class responsible for verifying that job constraints are satisfied. */
class ConstraintChecker {

  /** Logging tag. */
  /* package */ static final String TAG = "FJD.ConstraintChecker";

  private final Context context;

  /**
   * Constructs a new ConstraintChecker
   *
   * @param context Android Application Context object.
   */
  /* package */ ConstraintChecker(Context context) {
    this.context = context;
  }

  /**
   * Returns true iff all the specified job constraints are satisfied. Note: At the moment this
   * method only checks for network constraints and nothing more.
   *
   * @param job the job whose constraints are to be checked.
   */
  public boolean areConstraintsSatisfied(JobInvocation job) {

    int jobConstraints = Constraint.compact(job.getConstraints());
    return areNetworkConstraintsSatisfied(jobConstraints);
  }

  /**
   * Returns true if the specified jobConstraints are satisfied. We only check whether network
   * constraints are satisfied. All other constraints are assumed to be satisfied.
   */
  private boolean areNetworkConstraintsSatisfied(@JobConstraint int jobConstraints) {

    // Network constraints are always satisfied for jobs that don't need a network
    if (!wantsNetwork(jobConstraints)) {
      return true;
    }

    // Ensure basic network connectivity is available.
    ConnectivityManager connectivityManager =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (!isNetworkConnected(connectivityManager)) {
      return false;
    }

    // Note: Constraint.ON_ANY_NETWORK and Constraint.ON_UNMETERED_NETWORK are mutually exclusive.
    // Constraints satisfied if we don't need an unmetered network (as that implies any network is
    // OK) or current network is unmetered.
    return !wantsUnmeteredNetwork(jobConstraints) || isNetworkUnmetered(connectivityManager);
  }

  /** Returns true if any of the given {@code jobConstraints} require a network. */
  private static boolean wantsNetwork(@JobConstraint int jobConstraints) {
    return wantsAnyNetwork(jobConstraints) || wantsUnmeteredNetwork(jobConstraints);
  }

  /** Returns true if any of the given {@code jobConstraints} require an unmetered network. */
  private static boolean wantsUnmeteredNetwork(@JobConstraint int jobConstraints) {
    return (jobConstraints & Constraint.ON_UNMETERED_NETWORK) != 0;
  }

  /**
   * Returns true if any of the given {@code jobConstraints} is set to {@code
   * Constraint.ON_ANY_NETWORK}
   */
  private static boolean wantsAnyNetwork(@JobConstraint int jobConstraints) {
    return (jobConstraints & Constraint.ON_ANY_NETWORK) != 0;
  }

  /**
   * Returns true is network is connected (i.e. can pass data) based on the available network
   * information.
   */
  private static boolean isNetworkConnected(ConnectivityManager connectivityManager) {
    NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
    if (networkInfo == null) {
      // When network information is unavailable, we conservatively
      // assume network is inaccessible.
      Log.i(TAG, "NetworkInfo null, assuming network inaccessible");
      return false;
    } else {
      return networkInfo.isConnected();
    }
  }

  /** Returns true if the currently active network is unmetered. */
  private static boolean isNetworkUnmetered(ConnectivityManager connectivityManager) {
    return !ConnectivityManagerCompat.isActiveNetworkMetered(connectivityManager);
  }
}
