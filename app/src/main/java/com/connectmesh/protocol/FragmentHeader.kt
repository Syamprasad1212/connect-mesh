package com.connectmesh.protocol

data class FragmentHeader(
    val fragmentId: Long,
    val fragmentIndex: Short,
    val totalFragments: Short,
    val crc32: Int
) {
    companion object {
        const val FRAGMENT_HEADER_SIZE: Int = 16
    }
}
