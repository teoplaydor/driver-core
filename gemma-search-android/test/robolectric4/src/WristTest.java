import static org.junit.Assert.*;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothGattServer;
import org.robolectric.shadows.ShadowLooper;

import java.util.UUID;

import io.github.teoplaydor.semsearch.app.WristLink;
import io.github.teoplaydor.semsearch.app.WristService;
import io.github.teoplaydor.semsearch.core.WristGesture;

/**
 * Tracks by a turn of the wrist, the phone's side, through Robolectric's Bluetooth: the service opens the GATT server
 * (one service: samples written in, answers notified out) and advertises it; a watch connects, says HELLO and streams
 * its accelerometer; a turn right while music plays is «next», left is «previous», a turn with no music does nothing;
 * samples before a HELLO and another watch are turned away; the direction is taught by one turn.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WristTest {
    static final double G = 9.80665;

    /** The accelerometer of a left wrist, still / turned around the forearm by deg and back in s seconds, in milli-g. */
    static short[] stream(int rate, double... segs) {
        java.util.List<Short> out = new java.util.ArrayList<Short>();
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < segs.length; i += 2) {
            double deg = segs[i], s = segs[i + 1];
            int n = (int) Math.round(s * rate);
            for (int k = 0; k < n; k++) {
                double t = k / (double) rate, x = Math.sin(Math.PI * t / s);
                double th = Math.toRadians(deg) * x * x;
                // gravity seen by a watch turned by th around its x axis (face up at rest)
                double[] w = {0, Math.sin(th), Math.cos(th)};
                for (int j = 0; j < 3; j++) out.add((short) Math.round((w[j] + 0.01 * rnd.nextGaussian()) * 1000));
            }
        }
        short[] a = new short[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static int seq;

    /** The watch writes its samples, 3 a packet (20 bytes). */
    static void send(ShadowBluetoothGattServer sg, BluetoothDevice dev, BluetoothGattCharacteristic ch, short[] xyz) {
        for (int i = 0; i + 3 <= xyz.length / 3; i += 3) {
            sg.notifyOnCharacteristicWriteRequest(dev, 100 + seq, ch, false, false, 0, WristGesture.accel(seq++ & 0xff, xyz, i, 3));
        }
    }

    @Test
    public void wrist() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT",
                "android.permission.POST_NOTIFICATIONS");
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        Shadows.shadowOf(ad).setEnabled(true);
        Shadows.shadowOf(ad).setIsMultipleAdvertisementSupported(true);
        assertFalse("no Bluetooth LE: not offered", WristLink.supported(app));
        Shadows.shadowOf(app.getPackageManager()).setSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE, true);
        assertTrue(WristLink.supported(app));
        assertTrue(WristLink.hasPermissions(app));

        // on: the service holds the link
        WristLink.setOn(app, true);
        ServiceController<WristService> sc = Robolectric.buildService(WristService.class).create();
        sc.startCommand(0, 1);
        WristLink.Server server = WristLink.current();
        assertNotNull("the link is up", server);
        assertNotNull("GATT server opened", server.gatt);
        BluetoothGattService s = server.gatt.getService(UUID.fromString(WristGesture.SERVICE));
        assertNotNull("our service", s);
        BluetoothGattCharacteristic samples = s.getCharacteristic(UUID.fromString(WristGesture.SAMPLES));
        BluetoothGattCharacteristic events = s.getCharacteristic(UUID.fromString(WristGesture.EVENTS));
        assertNotNull(samples);
        assertNotNull(events);
        assertTrue("samples written without an answer", (samples.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0);
        assertTrue("answers notified", (events.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0);
        assertNotNull("notifications switch", events.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")));
        assertEquals("advertising", 1, Shadows.shadowOf(ad.getBluetoothLeAdvertiser()).getAdvertisementRequestCount());
        assertTrue(WristLink.status(), WristLink.status().startsWith("ждёт часы"));

        ShadowBluetoothGattServer sg = Shadows.shadowOf(server.gatt);
        BluetoothDevice watch = ad.getRemoteDevice("C0:FF:EE:00:00:01");
        sg.notifyConnection(watch);
        assertEquals("the link reached the phone", "часы подключаются…", WristLink.status());
        // a watch connected: advertising stops at once, so the radio is freer for its service discovery
        assertEquals("advertising stops on connect", 0, Shadows.shadowOf(ad.getBluetoothLeAdvertiser()).getAdvertisementRequestCount());
        // samples before a HELLO: not listened to
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        Shadows.shadowOf(am).setIsMusicActive(true);
        send(sg, watch, samples, stream(25, 0, 1, 70, 0.6, 0, 1));
        assertEquals("nothing before HELLO", -1, server.lastEvent);
        assertTrue("counted on the screen: " + WristLink.status(), WristLink.status().contains("без приветствия"));

        // HELLO: this watch is remembered; it subscribes to the answers
        sg.notifyOnCharacteristicWriteRequest(watch, 1, samples, false, true, 0, WristGesture.hello(0x0badcafe, 25, 1000, "vela"));
        assertTrue(WristLink.status(), WristLink.status().contains("vela") && WristLink.status().contains("25 Гц")
                && WristLink.status().contains("жду отсчёты"));
        assertEquals("still not advertising", 0, Shadows.shadowOf(ad.getBluetoothLeAdvertiser()).getAdvertisementRequestCount());
        Context c = app;
        BluetoothGattDescriptor cccd = events.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        // (Robolectric has no descriptor-write helper: the callback is called as the stack would)
        sg.getGattServerCallback().onDescriptorWriteRequest(watch, 2, cccd, false, true, 0, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        assertEquals("music plays: stream", WristGesture.EV_STREAM, server.lastEvent);
        assertTrue(WristLink.status(), WristLink.status().contains("музыка играет"));

        // a turn right while music plays: «next»
        send(sg, watch, samples, stream(25, 0, 1, 70, 0.6, 0, 1));
        assertEquals("next", WristGesture.EV_NEXT, server.lastEvent);
        assertTrue(WristLink.status(), WristLink.last(app).contains("следующий"));
        // left: «previous»
        send(sg, watch, samples, stream(25, -70, 0.6, 0, 1));
        assertEquals("previous", WristGesture.EV_PREVIOUS, server.lastEvent);
        assertTrue(WristLink.last(app).contains("предыдущий"));
        // no music: the turn is seen, nothing pressed, the watch told
        Shadows.shadowOf(am).setIsMusicActive(false);
        send(sg, watch, samples, stream(25, 70, 0.6, 0, 1));
        assertEquals("ignored", WristGesture.EV_IGNORED, server.lastEvent);
        assertTrue(WristLink.last(app).contains("музыка не играла"));
        // the music stopped: the watch is told to pause (and to stream again when it plays)
        ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("pause", WristGesture.EV_PAUSE, server.lastEvent);
        assertTrue("the screen says why the watch went quiet: " + WristLink.status(), WristLink.status().contains("пауза"));
        ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("told once", WristGesture.EV_PAUSE, server.lastEvent);
        Shadows.shadowOf(am).setIsMusicActive(true);
        ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("stream again", WristGesture.EV_STREAM, server.lastEvent);
        // after the pause the hand has to be still first: a turn at once does not count
        send(sg, watch, samples, stream(25, 70, 0.6, 0, 1));
        assertEquals("nothing from a stale position", WristGesture.EV_STREAM, server.lastEvent);

        // another watch: turned away
        BluetoothDevice other = ad.getRemoteDevice("C0:FF:EE:00:00:02");
        sg.notifyConnection(other);
        sg.notifyOnCharacteristicWriteRequest(other, 3, samples, false, false, 0, WristGesture.hello(0x12345678, 25, 1000, "zepp"));
        assertTrue(WristLink.status(), WristLink.status().contains("уже подключены"));
        assertTrue("its connection cancelled", sg.isConnectionCancelled(other));
        server.lastEvent = -1;
        send(sg, other, samples, stream(25, 0, 1, 70, 0.6, 0, 1));
        assertEquals("the other watch's turns do nothing", -1, server.lastEvent);

        // left/right mixed up on this watch: taught by one turn «to the right» (here: the other way round)
        assertTrue(WristLink.learn());
        send(sg, watch, samples, stream(25, 0, 1, -70, 0.6, 0, 1));
        assertEquals("learnt", WristGesture.EV_LEARNED, server.lastEvent);
        assertTrue(WristLink.learnt(c));
        send(sg, watch, samples, stream(25, -70, 0.6, 0, 1));
        assertEquals("now this way is next", WristGesture.EV_NEXT, server.lastEvent);

        // the watch goes away: advertising again
        sg.notifyDisconnection(watch);
        ShadowLooper.idleMainLooper();
        assertTrue(WristLink.status(), WristLink.status().contains("отключились"));
        assertEquals("advertising again", 1, Shadows.shadowOf(ad.getBluetoothLeAdvertiser()).getAdvertisementRequestCount());

        // forgotten: the next watch is welcome
        WristLink.forgetWatch(c);
        assertFalse(WristLink.learnt(c));
        sg.notifyConnection(other);
        sg.notifyOnCharacteristicWriteRequest(other, 4, samples, false, false, 0, WristGesture.hello(0x12345678, 50, 0, "zepp"));
        assertTrue(WristLink.status(), WristLink.status().contains("zepp") && WristLink.status().contains("50 Гц"));

        // a watch that finds the turns itself: one packet per turn; a small one is shown with the reason, a real one presses
        sg.notifyOnCharacteristicWriteRequest(other, 5, samples, false, true, 0,
                WristGesture.hello(WristGesture.VERSION_TURNS, 0x12345678, 16, 981, "zepp"));
        assertTrue(WristLink.status(), WristLink.status().contains("распознаёт повороты сам"));
        sg.notifyOnCharacteristicWriteRequest(other, 6, samples, false, true, 0, WristGesture.turnPacket(0, 26, 300, new float[]{1, 0, 0}));
        assertTrue(WristLink.status(), WristLink.status().contains("поворот 26° за 0,3 с") && WristLink.status().contains("мало"));
        server.lastEvent = -1;
        sg.notifyOnCharacteristicWriteRequest(other, 7, samples, false, true, 0, WristGesture.turnPacket(1, 64, 350, new float[]{1, 0, 0}));
        assertEquals("a turn found on the watch: next", WristGesture.EV_NEXT, server.lastEvent);
        assertTrue(WristLink.last(app), WristLink.last(app).contains("следующий") && WristLink.last(app).contains("64°"));

        // off: the service and the link are gone
        WristLink.setOn(c, false);
        sc.destroy();
        assertNull(WristLink.current());
        assertTrue("GATT server closed", sg.isClosed());
    }

}
