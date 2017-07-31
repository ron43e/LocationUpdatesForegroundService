/**
 * Copyright 2017 Google Inc. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gms.location.sample.locationupdatesforegroundservice;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.location.Location;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;
import java.util.Locale;

import javax.net.ssl.SSLHandshakeException;

/**
 * A bound and started service that is promoted to a foreground service when location updates have
 * been requested and all clients unbind.
 *
 * For apps running in the background on "O" devices, location is computed only once every 10
 * minutes and delivered batched every 30 minutes. This restriction applies even to apps
 * targeting "N" or lower which are run on "O" devices.
 *
 * This sample show how to use a long-running service for location updates. When an activity is
 * bound to this service, frequent location updates are permitted. When the activity is removed
 * from the foreground, the service promotes itself to a foreground service, and location updates
 * continue. When the activity comes back to the foreground, the foreground service stops, and the
 * notification assocaited with that service is removed.
 */
public class LocationUpdatesService extends Service {
  private static final String PACKAGE_NAME =
      "com.google.android.gms.location.sample.locationupdatesforegroundservice";
  private static final String TAG = LocationUpdatesService.class.getSimpleName();
  static final String ACTION_BROADCAST = PACKAGE_NAME + ".broadcast";
  static final String EXTRA_LOCATION = PACKAGE_NAME + ".location";
  private static final String EXTRA_STARTED_FROM_NOTIFICATION = PACKAGE_NAME + ".started_from_notification";
  private final IBinder mBinder = new LocalBinder();
  // The desired interval for location updates. Inexact. Updates may be more or less frequent.
  private static final long UPDATE_INTERVAL_IN_MILLISECONDS = 1000;
  // The fastest rate for active location updates. Updates will never be more frequent than this value.
  private static final long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2;
  // The identifier for the notification displayed for the foreground service.
  private static final int NOTIFICATION_ID = 12345678;
  // Used to check whether the bound activity has really gone away and not unbound as part of an
  // orientation change. We create a foreground service notification only if the former takes place.
  private boolean mChangingConfiguration = false;
  private NotificationManager mNotificationManager;
  // Contains parameters used by {@link com.google.android.gms.location.FusedLocationProviderApi}.
  private LocationRequest mLocationRequest;
  // Provides access to the Fused Location Provider API.
  private FusedLocationProviderClient mFusedLocationClient;
  // Callback for changes in location.
  private LocationCallback mLocationCallback;
  private Handler mServiceHandler;
  // The current location.
  private Location mLocation;     // the current location
  private Location bestLocation;  // best location found
  public static String lastServerResponse;
  public static Calendar runningSince;
  public Calendar stoppedOn;
  private String urlText = "http://stardatesoftware.com/locations/gps.php?";
  public static final String NOTIFICATION = "com.google.android.gms.location.sample.locationupdatesforegroundservice";
  public static boolean isRunning = true;
  private long lastUpdate;    // time of previous update
  private static final int TWO_MINUTES = 1000 * 60 * 2;
	SharedPreferences preferences;
	private String user;
	private static final long GEO_DURATION = 60 * 60 * 1000;
	private static final String GEOFENCE_REQ_ID = "My Geofence";
	private static final float GEOFENCE_RADIUS = 500.0f; // in meters
	private PendingIntent geoFencePendingIntent;
	private final int GEOFENCE_REQ_CODE = 0;



  public LocationUpdatesService() {
  }

