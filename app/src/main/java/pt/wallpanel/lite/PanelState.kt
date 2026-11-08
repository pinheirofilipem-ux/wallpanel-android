package pt.wallpanel.lite

/** Estado partilhado entre o serviço (decide) e a atividade (mostra). Só usado na main thread. */
object PanelState {
    var sleeping = false
        private set
    var listener: ((Boolean) -> Unit)? = null

    fun set(value: Boolean) {
        if (sleeping == value) return
        sleeping = value
        listener?.invoke(value)
    }
}
