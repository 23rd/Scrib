package org.scrib.transcriber

class CancellationToken {

    private val lock = Any()
    private val listeners = ArrayList<() -> Unit>()

    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        val pending: List<() -> Unit>
        synchronized(lock) {
            if (isCancelled) {
                return
            }
            isCancelled = true
            pending = ArrayList(listeners)
            listeners.clear()
        }
        pending.forEach { notify(it) }
    }

    fun onCancel(listener: () -> Unit) {
        val alreadyCancelled = synchronized(lock) {
            if (!isCancelled) {
                listeners.add(listener)
            }
            isCancelled
        }
        if (alreadyCancelled) {
            notify(listener)
        }
    }

    private fun notify(listener: () -> Unit) {
        try {
            listener()
        } catch (ignore: Throwable) {
        }
    }
}
