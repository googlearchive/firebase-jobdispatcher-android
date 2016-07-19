# Firebase JobDispatcher [![Build Status][ci-badge]][ci-link]

[ci-badge]: https://travis-ci.org/firebase/firebase-jobdispatcher-android.svg?branch=master
[ci-link]: https://travis-ci.org/firebase/firebase-jobdispatcher-android

The Firebase JobDispatcher is a library for working with the [JobScheduler][] compatibility library
in Google Play services. This replaces the old [GCM Network Manager][nts] library.

## Overview

### What's a [JobScheduler][]?

The JobScheduler is an Android system service available on API levels 21 (Lollipop)+. It provides an
API for scheduling units of work (represented by [`JobService`][JobService] subclasses) that will be
executed in your app's process.

### Why is this better than whatever I'm doing now?

Running apps in the background is expensive, which is especially harmful when they're not actively
doing work that's important to the user. That problem is multiplied when those background services
are listening for frequently sent broadcasts (`android.net.conn.CONNECTIVITY_CHANGE` and
`android.hardware.action.NEW_PICTURE` are common examples). Even worse, there's no way of specifying
prerequisites for these broadcasts. Listening for `CONNECTIVITY_CHANGE` broadcasts does not
guarantee that the device has an active network connection, only that the connection was recently
changed.

In recognition of these issues, the Android framework team created the [JobScheduler][]. This
provides developers a simple way of specifying runtime constraints on their jobs. Available
constraints include [network type][js-network-type], [charging state][js-charging-state], and
[idle state][js-idle-state].

This library uses the scheduling engine inside [Google Play services] (formerly the
[GCM Network Manager][nts] component) to provide a backwards compatible (back to Gingerbread)
[JobScheduler][]-like API.

This I/O presentation has more information on why background services are harmful and what you can
do about them:

[![Android battery and memory optimizations][io-video-img]][io-video-link]

There's more information on upcoming changes to Android's approach to background services on the
[Android developer preview page][n-preview-bg-optimizations].

[n-preview-bg-optimizations]: https://developer.android.com/preview/features/background-optimization.html
[io-video-img]: http://img.youtube.com/vi/VC2Hlb22mZM/hqdefault.jpg
[io-video-link]: https://youtu.be/VC2Hlb22mZM
[js-network-type]: https://developer.android.com/reference/android/app/job/JobInfo.Builder.html#setRequiredNetworkType(int)
[js-charging-state]: https://developer.android.com/reference/android/app/job/JobInfo.Builder.html#setRequiresCharging(boolean)
[js-idle-state]: https://developer.android.com/reference/android/app/job/JobInfo.Builder.html#setRequiresDeviceIdle(boolean)

### Requirements

The FirebaseJobDispatcher currently relies on the scheduling component in Google Play services.
Because of that, it won't work on environments without Google Play services installed.

### Comparison to other libraries

| Library                    | Minimum API | Requires Google Play   | Service API<sup>[1](#fn1)</sup> | Custom retry strategies |
| -------------------------- | ----------- | ---------------------- | ------------------------------- | ----------------------- |
| Framework [JobScheduler][] | 21          | No                     | JobScheduler                    | Yes                     |
| Firebase JobDispatcher     | 9           | Yes                    | JobScheduler                    | Yes                     |
| [evernote/android-job][]   | 9 / 14      | No<sup>[2](#fn2)</sup> | Custom                          | Yes<sup>[3](#fn3)</sup> |

<a name="fn1">1</a>: Refers to the methods that need to be implemented in the Service subclass.  
<a name="fn2">2</a>: Uses AlarmManager to support API levels >= 14 if Google Play services is unavailable  
<a name="fn3">3</a>: Supported for the AlarmManager and JobScheduler drivers.

## Quick start

There are two flavors for this library. Most users will want the default ("thick") version, but if
you have a dependency on `com.google.android.gms:play-services-gcm` then you'll need to use the
"thin" version.

NOTE: [Firebase Cloud Messaging][fcm] (`com.google.firebase:firebase-messaging`) users should use
the default ("thick") version.

### Installation

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
cp jobdispatcher/build/outputs/aar/jobdispatcher-thick-release.aar /my/target/directory
```

### Usage

#### Writing a new JobService

The simplest possible `JobService`:

```java
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;

public class MyJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters job) {
        // Do some work here

        return false; // Answers the question: "Is there still work going on?"
    }

    @Override
    public boolean onStopJob(JobParameters job) {
        return false; // Answers the question: "Should this job be retried?"
    }
}
```

#### Adding it to the manifest

```xml
<service
    android:exported="false"
    android:name=".MyJobService">
    <intent-filter>
        <action android:name="com.firebase.jobdispatcher.ACTION_EXECUTE"/>"
    </intent-filter>
</service>
```

#### Creating a Dispatcher

```java
// Create a new dispatcher using the Google Play driver.
FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(context));
```

#### Scheduling a simple job

```java
Job myJob = dispatcher.newJobBuilder()
    .setService(MyJobService.class) // the JobService that will be called
    .setTag("my-unique-tag")        // uniquely identifies the job
    .build();

dispatcher.mustSchedule(myJob);
```

#### Scheduling a more complex job

```java
Bundle myExtrasBundle = new Bundle();
myExtrasBundle.putString("some_key", "some_value");

Job myJob = dispatcher.newJobBuilder()
    .setService(MyJobService.class)                      // the JobService that will be called
    .setTag("my-unique-tag")                             // uniquely identifies the job
    .setRecurring(false)                                 // one-off job
    .setLifetime(Lifetime.UNTIL_NEXT_BOOT)               // don't persist past a device reboot
    .setTrigger(Trigger.executionWindow(0, 60))          // start between 0 and 60 seconds from now
    .setReplaceCurrent(false)                            // don't overwrite an existing job
    .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL) // retry with exponential backoff
    .setConstraints(
        Constraint.ON_UNMETERED_NETWORK,                 // only run on an unmetered network
        Constraint.DEVICE_CHARGING                       // only run when the device is charging
    )
    .setExtras(myExtrasBundle)
    .build();

dispatcher.mustSchedule(myJob);
```

#### Cancelling a job

```java
dispatcher.cancel("my-unique-tag");
```

#### Cancelling all jobs

```java
dispatcher.cancelAll();
```

<!--
## Next steps

- Browse the [API documentation][]

[API documentation]: TODO: put link here

-->

## Contributing

See the [CONTRIBUTING.md](CONTRIBUTING.md) file.

## Support

This library is actively supported by Google engineers. If you encounter any problems, please create
an issue in our [tracker][].

# License

Apache, see the [LICENSE](LICENSE) file.

[tracker]: https://github.com/firebase/firebase-jobdispatcher-android/issues
[nts]: https://developers.google.com/cloud-messaging/network-manager
[fcm]: https://firebase.google.com/docs/cloud-messaging/
[JobService]: https://developer.android.com/reference/android/app/job/JobService.html
[JobScheduler]: https://developer.android.com/reference/android/app/job/JobScheduler.html
[Google Play services]: https://developers.google.com/android/guides/overview
[evernote/android-job]: https://github.com/evernote/android-job
