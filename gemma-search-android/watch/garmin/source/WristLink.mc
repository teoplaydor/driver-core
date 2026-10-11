// The link: finds the phone by its Bluetooth LE service, connects, switches the phone's answers on, says HELLO and
// sends the accelerometer (25 a second, milli-g, 3 samples a 20-byte packet — Connect IQ writes 20 bytes at most);
// one Bluetooth request at a time. The phone's answers: «next» / «previous» buzz, «pause» stops the sensor while no
// music plays, «stream» starts it again. The protocol is the phone's (core/WristGesture.java).
using Toybox.BluetoothLowEnergy as Ble;
using Toybox.Sensor;
using Toybox.Attention;
using Toybox.Application.Storage;
using Toybox.Math;
using Toybox.WatchUi;
using Toybox.Lang;

class WristLink extends Ble.BleDelegate {
    const RATE = 25;
    const EV_NEXT = 1;
    const EV_PREVIOUS = 2;
    const EV_LEARNED = 3;
    const EV_PAUSE = 4;
    const EV_STREAM = 5;

    var status = "запускается…";
    var service;
    var samplesUuid;
    var eventsUuid;
    var device = null;
    var queue = [];
    var busy = false;
    var streaming = false;
    var linked = false;
    var seq = 0;
    var buf = [];
    var id;

    function initialize() {
        BleDelegate.initialize();
        service = Ble.stringToUuid("a7b7e001-9ca3-4574-a1f0-3c9840e24d01");
        samplesUuid = Ble.stringToUuid("a7b7e002-9ca3-4574-a1f0-3c9840e24d01");
        eventsUuid = Ble.stringToUuid("a7b7e003-9ca3-4574-a1f0-3c9840e24d01");
        id = Storage.getValue("wrist_id");
        if (id == null) {
            // who this watch is to the phone: random, kept (the phone listens only to the watch it met first)
            id = (Math.rand() & 0x7ffffffe) | 1;
            Storage.setValue("wrist_id", id);
        }
    }

    function show(s) {
        status = s;
        WatchUi.requestUpdate();
    }

    function start() {
        Ble.setDelegate(self);
        Ble.registerProfile({
            :uuid => service,
            :characteristics => [
                { :uuid => samplesUuid },
                { :uuid => eventsUuid, :descriptors => [Ble.cccdUuid()] }
            ]
        });
    }

    function stop() {
        listen(false);
        Ble.setScanState(Ble.SCAN_STATE_OFF);
        if (device != null) {
            Ble.unpairDevice(device);
            device = null;
        }
    }

    function onProfileRegister(uuid, st) {
        scan();
    }

    // ---------------------------------------------------------------- finding the phone

    function scan() {
        show("ищу телефон…");
        Ble.setScanState(Ble.SCAN_STATE_SCANNING);
    }

    function onScanResults(results) {
        for (var r = results.next(); r != null; r = results.next()) {
            if (advertises(r)) {
                Ble.setScanState(Ble.SCAN_STATE_OFF);
                show("подключаюсь…");
                try {
                    device = Ble.pairDevice(r);
                } catch (e) {
                    device = null;
                }
                if (device == null) {
                    scan();
                }
                return;
            }
        }
    }

