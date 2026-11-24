package app.cameraderie.sony

/** One active Sony session. Old notifications/callbacks cannot close a newer lease. */
internal class SonySessionLifetime {
    private var active:String?=null
    @Synchronized fun begin(id:String):Boolean {
        require(id.isNotBlank());if(active!=null) return false;active=id;return true
    }
    @Synchronized fun isCurrent(id:String)=active==id
    @Synchronized fun end(id:String):Boolean {
        if(active!=id) return false;active=null;return true
    }
}
