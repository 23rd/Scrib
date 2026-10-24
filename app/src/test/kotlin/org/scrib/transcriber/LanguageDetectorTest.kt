package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun latinScriptLanguagesByCommonWords() {
        assertEquals("de", LanguageDetector.detect("Guten Morgen zusammen. Ich wollte nur kurz Bescheid geben, dass ich heute etwas später ins Büro komme, weil mein Zug ausgefallen ist."))
        assertEquals("en", LanguageDetector.detect("Good morning everyone. I just wanted to let you know that I will be a bit late to the office today because my train was cancelled."))
        assertEquals("es", LanguageDetector.detect("Buenos días a todos. Solo quería avisar de que hoy llegaré un poco más tarde a la oficina porque han cancelado mi tren."))
        assertEquals("fr", LanguageDetector.detect("Bonjour à tous. Je voulais juste vous prévenir que je serai un peu en retard au bureau aujourd'hui parce que mon train est annulé."))
        assertEquals("it", LanguageDetector.detect("Buongiorno a tutti. Volevo solo avvisare che oggi arriverò un po' più tardi in ufficio perché il mio treno è stato cancellato."))
        assertEquals("nl", LanguageDetector.detect("Goedemorgen allemaal. Ik wilde even laten weten dat ik vandaag wat later op kantoor ben omdat mijn trein is uitgevallen."))
        assertEquals("pt", LanguageDetector.detect("Bom dia a todos. Só queria avisar que hoje vou chegar um pouco mais tarde ao escritório porque o meu comboio foi cancelado."))
        assertEquals("pl", LanguageDetector.detect("Dzień dobry wszystkim. Chciałem tylko dać znać, że dzisiaj będę trochę później w biurze, bo mój pociąg został odwołany."))
    }

    @Test
    fun scriptsSettleTheRest() {
        assertEquals("ru", LanguageDetector.detect("Доброе утро всем. Я хотел только предупредить, что сегодня приду в офис немного позже, потому что мой поезд отменили."))
        assertEquals("uk", LanguageDetector.detect("Доброго ранку всім. Я хотів лише попередити, що сьогодні прийду в офіс трохи пізніше, бо мій потяг скасували."))
        assertEquals("ja", LanguageDetector.detect("おはようございます。今日は電車が運休になったので、少し遅れて出社します。"))
        assertEquals("zh", LanguageDetector.detect("大家早上好。我只是想说一下，今天我会晚一点到办公室，因为我的火车被取消了。"))
        assertEquals("ko", LanguageDetector.detect("좋은 아침입니다. 오늘 기차가 취소되어 사무실에 조금 늦게 도착할 예정입니다."))
        assertEquals("ar", LanguageDetector.detect("صباح الخير للجميع. أردت فقط أن أخبركم أنني سأصل إلى المكتب متأخراً قليلاً اليوم لأن قطاري أُلغي."))
    }

    @Test
    fun tooLittleToGoOn() {
        assertNull(LanguageDetector.detect(""))
        assertNull(LanguageDetector.detect("12345 67890"))
        assertNull(LanguageDetector.detect("Zugausfall Büro"))
    }
}
