package org.sofwerx.sqan;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public class LocationService implements LocationListener {
    private final LocationManager locationManager;
    private final SqAnService sqAnService;
    private Location lastLocation = null;
    private LocationUpdateListener listener;

    public LocationService(SqAnService sqAnService) {
        this.sqAnService = sqAnService;
        if (sqAnService instanceof LocationUpdateListener)
            listener = (LocationUpdateListener)sqAnService;
        locationManager = (LocationManager) sqAnService.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (sqAnService.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && sqAnService.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;
        //locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, this);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, this);
        //TODO maybe add active GPS location updates as well
        lastLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
    }

    public Location getLastLocation() { return lastLocation; }

    public void shutdown() {
        //TODO
        locationManager.removeUpdates(this);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (isBetterLocation(location)) {
            lastLocation = location;
            if (listener != null)
                listener.onLocationChanged(location);
        }
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {
        //TODO
    }

    @Override
    public void onProviderEnabled(String s) {
        //TODO
    }

    @Override
    public void onProviderDisabled(String s) {
        //TODO
    }

    private final static int SIGNIFICANT_TIME_DIFFERENCE = 1000 * 60 * 2;
    protected boolean isBetterLocation(Location location) {
        if (lastLocation == null)
            return true;

        long timeDelta = location.getTime() - lastLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > SIGNIFICANT_TIME_DIFFERENCE;
        boolean isSignificantlyOlder = timeDelta < -SIGNIFICANT_TIME_DIFFERENCE;
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer)
            return true;
        else if (isSignificantlyOlder)
            return false;

        int accuracyDelta = (int) (location.getAccuracy() - lastLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        boolean isFromSameProvider = isSameProvider(location.getProvider(), lastLocation.getProvider());

        if (isMoreAccurate)
            return true;
        else if (isNewer && !isLessAccurate)
            return true;
        else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider)
            return true;
        return false;
    }

    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null)
            return provider2 == null;
        return provider1.equals(provider2);
    }

    public void setListener(LocationUpdateListener listener) { this.listener = listener; }

    public interface LocationUpdateListener {
        void onLocationChanged(Location location);
    }
}
