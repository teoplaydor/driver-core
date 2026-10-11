// «Пульт кистью» for Garmin: the watch's side of «tracks by a turn of the wrist» (SemSearch → Настройки → Часы).
// The app sends the raw accelerometer to the phone, the phone recognises the turn and answers; the app vibrates.
// A watch app keeps running with the backlight off while it is the open app.
using Toybox.Application;
using Toybox.WatchUi;

class WristApp extends Application.AppBase {
    var link;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        link = new WristLink();
        link.start();
    }

    function onStop(state) {
        if (link != null) {
            link.stop();
        }
    }

    function getInitialView() {
        return [new WristView(link)];
    }
}
