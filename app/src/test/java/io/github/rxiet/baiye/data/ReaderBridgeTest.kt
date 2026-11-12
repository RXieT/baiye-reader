package io.github.rxiet.baiye.data

import io.github.rxiet.baiye.reader.ReaderJson
import io.github.rxiet.baiye.reader.ReaderManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ReaderBridgeTest {
    @Test fun localManifestAlwaysContainsRequiredEmptyChapterArray() {
        val manifest = ReaderManifest(Book("local-test", "测试", local = true), settings = ReaderSettings())
        val value = ReaderJson.parseToJsonElement(ReaderJson.encodeToString(manifest)).jsonObject
        assertEquals("[]", value.getValue("chapters").toString())
        assertTrue(value.getValue("book").jsonObject.getValue("local").toString().toBoolean())
    }
    @Test fun settingsAlwaysSendDefaultsWhenSwitchingBack() {
        val value = ReaderJson.parseToJsonElement(ReaderJson.encodeToString(ReaderSettings())).jsonObject
        assertEquals("false", value.getValue("scroll").toString())
        assertEquals("20", value.getValue("fontSize").toString())
        assertEquals("\"paper\"", value.getValue("theme").toString())
    }
}
