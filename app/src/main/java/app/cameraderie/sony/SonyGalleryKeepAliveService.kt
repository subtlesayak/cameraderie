package app.cameraderie.sony

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import app.cameraderie.R

/** Keeps a user-started, live camera network eligible while Home/another app is visible.
 * It does not reconnect, replay work or resume a process-death transfer. */
internal object SonyGalleryKeepAlive {
    private val lifetime=SonySessionLifetime()
    private var cancel:(()->Unit)?=null
    fun begin(context:Context,onDisconnect:()->Unit):String {
        cancel?.invoke()
        val id=java.util.UUID.randomUUID().toString()
        check(lifetime.begin(id));cancel=onDisconnect
        try { context.startForegroundService(command(context,SonyGalleryKeepAliveService.BEGIN,id)) }
        catch(error:RuntimeException) { lifetime.end(id);cancel=null;throw error }
        return id
    }
    fun current(id:String)=lifetime.isCurrent(id)
    fun update(context:Context,id:String,text:String,percent:Int?=null) {
        if(!current(id)) return
        context.startService(command(context,SonyGalleryKeepAliveService.UPDATE,id).putExtra("status",text.take(300)).putExtra("percent",percent ?: -1))
    }
    fun end(context:Context,id:String) {
        if(!lifetime.end(id)) return
        cancel=null;context.startService(command(context,SonyGalleryKeepAliveService.END,id))
    }
    fun disconnect(id:String) {
        if(!current(id)) return
        val callback=cancel
        if(callback!=null) callback() else { lifetime.end(id);cancel=null }
    }
    private fun command(context:Context,action:String,id:String)=Intent(context,SonyGalleryKeepAliveService::class.java).setAction(action).putExtra("lease",id)
}

internal object SonyGallerySessionNotification {
    const val CHANNEL="sony_camera_session"
    fun build(context:Context,id:String,text:String,percent:Int?=null):Notification {
        val open=Intent(context,SonyGalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val disconnect=Intent(context,SonyGalleryKeepAliveService::class.java).setAction(SonyGalleryKeepAliveService.DISCONNECT)
            .setData(Uri.parse("gripbridge://sony-session/$id")).putExtra("lease",id)
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_gallery)
            .setContentTitle("Cameraderie camera session").setContentText(text.take(300))
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context,5301,open,flags))
            .addAction(Notification.Action.Builder(null,"Disconnect",PendingIntent.getService(context,0,disconnect,flags)).build())
            .apply { if(percent!=null) setProgress(100,percent.coerceIn(0,100),false) }
            .build()
    }
}

class SonyGalleryKeepAliveService:Service() {
    private var lease:String?=null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(SonyGallerySessionNotification.CHANNEL,"Camera connection and imports",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val id=intent?.getStringExtra("lease") ?: run { stopSelf(startId);return START_NOT_STICKY }
        when(intent.action) {
            BEGIN -> {
                if(!SonyGalleryKeepAlive.current(id)) {
                    // An immediate Cancel may beat onStartCommand. Fulfil the
                    // foreground-start contract, then retire only this start ID.
                    if(lease==null) {
                        startForeground(NOTIFICATION,SonyGallerySessionNotification.build(this,id,"Closing camera connection…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)
                    }
                    return START_NOT_STICKY
                }
                lease=id
                startForeground(NOTIFICATION,SonyGallerySessionNotification.build(this,id,"Opening the selected camera connection…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
            UPDATE -> if(lease==id && SonyGalleryKeepAlive.current(id)) {
                val percent=intent.getIntExtra("percent",-1).takeIf { it>=0 }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION,SonyGallerySessionNotification.build(this,id,intent.getStringExtra("status") ?: "Camera session active",percent))
            }
            DISCONNECT -> if(lease==id) { SonyGalleryKeepAlive.disconnect(id);stopForeground(STOP_FOREGROUND_REMOVE);lease=null;stopSelf(startId) }
            END -> if(lease==id || lease==null) { stopForeground(STOP_FOREGROUND_REMOVE);lease=null;stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent:Intent?) { lease?.let { SonyGalleryKeepAlive.disconnect(it) };stopSelf() }
    override fun onDestroy() { lease?.let { SonyGalleryKeepAlive.disconnect(it) };lease=null;super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        const val BEGIN="app.cameraderie.SONY_SESSION_BEGIN"
        const val UPDATE="app.cameraderie.SONY_SESSION_UPDATE"
        const val END="app.cameraderie.SONY_SESSION_END"
        const val DISCONNECT="app.cameraderie.SONY_SESSION_DISCONNECT"
        private const val NOTIFICATION=5301
    }
}
