package com.activityassistant.support;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CsvExportTest {
    @Test void chineseBomAndRfcEscapingPreserveCommasQuotesAndLineBreaks() {
        byte[] bytes=CsvExport.bytes(List.of("姓名","备注"),List.of(List.of("张同学","一行,带\"引号\"\n二行")));
        assertArrayEquals(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf},Arrays.copyOf(bytes,3));
        assertEquals("\uFEFF\"姓名\",\"备注\"\r\n\"张同学\",\"一行,带\"\"引号\"\"\n二行\"\r\n",new String(bytes,StandardCharsets.UTF_8));
    }
    @Test void dangerousUnicodeAndWhitespacePrefixedSpreadsheetCellsStayText() {
        for(String attack:List.of("=HYPERLINK(\"bad\")","+cmd|bad","-1+2","@SUM(1,2)"," \t\r=1+1","\uFEFF\u200B=cmd","\u3000＝SUM(1,2)","＋1+1","－1+1","＠SUM(1,2)","\u0000\u001f=cmd","−1+2")) {
            assertEquals("'"+attack,CsvExport.cell(attack),attack);
        }
    }
    @Test void typedNumericFactsAndOrdinaryTextAreNotCorrupted() {
        assertEquals("-12",CsvExport.cell(-12));assertEquals("0",CsvExport.cell(0));assertEquals("false",CsvExport.cell(false));
        assertEquals("2026-10-26T10:00:00Z",CsvExport.cell("2026-10-26T10:00:00Z"));assertEquals("中文答案",CsvExport.cell("中文答案"));
        assertEquals("'-12",CsvExport.cell("-12"));assertEquals("",CsvExport.cell(null));
    }
}
