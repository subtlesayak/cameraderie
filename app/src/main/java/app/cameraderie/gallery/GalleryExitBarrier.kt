package app.cameraderie.gallery

/** Main-thread conjunction: worker teardown and BLE release must both finish
 * before bridge restoration. Stop cancels the continuation immediately. */
class GalleryExitBarrier(private var ready: (() -> Unit)?) {
    private var worker=false
    private var ble=false
    fun workerClosed() { worker=true;check() }
    fun bleClosed() { ble=true;check() }
    private fun check() { if(worker && ble) { val callback=ready;ready=null;callback?.invoke() } }
    fun cancel() { ready=null }
}
