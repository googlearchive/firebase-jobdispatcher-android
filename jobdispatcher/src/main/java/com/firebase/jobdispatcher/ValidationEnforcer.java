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

  public ValidationEnforcer(JobValidator validator) {
    this.validator = validator;
  }

  /** {@inheritDoc} */
  @Nullable
  @Override
  public List<String> validate(JobParameters job) {
    return validator.validate(job);
  }

  /**
   * {@inheritDoc}
   *
   * @param trigger
   */
  @Nullable
  @Override
  public List<String> validate(JobTrigger trigger) {
    return validator.validate(trigger);
  }

  /** {@inheritDoc} */
  @Nullable
  @Override
  public List<String> validate(RetryStrategy retryStrategy) {
    return validator.validate(retryStrategy);
  }

  /** Indicates whether the provided JobParameters is valid. */
  public final boolean isValid(JobParameters job) {
    return validate(job) == null;
  }

  /** Indicates whether the provided JobTrigger is valid. */
  public final boolean isValid(JobTrigger trigger) {
    return validate(trigger) == null;
  }

  /** Indicates whether the provided RetryStrategy is valid. */
  public final boolean isValid(RetryStrategy retryStrategy) {
    return validate(retryStrategy) == null;
  }

  /**
   * Throws a RuntimeException if the provided JobParameters is invalid.
   *
   * @throws ValidationException
   */
  public final void ensureValid(JobParameters job) {
    ensureNoErrors(validate(job));
  }

  /**
   * Throws a RuntimeException if the provided JobTrigger is invalid.
   *
   * @throws ValidationException
   */
  public final void ensureValid(JobTrigger trigger) {
    ensureNoErrors(validate(trigger));
  }

  /**
   * Throws a RuntimeException if the provided RetryStrategy is invalid.
   *
   * @throws ValidationException
   */
  public final void ensureValid(RetryStrategy retryStrategy) {
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

    public ValidationException(String msg, @NonNull List<String> errors) {
      super(msg + ": " + TextUtils.join("\n  - ", errors));
      this.errors = errors;
    }

    public List<String> getErrors() {
      return errors;
    }
  }
}
