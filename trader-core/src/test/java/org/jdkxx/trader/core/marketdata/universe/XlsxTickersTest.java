package org.jdkxx.trader.core.marketdata.universe;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxTickersTest {

    private static byte[] xlsx(String sharedStrings, String sheet) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write(sharedStrings.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheet.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    @Test
    void 取表头Ticker列以下的代码() throws Exception {
        String shared = "<sst><si><t>Fund Name:</t></si><si><t>Name</t></si><si><t>Ticker</t></si><si><t>APPLE INC</t></si><si><t>AAPL</t></si><si><t>BERKSHIRE</t></si><si><t>BRK.B</t></si><si><t>US DOLLAR</t></si><si><t>-</t></si></sst>";
        String sheet = "<worksheet><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\" t=\"s\"><v>2</v></c><c r=\"C2\"><v>1.5</v></c></row>"
                + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>3</v></c><c r=\"B3\" t=\"s\"><v>4</v></c><c r=\"C3\"><v>7.1</v></c></row>"
                + "<row r=\"4\"><c r=\"A4\" t=\"s\"><v>5</v></c><c r=\"B4\" t=\"s\"><v>6</v></c></row>"
                + "<row r=\"5\"><c r=\"A5\" t=\"s\"><v>7</v></c><c r=\"B5\" t=\"s\"><v>8</v></c></row>"
                + "</sheetData></worksheet>";

        Set<String> tickers = XlsxTickers.tickers(xlsx(shared, sheet), "Ticker");

        assertThat(tickers).containsExactly("AAPL", "BRK.B");
    }
}
