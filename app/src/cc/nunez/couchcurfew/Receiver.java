package cc.nunez.couchcurfew;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot, our own update (Android turns the service off) and midnight (apps out of time open again). */
public class Receiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Guard guard = Guard.get(c);
        PendingResult pending = goAsync();
        Runnable done = pending::finish;
        String action = i.getAction();
        if (Guard.ACTION_MIDNIGHT.equals(action)) guard.reconcile(done);
        else guard.repairService(done);
        guard.scheduleMidnight();
    }
}
