package com.vitmap.core.code

/** Chooses a sensible default among a cloud service's compilers for one language. */
object CompilerPick {
    private val crossArch = Regex("\\b(arm\\w*|aarch64|hexagon|risc-?v\\w*|rv32\\w*|rv64\\w*|mips\\w*|power\\w*|ppc\\w*|s390x?|sparc\\w*|msp430|avr|wasm\\w*|loongarch\\w*|m68k|xtensa|6502|bpf|kvx|tricore|vax|e?z80|sh4?|ia64|c6x|arc|cris|frv)\\b")
    private val unstable = Regex("trunk|nightly|snapshot|assert|experimental|\\bdev\\b|head|latest|\\(main\\)|preview|beta|rc\\d")

    private fun version(name: String): List<Int> = Regex("\\d+").findAll(name).take(4).map { it.value.toIntOrNull() ?: 0 }.toList()

    private val byVersion = Comparator<List<Int>> { a, b ->
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { -1 }).compareTo(b.getOrElse(i) { -1 })
            if (c != 0) return@Comparator c
        }
        0
    }

    /**
     * The service's own default when it can run code; otherwise the newest stable build for the
     * host machine (cross compilers and trunk snapshots often can't execute).
     */
    fun godbolt(default: String?, compilers: List<Pair<String, String>>): String? {
        if (compilers.isEmpty()) return null
        if (default != null && compilers.any { it.first == default }) return default
        val native = compilers.filterNot { crossArch.containsMatchIn(it.second.lowercase()) }.ifEmpty { compilers }
        val stable = native.filterNot { unstable.containsMatchIn(it.second.lowercase()) }.ifEmpty { native }
        return stable.maxWithOrNull { a, b -> byVersion.compare(version(a.second), version(b.second)) }?.first
    }

    /** Wandbox lists newest first; skip "head" snapshots, which are sometimes broken. */
    fun wandbox(names: List<String>): String? = names.firstOrNull { "head" !in it } ?: names.firstOrNull()
}
