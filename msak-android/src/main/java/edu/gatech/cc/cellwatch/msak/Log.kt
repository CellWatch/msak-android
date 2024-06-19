package edu.gatech.cc.cellwatch.msak

interface Logger {
    fun v(tag: String?, message: String?, throwable: Throwable? = null)
    fun d(tag: String?, message: String?, throwable: Throwable? = null)
    fun i(tag: String?, message: String?, throwable: Throwable? = null)
    fun w(tag: String?, message: String?, throwable: Throwable? = null)
    fun e(tag: String?, message: String?, throwable: Throwable? = null)
}

object AndroidLogger : Logger {
    override fun v(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.v(tag, message, throwable)
    }
    override fun d(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.d(tag, message, throwable)
    }
    override fun i(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.i(tag, message, throwable)
    }
    override fun w(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.w(tag, message, throwable)
    }
    override fun e(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.e(tag, message, throwable)
    }
}

internal var Log: Logger = AndroidLogger

fun setLogger(log: Logger) {
    Log = log
}