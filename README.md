# Firebase JobDispatcher [![Build Status][ci-badge]][ci-link]

[ci-badge]: https://travis-ci.org/firebase/firebase-jobdispatcher-android.svg?branch=master
[ci-link]: https://travis-ci.org/firebase/firebase-jobdispatcher-android

The Firebase Android JobDispatcher is a library that provides a high-level wrapper around job
scheduling engines on Android, starting with the [GCM Network Manager][nts].

This replaces the old GCM Network Manager library.

## Installation

For now, clone the repo:
```
git clone https://github.com/firebase/firebase-jobdispatcher-android
cd firebase-jobdispatcher-android
```

Build the `aar` bundle:
```
./gradlew aar
```
And copy it to where you need it:
```
cp jobdispatcher/build/outputs/aar/jobdispatcher-release.aar
```

## Concepts

### Job

A Job is a description of a unit of work. At its heart, it's composed of a
series of mandatory attributes:

-   A string tag that (within your app) uniquely identifies the job.

-   A `JobService` subclass that contains the job-specific business logic.

-   A `JobTrigger` that determines whether the Job is ready to run.

As well as a set of optional attributes:

-   A set of `Constraints` that need to be satisfied in order to run the Job.
    The default is the empty set, which signals that the Job will be run as soon
    as the `JobTrigger` is activated.

-   A `RetryStrategy` that specifies how failures should be handled. The default
    is to handle failures using an exponential backoff strategy.

-   A `lifetime` that specifies how long the Job should remain scheduled. The
    default is to keep it until the next boot.

-   An optional `Bundle` of user-supplied extras. The default is an empty
    Bundle.

-   A boolean indicating whether the Job should repeat. The default is false
    (i.e. the Job will be executed once).

-   A boolean indicating whether the Job should replace any pre-existing Job
    with the same tag. The default is false.

### Driver

`Driver` is an interface that represents a component that can schedule, cancel,
and execute Jobs. The only bundled Driver is the `GooglePlayDriver`, which
relies on the scheduler built-in to Google Play services.

### Trigger

A `Trigger` is a "sticky" condition. When a `Trigger` is activated (or
"triggered") it remains triggered until its associated Job is successfully
executed.

There are two currently supported `Triggers`:

-   The `ImmediateTrigger` (`Trigger.NOW`), which is only available on
    non-recurring Jobs, means that the Job should be run as soon as its runtime
    constraints are satisfied.

-   The `ExecutionWindowTrigger` (`Trigger.executionWindow`), which specifies a
    time window in which the Job should be executed. This becomes triggered as
    soon as the window start deadline is reached, and drivers are encouraged to
    run the Job before the window end if possible. Ultimately when the Job is
    executed is decided by the backing driver, so this is more of a suggestion
    than a hard deadline.

### Constraints

Constraints are runtime conditions that need to be met in order to run the Job.

There are three currently supported constraints:

-   `Constraint.ON_ANY_NETWORK` signals that the Job should only be run if the
    device has a working network connection. This should be used for only small
    data transfers.

-   `Constraint.ON_UNMETERED_NETWORK` signals that the Job should only be run
    when the device is connected to an **unmetered** network. This should be
    used for Jobs that require large data transfers.

-   `Constraint.DEVICE_CHARGING` signals that the Job should only be run when
    the device is charging.

## Usage

All access to Jobs is handled through a root `FirebaseJobDispatcher` object,
which wraps a `Driver`. You can create an instance that uses the Google Play
driver like so:

```java
Driver myDriver = new GooglePlayDriver(myContext);
FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(myDriver);
```

Usually you'll want to create a single `FirebaseJobDispatcher` instance that can
be shared throughout your app.

All Jobs are represented by subclasses of
`com.firebase.jobdispatcher.JobService`, which exposes the same end-user API as
the Android framework's [`JobService`][jobservice] class.'. A direct example
might look like so:

```java
public class MyJobService extends JobService {
    private AsyncTask asyncTask;

    @Override
    public boolean onStartJob(JobParameters job) {
        // Begin some async work
        asyncTask = new AsyncTask<Object, Object, Object>() {
            protected Object doInBackground(Object... objects) {
                /* do some work */
            }

            protected void onPostExecute(Object result) {
                jobFinished(job, false /* no need to reschedule, we're done */);
            }
        };

        asyncTask.execute();

        return true; /* Still doing work */
    }

    @Override
    public boolean onStopJob(JobParameters job) {
        asyncTask.cancel();

        return true; /* we're not done, please reschedule */
    }
}
```

Just like any other `Service`, you'll need to register your subclass in your
`AndroidManifest.xml`. **For security reasons it should not be exported**. Make
sure you include the `<intent-filter>` as follows:

```xml
<service android:name=".MyJobService" android:exported="false">
  <intent-filter>
    <action android:name="com.firebase.jobdispatcher.ACTION_EXECUTE"/>
  </intent-filter>
</service>
```

Finally, you can create and schedule your Job:

```java
Job job = dispatcher.newJobBuilder()
    .setService(MyJobService.class)
    .setTag("my-tag")
    .setConstraints(
        Constraint.DEVICE_CHARGING,
        Constraint.ON_UNMETERED_NETWORK)
    .setTrigger(Trigger.NOW)
    .setLifetime(Lifetime.UNTIL_NEXT_BOOT)
    .setRecurring(false)
    .build();

int result = dispatcher.schedule(job);
if (result != FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS) {
    // handle error
}
```

For more usage examples, see the `JobFormActivity` class in the included
`testapp`.

## Contributing

See the [CONTRIBUTING.md](CONTRIBUTING.md) file.

## Support

This library is actively supported by Google engineers. If you encounter any
problems, please create an issue in our [tracker](https://github.com/firebase/firebase-jobdispatcher-android/issues).

# License

Apache, see the [LICENSE](LICENSE) file.

[nts]: https://developers.google.com/cloud-messaging/network-manager
[jobservice]: https://developer.android.com/reference/android/app/job/JobService.html