    function advertises(r) {
        var it = r.getServiceUuids();
        if (it == null) {
            return false;
        }
        for (var u = it.next(); u != null; u = it.next()) {
            if (u.equals(service)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the link

    function onConnectedStateChanged(dev, state) {
        if (state == Ble.CONNECTION_STATE_CONNECTED) {
            device = dev;
            var ch = events();
            var d = ch == null ? null : ch.getDescriptor(Ble.cccdUuid());
            if (d == null) {
                hello();
                return;
            }
            busy = true;
            try {
                d.requestWrite([0x01, 0x00]b);
            } catch (e) {
                busy = false;
                hello();
            }
        } else {
            lost();
        }
    }

    function onDescriptorWrite(desc, st) {
        busy = false;
        if (!linked) {
            hello();
        }
    }

    function hello() {
        linked = true;
        var name = "garmin".toUtf8Array();
        var b = new [10 + name.size()]b;
        b[0] = 0x00;
        b[1] = 1;
        put16(b, 2, id & 0xffff);
        put16(b, 4, (id >> 16) & 0xffff);
        put16(b, 6, RATE);
        put16(b, 8, 1000);
        for (var i = 0; i < name.size(); i++) {
            b[10 + i] = name[i];
        }
        send(b);
        listen(true);
        show("подключено");
    }

    function lost() {
        listen(false);
        queue = [];
        busy = false;
        linked = false;
        if (device != null) {
            Ble.unpairDevice(device);
            device = null;
        }
        show("связь потеряна — ищу");
        scan();
    }

    function events() {
        if (device == null) {
            return null;
        }
        var s = device.getService(service);
        return s == null ? null : s.getCharacteristic(eventsUuid);
    }

    function onCharacteristicChanged(ch, value) {
        if (value == null || value.size() == 0 || !ch.getUuid().equals(eventsUuid)) {
            return;
        }
        var ev = value[0];
        if (ev == EV_PAUSE) {
            listen(false);
            show("музыка не играет");
        } else if (ev == EV_STREAM) {
            buf = [];
            listen(true);
        } else if (ev == EV_NEXT || ev == EV_PREVIOUS) {
            buzz(60);
        } else if (ev == EV_LEARNED) {
            buzz(300);
        }
    }

    function buzz(ms) {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(60, ms)]);
        }
    }

    // ---------------------------------------------------------------- the samples

    function listen(on) {
        if (on == streaming) {
            return;
        }
        streaming = on;
        if (on) {
            show("слушаю повороты кисти");
            Sensor.registerSensorDataListener(method(:onSensor), {
                :period => 1,
                :accelerometer => { :enabled => true, :sampleRate => RATE }
            });
        } else {
            Sensor.unregisterSensorDataListener();
        }
    }

    // a second of samples at a time (the period): sent as soon as they come
    function onSensor(data) {
        var a = data.accelerometerData;
        if (!streaming || a == null || a.x == null) {
            return;
        }
        for (var i = 0; i < a.x.size(); i++) {
            buf.add(a.x[i]);
            buf.add(a.y[i]);
            buf.add(a.z[i]);
            if (buf.size() >= 9) {
                var b = new [20]b;
                b[0] = 0x13;
                b[1] = seq & 0xff;
                seq = seq + 1;
                for (var k = 0; k < 9; k++) {
                    put16(b, 2 + 2 * k, buf[k]);
                }
                buf = [];
                send(b);
            }
        }
    }

    function put16(b, o, v) {
        if (v > 32767) {
            v = 32767;
        } else if (v < -32768) {
            v = -32768;
        }
        v = v & 0xffff;
        b[o] = v & 0xff;
        b[o + 1] = (v >> 8) & 0xff;
    }

    // one Bluetooth request at a time; a slow link loses the oldest samples, not the newest
    function send(b) {
        queue.add(b);
        if (queue.size() > 20) {
            queue = queue.slice(queue.size() - 20, null);
        }
        pump();
    }

    function pump() {
        if (busy || device == null || queue.size() == 0) {
            return;
        }
        var s = device.getService(service);
        var ch = s == null ? null : s.getCharacteristic(samplesUuid);
        if (ch == null) {
            return;
        }
        var b = queue[0];
        queue = queue.slice(1, null);
        busy = true;
        try {
            // on Connect IQ the default write type is the one without a response
            ch.requestWrite(b, { :writeType => Ble.WRITE_TYPE_DEFAULT });
        } catch (e) {
            busy = false;
        }
    }

    function onCharacteristicWrite(ch, st) {
        busy = false;
        pump();
    }
}
