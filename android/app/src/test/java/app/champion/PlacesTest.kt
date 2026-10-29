package app.champion

import org.junit.Assert.*
import org.junit.Test

class PlacesTest {
    @Test fun vivoCountryOnlyLineUsesAvailableAddressFields() {
        assertEquals("四川省成都市武侯区人民南路", Places.address("中国", "中国", listOf("四川省", "成都市", "武侯区", "人民南路")))
    }

    @Test fun countryOnlyWithoutDetailsShowsCoordinates() {
        assertEquals("", Places.address("中国", "中国", listOf("中国")))
        assertEquals("坐标 30.57000, 104.06000", Places.display("中国", 30.57, 104.06))
        assertEquals("等待详细定位", Places.display("中国"))
    }

    @Test fun detailedXiaomiAddressIsKept() {
        val line = "中国四川省成都市武侯区人民南路四段"
        assertEquals(line, Places.address(line, "中国", listOf("四川省", "成都市", "武侯区")))
    }

    @Test fun duplicatedCityFieldsAreNotRepeated() {
        assertEquals("上海市徐汇区", Places.address("中国", "中国", listOf("上海市", "上海市", "徐汇区")))
    }
}
