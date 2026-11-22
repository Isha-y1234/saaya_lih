package com.project.saaya.data.repository

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFormatTest {
    private fun wav(count:Int=32000,silent:Boolean=false):ByteArray = WavFormat.header(count)+ByteArray(count).also {
        if(!silent){it[0]=0x20;it[1]=0x10}
    }
    @Test fun nativeHeaderAndPcmAreValidAndOriginalBytesRemainUnchanged() {
        val original=wav();val copy=original.copyOf()
        val info=WavFormat.validate(original)
        assertEquals(1000L,info.durationMs);assertFalse(info.silent);assertArrayEquals(copy,original)
    }
    @Test fun exactThirtySecondBoundaryAndMinimalNonemptySample() {
        assertEquals(30000L,WavFormat.validate(wav(960000)).durationMs)
        assertEquals(46,wav(2).size)
        try{WavFormat.header(960002);fail()}catch(_:IllegalArgumentException){}
        try{WavFormat.validate(ByteArray(960046));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun rejectsWrongCodecChannelsRateAlignmentLengthsAndBits() {
        for((offset,value) in listOf(4 to 0,16 to 18,24 to 48000,28 to 16000,40 to 1)) {
            val bytes=wav();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,value)
            try{WavFormat.validate(bytes);fail("Accepted invalid field $offset")}catch(_:IllegalArgumentException){}
        }
        for((offset,value) in listOf(20 to 3,22 to 2,32 to 4,34 to 8)) {
            val bytes=wav();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(offset,value.toShort())
            try{WavFormat.validate(bytes);fail("Accepted invalid field $offset")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun emptyMalformedAndSilentSamplesAreDistinguished() {
        for(bytes in listOf(ByteArray(0),ByteArray(44),wav().copyOf(32043),ByteArray(100))) {
            try{WavFormat.validate(bytes);fail()}catch(_:IllegalArgumentException){}
        }
        assertTrue(WavFormat.validate(wav(silent=true)).silent)
    }
}
