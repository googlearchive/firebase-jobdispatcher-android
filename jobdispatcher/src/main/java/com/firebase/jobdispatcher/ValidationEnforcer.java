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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import java.util.List;

/** Wraps a JobValidator and provides helpful validation utilities. */
public class ValidationEnforcer implements JobValidator {
  private final JobValidator validator;

  public ValidationEnforcer(@NonNull JobValidator validator) {
    this.validator = validator;
  }

  @Nullable
  @Override
  public List<String> validate(@NonNull JobParameters job) {
    return validator.validate(job);
  }

  @Nullable
  @Override
  public List<String> validate(@NonNull JobTrigger trigger) {
    return validator.validate(trigger);
  }

  @Nullable
  @Override
  public List<String> validate(@NonNull RetryStrategy retryStrategy) {
    return validator.validate(retryStrategy);
  }

  /** Indicates whether the provided JobParameters is valid. */
  public final boolean isValid(@NonNull JobParameters job) {
    return validate(job) == null;
  }

  /** Indicates whether the provided JobTrigger is valid. */
  public final boolean isValid(@NonNull JobTrigger trigger) {
    return validate(trigger) == null;
  }

  /** Indicates whether the provided RetryStrategy is valid. */
  public final boolean isValid(@NonNull RetryStrategy retryStrategy) {
    return validate(retryStrategy) == null;
  }

  /**
   * Throws a RuntimeException if the provided JobParameters is invalid.
   *
   * @throws ValidationException
   */
  public final void ensureValid(@NonNull JobParameters job) {
    ensureNoErrors(validate(job));
  }

  /**
   * Throws a RuntimeException if the provided JobTrigger is invalid.
   *
   * @throws ValidationException
   */
  public final void ensureValid(@NonNull JobTrigger trigger) {
    ensureNoErrors(validate(trigger));
  }

  /**
   * Throws a RuntimeException if the provided RetryStrategy is invalid.
   *
   * @throws ValidationException
   */
  public final void ensureValid(@NonNull RetryStrategy retryStrategy) {
    ensureNoErrors(validate(retryStrategy));
  }

  private static void ensureNoErrors(List<String> errors) {
    if (errors != null) {
      throw new ValidationException("JobParameters is invalid", errors);
    }
  }

  /** An Exception thrown when a validation error is encountered. */
  public static final class ValidationException extends RuntimeException {
    private final List<String> errors;

    public ValidationException(@NonNull String msg, @NonNull List<String> errors) {
      super(msg + ": " + TextUtils.join("\n  - ", errors));
      this.errors = errors;
    }

    @NonNull
    public List<String> getErrors() {
      return errors;
    }
  }
}