  @Override
  public void onCreate() {
    mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

    mLocationCallback = new LocationCallback() {
      @Override
      public void onLocationResult(LocationResult locationResult) {
        super.onLocationResult(locationResult);
        onNewLocation(locationResult.getLastLocation());
      }
    };
    createLocationRequest();
    getLastLocation();
    lastUpdate = System.currentTimeMillis();
    HandlerThread handlerThread = new HandlerThread(TAG);
    handlerThread.start();
    mServiceHandler = new Handler(handlerThread.getLooper());
    mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// set up to be able to read preferences
		preferences = PreferenceManager.getDefaultSharedPreferences(this);
		user = preferences.getString("pref_user_id", "Unknown");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.i(TAG, "Service started");
    boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION,
        false);
    // We got here because the user decided to remove location updates from the notification.
    if (startedFromNotification) {
      removeLocationUpdates();
      stopSelf();
    }
    // Tells the system to not try to recreate the service after it has been killed.
    return START_NOT_STICKY;
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    mChangingConfiguration = true;
  }

  @Override
  public IBinder onBind(Intent intent) {
    // Called when a client (MainActivity in case of this sample) comes to the foreground
    // and binds with this service. The service should cease to be a foreground service
    // when that happens.
    Log.i(TAG, "in onBind()");
    stopForeground(true);
    mChangingConfiguration = false;
    return mBinder;
  }

  @Override
  public void onRebind(Intent intent) {
    // Called when a client (MainActivity in case of this sample) returns to the foreground
    // and binds once again with this service. The service should cease to be a foreground
    // service when that happens.
    Log.i(TAG, "in onRebind()");
    stopForeground(true);
    mChangingConfiguration = false;
    super.onRebind(intent);
  }

  @Override
  public boolean onUnbind(Intent intent) {
    Log.i(TAG, "Last client unbound from service");

    // Called when the last client (MainActivity in case of this sample) unbinds from this
    // service. If this method is called due to a configuration change in MainActivity, we
    // do nothing. Otherwise, we make this service a foreground service.
    if (!mChangingConfiguration && Utils.requestingLocationUpdates(this)) {
      Log.i(TAG, "Starting foreground service");
      startForeground(NOTIFICATION_ID, getNotification());
    }
    return true; // Ensures onRebind() is called when a client re-binds.
  }

  @Override
  public void onDestroy() {
    mServiceHandler.removeCallbacksAndMessages(null);
  }

  /** Makes a request for location updates. Note that in this sample we merely log the
   * {@link SecurityException}.
   */
  public void requestLocationUpdates() {
    Log.i(TAG, "Requesting location updates");
    Utils.setRequestingLocationUpdates(this, true);
    startService(new Intent(getApplicationContext(), LocationUpdatesService.class));
    try {
      mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.myLooper());
    } catch (SecurityException unlikely) {
      Utils.setRequestingLocationUpdates(this, false);
      Log.e(TAG, "Lost location permission. Could not request updates. " + unlikely);
    }
  }

  /** Removes location updates. Note that in this sample we merely log the
   * {@link SecurityException}.
   */
  public void removeLocationUpdates() {
    Log.i(TAG, "Removing location updates");
    try {
      mFusedLocationClient.removeLocationUpdates(mLocationCallback);
      Utils.setRequestingLocationUpdates(this, false);
      stopSelf();
    } catch (SecurityException unlikely) {
      Utils.setRequestingLocationUpdates(this, true);
      Log.e(TAG, "Lost location permission. Could not remove updates. " + unlikely);
    }
  }

  /** Returns the {@link NotificationCompat} used as part of the foreground service.
   *
   * @return Notification
   */
  private Notification getNotification() {
    Intent intent = new Intent(this, LocationUpdatesService.class);
    CharSequence text = Utils.getLocationText(mLocation);
    // Extra to help us figure out if we arrived in onStartCommand via the notification or not.
    intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);
    // The PendingIntent that leads to a call to onStartCommand() in this service.
    PendingIntent servicePendingIntent = PendingIntent.getService(this, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT);
    // The PendingIntent to launch activity.
    PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
        new Intent(this, MainActivity.class), 0);
    return new NotificationCompat.Builder(this)
        .addAction(R.drawable.ic_launch, getString(R.string.launch_activity),
            activityPendingIntent)
        .addAction(R.drawable.ic_cancel, getString(R.string.remove_location_updates),
            servicePendingIntent)
        .setContentText(text)
        .setContentTitle(Utils.getLocationTitle(this))
        .setOngoing(true)
        .setPriority(Notification.PRIORITY_HIGH)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setTicker(text)
        .setWhen(System.currentTimeMillis()).build();
  }

  /** get the last location
   *
   */
  private void getLastLocation() {
    try {
      mFusedLocationClient.getLastLocation()
          .addOnCompleteListener(new OnCompleteListener<Location>() {
            @Override
            public void onComplete(@NonNull Task<Location> task) {
              if (task.isSuccessful() && task.getResult() != null) {
                mLocation = task.getResult();
              } else {
                Log.w(TAG, "Failed to get location.");
              }
            }
          });
    } catch (SecurityException unlikely) {
      Log.e(TAG, "Lost location permission." + unlikely);
    }
  }

  /** there is a new location
   *
   * @param location is the new location
   */
  private void onNewLocation(Location location) {
    Log.i(TAG, "New location: " + location);
    mLocation = location;
    // check to see if this location is the best one
    if (isBetterLocation(location, bestLocation)) {
      // Notify anyone listening for broadcasts about the new location.
      Intent intent = new Intent(ACTION_BROADCAST);
      intent.putExtra(EXTRA_LOCATION, location);
      LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);

      // Update notification content if running as a foreground service.
      if (serviceIsRunningInForeground(this)) {
        mNotificationManager.notify(NOTIFICATION_ID, getNotification());
      }
      // get current time
      long currentTime = location.getTime();
      // send location to website
      new TrackerRequest().start(
          "lat=" + location.getLatitude()
              + "&lon=" + location.getLongitude()
              + "&t=" + location.getTime()
              + "&user=" + user
              + "&tme=" + ((currentTime - lastUpdate) / 1000)
              + "&brg=" + location.getBearing()
              + "&alt=" + Math.floor(location.getAltitude() * 3.28084f)
              + "&acc=" + Math.floor(location.getAccuracy() * 3.28084f)
      );
      lastUpdate = currentTime;
      bestLocation = location;
    }
  }

  // Sets the location request parameters.
  private void createLocationRequest() {
    mLocationRequest = new LocationRequest();
    mLocationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);
    mLocationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);
    mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
  }

  /** calculate time since a certain time
   */
  public StringBuilder printDifference(long startDate, long endDate) {
    StringBuilder sb = new StringBuilder();
    Formatter formatter = new Formatter(sb, Locale.US);
    //milliseconds
    long different = startDate - endDate;
    long secondsInMilli = 1000;
    long minutesInMilli = secondsInMilli * 60;
    long hoursInMilli = minutesInMilli * 60;
    long daysInMilli = hoursInMilli * 24;
    long elapsedDays = different / daysInMilli;
    different = different % daysInMilli;
    long elapsedHours = different / hoursInMilli;
    different = different % hoursInMilli;
    long elapsedMinutes = different / minutesInMilli;
    different = different % minutesInMilli;
    long elapsedSeconds = different / secondsInMilli;
    if (elapsedDays > 0) {
      formatter.format("%d days ago", elapsedDays);
    } else if (elapsedHours > 0) {
      formatter.format("%d hours ago", elapsedHours);
    } else if (elapsedMinutes > 0) {
      formatter.format("%d minutes %d secs", elapsedMinutes, elapsedSeconds);
    } else {
      formatter.format("'%d seconds ago'", elapsedSeconds);
    }
    return sb;
  }

  /** Class used for the client Binder.  Since this service runs in the same process as its
   *   clients, we don't need to deal with IPC.
   */
  public class LocalBinder extends Binder {
    LocationUpdatesService getService() {
      return LocationUpdatesService.this;
    }
  }

  // Returns true if this is a foreground service.
  public boolean serviceIsRunningInForeground(Context context) {
    ActivityManager manager = (ActivityManager) context.getSystemService(
        Context.ACTIVITY_SERVICE);
    for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(
        Integer.MAX_VALUE)) {
      if (getClass().getName().equals(service.service.getClassName())) {
        if (service.foreground) {
          return true;
        }
      }
    }
    return false;
  }

  /** Determines whether one Location reading is better than the current Location fix
   * @param location  The new Location that you want to evaluate
   * @param currentBestLocation  The current Location fix, to which you want to compare the new one
   * from: https://developer.android.com/guide/topics/location/strategies.html
   */
  protected boolean isBetterLocation(Location location, Location currentBestLocation) {
    if (currentBestLocation == null) {
      // A new location is always better than no location
      return true;
    }

    // Check whether the new location fix is newer or older
    long timeDelta = location.getTime() - currentBestLocation.getTime();
    boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
    boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
    boolean isNewer = timeDelta > 0;

    // If it's been more than two minutes since the current location, use the new location
    // because the user has likely moved
    if (isSignificantlyNewer) {
      return true;
      // If the new location is more than two minutes older, it must be worse
    } else if (isSignificantlyOlder) {
      return false;
    }

    // Check whether the new location fix is more or less accurate
    int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
    boolean isLessAccurate = accuracyDelta > 0;
    boolean isMoreAccurate = accuracyDelta < 0;
    boolean isSignificantlyLessAccurate = accuracyDelta > 200;

    // Check if the old and new location are from the same provider
    boolean isFromSameProvider = isSameProvider(location.getProvider(),
        currentBestLocation.getProvider());

    // Determine location quality using a combination of timeliness and accuracy
    if (isMoreAccurate) {
      return true;
    } else if (isNewer && !isLessAccurate) {
      return true;
    } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
      return true;
    }
    return false;
  }

  /** Checks whether two providers are the same */
  private boolean isSameProvider(String provider1, String provider2) {
    if (provider1 == null) {
      return provider2 == null;
    }
    return provider1.equals(provider2);
  }


	/** This is the tracker thread */
  private class TrackerRequest extends Thread {
    private final static String MY_TAG = "TrackerReq";
    private String params;
    URL url;

    public void run() {
      String message;
      int code = 0;
      boolean success = false;
      // Retry every 10 seconds at least in case of network failure
      int retry = 10;
      // Try to keep at most 10 threads alive
      int iMax = 10;
      for (int i = 1; !(success || (i > iMax) || !LocationUpdatesService.isRunning); i++) {
        try {
          url = new URL(urlText + params);
          HttpURLConnection conn = (HttpURLConnection) url.openConnection();
          conn.setReadTimeout(10000 /* milliseconds */);
          conn.setConnectTimeout(15000 /* milliseconds */);
          conn.setRequestMethod("GET");
          conn.setDoInput(true);
          conn.connect();
          code = conn.getResponseCode();
          Log.d(MY_TAG, "HTTP request done: " + code);
          message = "HTTP " + code;
          if (code != 200) {
            if (code == 404) {
              Log.d(MY_TAG, "HTTP Not Found");
            }
          } else {
            success = true;
          }
        } catch (MalformedURLException e) {
          message = getResources().getString(R.string.error_malformed_url);
        } catch (UnknownHostException e) {
          message = getResources().getString(R.string.error_unknown_host);
        } catch (SSLHandshakeException e) {
          message = getResources().getString(R.string.error_ssl);
        } catch (SocketTimeoutException e) {
          message = getResources().getString(R.string.error_timeout);
        } catch (Exception e) {
          Log.d(MY_TAG, "HTTP request failed: " + e);
          message = e.getLocalizedMessage();
          if (message == null) {
            message = e.toString();
          }
        }
        if (!params.startsWith("self_hosted_tracker=")) {
          lastServerResponse = getResources().getString(R.string.last_location_sent_at)
              + " "
              + DateFormat.getTimeInstance().format(new Date())
              + " ";
          if (code == 200) {
            lastServerResponse += "<font color='#00aa00'><b>"
                + getResources().getString(R.string.http_request_ok)
//                                + "<br>"
//                                + url
                + "<br>"
                + "</b></font>";
          } else {
            lastServerResponse += "<font color='#ff0000'><b>"
                + getResources().getString(R.string.http_request_failed)
                + "</b></font>"
                + "<br>"
                + urlText
                + "<br>"
                + "(" + message + ")";
          }

          Intent notifIntent = new Intent(NOTIFICATION);
          notifIntent.putExtra(NOTIFICATION, "HTTP");
          sendBroadcast(notifIntent);
        }
        if (!success) {
          try {
            Thread.sleep(retry * 1000); // note: when device is sleeping, it may last longer
          } catch (Exception e) {
          }
        }
      }
    }

    public void start(String params) {
      this.params = params;
      super.start();
    }
  }
}
