package com.azat.runningtracker.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.azat.runningtracker.R
import com.azat.runningtracker.other.Constants.ACTION_PAUSE_SERVICE
import com.azat.runningtracker.other.Constants.ACTION_SHOW_TRACKING_FRAGMENT
import com.azat.runningtracker.other.Constants.ACTION_START_OR_RESUME_SERVICE
import com.azat.runningtracker.other.Constants.ACTION_STOP_SERVICE
import com.azat.runningtracker.other.Constants.FASTEST_LOCATION_INTERVAL
import com.azat.runningtracker.other.Constants.LOCATION_UPDATE_INTERVAL
import com.azat.runningtracker.other.Constants.NOTIFICATION_CHANNEL_ID
import com.azat.runningtracker.other.Constants.NOTIFICATION_CHANNEL_NAME
import com.azat.runningtracker.other.Constants.NOTIFICATION_ID
import com.azat.runningtracker.other.TrackingUtility
import com.azat.runningtracker.ui.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import timber.log.Timber

/*************************
 * Created by AZAT SAYAN *
 *                       *
 * Contact: @theazat     *
 *                       *
 * 07/07/2020 - 8:05 AM  *
 ************************/
/* Why we extends form LifecycleService?
* we will need to observe from life to the objects inside of this service class
* The observed function of a live data object needs the lifecycle owner
* and if we don't specify this LifecycleService() here we can't pass
* an instance of this service as lifecycle owner to that observed function
*
* We want to manage the communication from our activity to our service so on
* the one hand we have the activity to service communication or fragment to
* service communication and for that we will simply use intents, so whenever
* we want to send a command to our service then we will simply send an intent
* to that service with an action attached and inside of this service class then check
* what that action is.
*
* Also we need to worry about the communication from our service to our
* TrackingFragment because we need to to coordinate of the user data.
* So that we basically have two major options to do that.
* One option is Singleton pattern.
* The other option would be to make this service a bound service so that
* basically means that the service acts as kind of server and clients can
* bind to it so the client would be our fragment in this case but
* that is much more work and Singleton pattern makes this really much easier.
* */
class TrackingService : LifecycleService() {

    var isFirstRun = true

    lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    companion object {
        val isTracking = MutableLiveData<Boolean>()

        // val pathPoints = MutableLiveData<MutableList<MutableList<LatLng>>>()
        val pathPoints = MutableLiveData<PolyLines>()
    }

    private fun postInitialValues() {
        isTracking.postValue(false)
        pathPoints.postValue(mutableListOf())
    }

    override fun onCreate() {
        super.onCreate()
        postInitialValues()
        fusedLocationProviderClient = FusedLocationProviderClient(this)

        isTracking.observe(this, Observer {
            updateLocationTracking(it)
        })
    }

    // whenever we send a command to our service so whenever we send an Intent with an action attached to this service class
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START_OR_RESUME_SERVICE -> {
                    if (isFirstRun) {
                        startForegroundService()
                        isFirstRun = false
                    } else {
                        Timber.d("Resuming service..")
                    }
                }
                ACTION_PAUSE_SERVICE -> {
                    Timber.d("Paused service")
                }
                ACTION_STOP_SERVICE -> {
                    Timber.d("Stopped service")
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationTracking(isTracking: Boolean) {
        if (isTracking) {
            if (TrackingUtility.hasLocationPermission(this)) {
                val request = LocationRequest().apply {
                    interval = LOCATION_UPDATE_INTERVAL
                    fastestInterval = FASTEST_LOCATION_INTERVAL
                    priority = PRIORITY_HIGH_ACCURACY
                }
                fusedLocationProviderClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
        } else {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        }
    }

    val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult?) {
            super.onLocationResult(result)
            if (isTracking.value!!) {
                result?.locations?.let { locations ->
                    for (location in locations) {
                        addPathPoint(location)
                        Timber.d("NEW LOCATION: ${location.latitude}, ${location.longitude}")
                    }
                }
            }
        }
    }

    private fun addPathPoint(location: Location?) {
        location?.let {
            val pos = LatLng(it.latitude, it.longitude)
            pathPoints.value?.apply {
                last().add(pos)
                pathPoints.postValue(this)
            }
        }
    }


    private fun addEmptyPolyline() = pathPoints.value?.apply {
        add(mutableListOf())
        pathPoints.postValue(this)
    } ?: pathPoints.postValue(mutableListOf(mutableListOf()))

    /* NotificationManager is just system service so a service of the Android framework
    * that we need whenever we want to show notification so we just get a reference
    * to that system service and then we create our notification locally here in our app
    * and then we basically pass that notification to notification manager so Android
    * system can actually show our notification */
    private fun startForegroundService() {
        addEmptyPolyline()
        isTracking.postValue(true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(notificationManager)
        }

        // setAutoCancel(false) will just prevent that if the user clicks on our notification
        // that the notification disappears we always want to the notification to be active and that's why we have to set
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_directions_run_black_24dp)
            .setContentTitle("Running App")
            .setContentText("00:00:00")
            .setContentIntent(getMainActivityPendingIntent())


        startForeground(NOTIFICATION_ID, notificationBuilder.build())
    }

    /* Also we need to pass to that notificationBuilder is a PendingIntent. A PendingIntent is used
    * here to open our MainActivity when we click on our notification */
    private fun getMainActivityPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).also {
            it.action = ACTION_SHOW_TRACKING_FRAGMENT
        },
        FLAG_UPDATE_CURRENT // whenever we launch that PendingIntent and it already exists it will update it instead of recreating or restarting it
    )


    /* We need to create a notification with which we will launch this foreground service
    * so whenever you want to show another notification then we need to make sure to
    * create a channel for that a notification channel or at least for Android Oreo and later
    */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }
}

typealias Polyline = MutableList<LatLng>
typealias PolyLines = MutableList<Polyline>