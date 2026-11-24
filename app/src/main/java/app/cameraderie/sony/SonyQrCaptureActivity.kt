package app.cameraderie.sony

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/** Camera connection QR content stays private even when gallery screenshots are allowed. */
class SonyQrCaptureActivity:CaptureActivity() {
    override fun onCreate(state:Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(state)
    }
}
