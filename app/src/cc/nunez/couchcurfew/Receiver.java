package cc.nunez.couchcurfew;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot, our own update (Android turns the service off) and the alarm (midnight, or the schedule turning kids mode on or off). */
public class Receiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Guard guard = Guard.get(c);
        PendingResult pending = goAsync();
        Runnable done = pending::finish;
        String action = i.getAction();
        if (Guard.ACTION_ALARM.equals(action)) guard.onAlarm(done);
        else {
            guard.repairService(done);
            guard.scheduleAlarm();
        }
    }
}
