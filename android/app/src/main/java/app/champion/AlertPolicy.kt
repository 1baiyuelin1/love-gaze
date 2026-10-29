package app.champion

/** Only newly inserted, recent partner opens may produce an alert. */
object AlertPolicy {
    val packages = setOf("com.xingin.xhs", "com.tencent.mm", "com.taobao.taobao", "com.ss.android.ugc.aweme")

    fun select(moments: List<Moment>, partner: String, enabledAt: Long, now: Long): List<Moment> =
        moments.asSequence().filter {
            it.owner == partner && it.kind == Kind.OPEN && it.packageName in packages &&
                it.at >= enabledAt && now - it.at in -120_000L..1_800_000L
        }.distinctBy { it.id }.sortedByDescending { it.at }.distinctBy { it.packageName }.toList()
}
