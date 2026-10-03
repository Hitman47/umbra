package android.util

/** Android's log, on a PC: warnings to the console, the rest dropped. */
object Log {
    @JvmStatic fun i(tag: String, message: String) = 0
    @JvmStatic fun d(tag: String, message: String) = 0
    @JvmStatic fun w(tag: String, message: String) = System.err.println("[$tag] $message").let { 0 }
    @JvmStatic fun w(tag: String, message: String, e: Throwable) = System.err.println("[$tag] $message: $e").let { 0 }
    @JvmStatic fun e(tag: String, message: String, e: Throwable) = System.err.println("[$tag] $message: $e").let { 0 }
}
